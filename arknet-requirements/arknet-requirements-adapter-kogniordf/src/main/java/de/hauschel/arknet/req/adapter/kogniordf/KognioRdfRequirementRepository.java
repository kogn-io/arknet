// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.InvalidLanguageTagException;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.DuplicateRequirementCodeException;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementReadConflictException;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.req.domain.ConstraintRef;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.req.domain.UnsupportedRequirementStatusException;

/**
 * Out-adapter: {@link RequirementRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF store).
 *
 * <p>Maps a {@link Requirement} to its opaque {@link RequirementId} as the subject IRI (minted
 * once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory}, never derived from the
 * business code), stored in one named graph shared by all requirements: five mandatory triples
 * (identifier, type, title, description, status) plus one or more mandatory
 * {@code arkreq:acceptanceCriterion} edges ({@code 1..n}, testable "Done when ..." criteria) to
 * own, positioned {@code arkreq:AcceptanceCriterion} resources (issue #266, mirroring
 * {@code arkreq:mainStep}/{@code arkreq:Step}) plus up to four optional triples for
 * {@code rationale}, {@code priority},
 * {@code motivatedBy} and {@code qualityCategory} - written only when the corresponding field is
 * non-{@code null} and read back so that requirements without them still match
 * ({@code priority}/{@code motivatedBy}/{@code qualityCategory} via {@code OPTIONAL} SPARQL
 * clauses; {@code rationale} via its own follow-up query, since it is language-tagged and would
 * multiply rows the way {@code title}/{@code description} would - see {@link #readRationales}). The {@code dcterms:identifier} triple carries the
 * human-readable {@link RequirementCode} ({@code FR-1}) - identity and label are deliberately
 * different triples on the same subject. This class depends only on the neutral kognio-rdf
 * ports ({@code terms} + {@code dataset}) and {@link SimpleRdf} - it never imports RDF4J or any
 * other backend-specific type. The backend ({@link DatasetLifecycle} implementation) is
 * supplied by the composition root.</p>
 *
 * <p><strong>ProjectId (local, single-user).</strong> Each {@link ProjectId}
 * is mapped 1:1 to a kognio-rdf {@link DatasetId}, so distinct projects are
 * fully isolated datasets. A future remote/team adapter (against a separate backend)
 * would use the same routing key differently (e.g. as a server-side project
 * selector), but the local embedded adapter already keeps projects separate.</p>
 *
 * <p><strong>Create vs. compare-and-set update (opaque identity).</strong> Because
 * identity is opaque and minted once, "insert or replace by identity" is no longer one coherent
 * operation. The transactional mechanics - the in-transaction existence checks for identity and
 * business-code collision, the SHACL gate, the commit-conflict translation, and the
 * head comparison - live in the shared {@link de.hauschel.arknet.persistence.WriteFunnel},
 * not here: {@link #create} and {@link #compareAndUpdate} only build the
 * candidate graph and, via {@code alreadyExists}/{@code duplicateCode}/{@code notFound}/
 * {@code headMismatch}, supply the exceptions the funnel throws - {@link
 * ResourceAlreadyExistsException} for an identity collision on create, {@link
 * DuplicateRequirementCodeException} for a business-code collision on create (also thrown when a
 * genuinely overlapping {@code SERIALIZABLE} transaction loses the commit itself, see
 * the funnel's own javadoc), {@link RequirementNotFoundException} for a missing subject on either
 * path, and {@link RequirementConcurrentlyModifiedException} for a stale {@code expectedHead} on
 * {@link #compareAndUpdate}. There is no unconditional update: every correction to an
 * already-created requirement goes through the compare-and-set guard, replacing the subject's
 * triples wholesale only once its head still matches.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate the candidate instance
 * graph against the requirements SHACL shapes before the write transaction opens,
 * {@link WriteConstraintViolationException} on a violation, nothing persisted - live in the
 * shared {@link de.hauschel.arknet.persistence.WriteFunnel}. The gate itself is
 * technology-neutral - only {@link KognioRdfRequirementRepositoryFactory} names RDF4J.</p>
 *
 * <p><strong>Term references arrive pre-resolved, identity-carrying.</strong>
 * {@link TermRef} carries the term's opaque subject {@link ResourceId} directly - resolving a
 * human-typed term code (e.g. {@code TERM-1}) against the shared project store, and rejecting
 * an unknown or ambiguous code, is done once by {@code KognioRdfTermLookup} at the moment a term
 * is linked (in the application service), not here on every write. This adapter therefore neither
 * queries the sibling terms graph nor re-verifies that a referenced subject still denotes a
 * {@code skos:Concept}; it trusts the identity it was handed, the same way it trusts {@code
 * motivatedBy} without re-resolving it. It still asserts each referenced subject's type as
 * {@code skos:Concept} in the SHACL write-gate's validation-only context (see below), because the
 * shape needs that type to fire correctly against a candidate graph that does not itself carry
 * the term's type triple.</p>
 *
 * <p><strong>That assertion is trusted, not verified.</strong> For a ref that {@code
 * KognioRdfTermLookup} produced the type did hold when the link was made - that query requires
 * it. For a ref that {@link #readUsesTerms} produced it may never have held: that read filters
 * for IRI-ness only and states no type condition, so a store-first edge can carry a
 * non-{@code Concept} target into the context, where asserting the type satisfies the gate's
 * {@code sh:class} with the very fact under test. The MCP tools cannot reach the case, and it is
 * no worse than before this change (such an edge survived there too, preserved by the same
 * blank-node preservation mechanism and equally unscrutinised) - but what makes it safe is that
 * it is unreachable, not that anything checked it.</p>
 *
 * <p><strong>Row multiplication on {@code priority}/{@code qualityCategory}.</strong>
 * Both properties carry no {@code sh:Violation}-severity {@code sh:maxCount} (unlike
 * {@code title}/{@code description}/{@code motivatedBy}, hardened separately): {@code priority}'s
 * {@code sh:maxCount 1} is {@code sh:Warning}-severity only (never blocks a write), and
 * {@code qualityCategory} carries no {@code sh:maxCount} at all. A store-first
 * requirement with two triples on either predicate therefore legally multiplies {@link #findAll}'s
 * SPARQL rows for one subject. {@link #findAll} groups rows per subject (the same
 * {@code LinkedHashMap} + {@code computeIfAbsent} pattern {@link #findByIds} already used) and
 * takes the first-seen value deterministically for each, logging a single {@code WARN} per
 * assembled {@link Requirement} when more than one distinct value was collected - visible instead
 * of silently duplicating the requirement in the result list. {@link #findByCode} is unaffected:
 * its single-row {@code findFirst()} is already internally consistent (one row = one coherent
 * value combination), it just returns a value combination the store cannot guarantee is "the"
 * one.</p>
 *
 * <p><strong>SHACL-legal but MVP-unsupported status.</strong> {@code requirements-shapes.ttl}'s
 * {@code Requirement-status} shape allows six status individuals via {@code sh:in}, but
 * {@link de.hauschel.arknet.req.domain.RequirementStatus} implements only two ({@code
 * PROPOSED}/{@code ACCEPTED}). A store-first requirement carrying one of the other four
 * is therefore SHACL-legal but cannot be decoded: {@link #findByCode}, {@link #findCurrentByCode}
 * and {@link #findAll} all throw {@link
 * de.hauschel.arknet.req.domain.UnsupportedRequirementStatusException} for it, naming the
 * requirement and the unsupported status, rather than silently filtering it out of the result (see
 * {@link #statusFromIri}).</p>
 *
 * <p><strong>Type-mismatched {@code priority}/{@code motivatedBy}/{@code qualityCategory}.</strong>
 * All three shapes are {@code sh:Warning}-severity (never blocks a write), and none declares
 * {@code sh:nodeKind}: a store-first edit can therefore legally write, say,
 * {@code arkreq:motivatedBy "text"} as a literal instead of an IRI. Unlike {@code status} (a
 * mandatory field, so failing loudly is the right call), these three are already optional domain
 * fields - {@link #priorityOf}/{@link #motivatedByOf}/{@link #qualityCategoryOf} guard their cast
 * with an {@code instanceof} check and log a single {@code WARN} plus read the value as "not set"
 * on a mismatch, rather than letting an uncaught {@link ClassCastException} abort the whole
 * {@link #findAll} batch the same way an unguarded {@link #statusFromIri} once did.</p>
 */
public class KognioRdfRequirementRepository implements RequirementRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfRequirementRepository.class);

    private static final String ARKREQ_NAMESPACE = "https://w3id.org/arknet/requirements#";
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";

    /**
     * Same literal as {@code KognioRdfConstraintRepository}'s own {@code CONSTRAINTS_GRAPH} -
     * duplicated rather than shared via a public constant, matching this codebase's existing
     * convention of small, adapter-private literals (see e.g. {@code REQUIREMENTS_GRAPH} itself,
     * never exposed outside this package either). Needed here so {@link
     * #constraintAssertedContext} can read a linked constraint's own triples for the SHACL gate.
     */
    private static final String CONSTRAINTS_GRAPH = "https://w3id.org/arknet/model/constraints";

    private static final String CONCEPT_TYPE = ArkreqVocabulary.CONCEPT_TYPE;
    private static final String USES_TERM_PROPERTY = ArkreqVocabulary.USES_TERM;
    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String NON_FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.NON_FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String STATUS_PROPERTY = ARKREQ_NAMESPACE + "status";
    private static final String PROPOSED_STATUS = ARKREQ_NAMESPACE + "Proposed";
    private static final String ACCEPTED_STATUS = ARKREQ_NAMESPACE + "Accepted";
    private static final String TITLE_PROPERTY = VocabDct.NAMESPACE + "title";
    private static final String DESCRIPTION_PROPERTY = VocabDct.NAMESPACE + "description";
    private static final String RATIONALE_PROPERTY = ARKREQ_NAMESPACE + "rationale";
    private static final String PRIORITY_PROPERTY = ARKREQ_NAMESPACE + "priority";
    private static final String MOTIVATED_BY_PROPERTY = ARKREQ_NAMESPACE + "motivatedBy";
    private static final String QUALITY_CATEGORY_PROPERTY = ARKREQ_NAMESPACE + "qualityCategory";
    private static final String ACCEPTANCE_CRITERION_PROPERTY = ArkreqVocabulary.ACCEPTANCE_CRITERION;
    private static final String CONSTRAINED_BY_PROPERTY = ArkreqVocabulary.CONSTRAINED_BY;

    /**
     * {@code arkreq:AcceptanceCriterion} (issue #266) - the own resource type an
     * {@code arkreq:acceptanceCriterion} edge now points at, mirroring
     * {@code KognioRdfUseCaseRepository}'s {@code STEP_TYPE}. Adapter-private (not shared via
     * {@link ArkreqVocabulary}) because, unlike {@link #ACCEPTANCE_CRITERION_PROPERTY}/
     * {@link #CRITERION_TEXT_PROPERTY}, nothing outside this adapter needs to test a node's type
     * against it - the traceability read path only ever follows the edge straight to
     * {@code criterionText}, the same way it never checks {@code arkreq:Step}'s type either.
     */
    private static final String ACCEPTANCE_CRITERION_TYPE = ARKREQ_NAMESPACE + "AcceptanceCriterion";

    /**
     * {@code arkreq:position} (issue #266) - shared with {@code arkreq:Step}'s own ordinal, see
     * that ontology term's comment. Adapter-private for the same reason
     * {@code KognioRdfUseCaseRepository}'s own {@code POSITION_PROPERTY} is: nothing outside the
     * two out-adapters that write it needs the IRI.
     */
    private static final String POSITION_PROPERTY = ARKREQ_NAMESPACE + "position";

    /** {@code arkreq:criterionText} (issue #266) - AcceptanceCriterion's testable "Done when ..." text. */
    private static final String CRITERION_TEXT_PROPERTY = ArkreqVocabulary.CRITERION_TEXT;

    /**
     * Stands in for a requirement that predates the mandatory acceptance-criterion invariant, or
     * whose acceptance-criterion positions are store-first malformed (a gap or
     * duplicate): {@code arkreq:acceptanceCriterion} became mandatory ({@code sh:minCount 1}) only
     * with this field, so a requirement written by an older {@code req_add} carries none. The gate
     * blocks that state on the next <em>write</em>, but reading is not gated - and
     * {@link Requirement}'s constructor rejects an empty or non-consecutively-positioned list
     * unconditionally, so without this substitution {@link #findByCode}/{@link #findAll} would
     * throw for every such requirement instead of returning it. Substituting here, at the adapter
     * boundary, keeps that domain invariant intact (it never sees an illegal list) while surfacing
     * the gap instead of crashing - see {@link #acceptanceCriteriaOrLegacyPlaceholder}.
     */
    private static final List<AcceptanceCriterion> LEGACY_ACCEPTANCE_CRITERION_PLACEHOLDER =
            List.of(new AcceptanceCriterion(1, "(Altdatensatz - kein Akzeptanzkriterium hinterlegt)"));

    private static final String MUST_HAVE_PRIORITY = ARKREQ_NAMESPACE + "MustHave";
    private static final String SHOULD_HAVE_PRIORITY = ARKREQ_NAMESPACE + "ShouldHave";
    private static final String COULD_HAVE_PRIORITY = ARKREQ_NAMESPACE + "CouldHave";
    private static final String WONT_HAVE_PRIORITY = ARKREQ_NAMESPACE + "WontHave";

    /**
     * Bound on {@link #readInTransaction}'s retry loop (issue #171). A read-only transaction that
     * lost this race is resolved by a single retry in the overwhelming majority of cases, since
     * the retry simply re-runs the identical read against the store's now-current state; this
     * bound exists only so a pathological, sustained storm of concurrent writers against the same
     * requirement(s) fails loudly instead of retrying forever - the exact same "bounded, then loud"
     * shape (and, as it turns out, the exact same number) as {@link CodeAssignment}'s own retry
     * bound, so this reuses that constant instead of duplicating it under a private name.
     */
    private static final int MAX_READ_RETRY_ATTEMPTS = CodeAssignment.DEFAULT_MAX_ATTEMPTS;

    private final DatasetLifecycle lifecycle;
    private final ResourceIdFactory resourceIdFactory;
    private final DisplayLocale displayLocale;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle         the kognio-rdf dataset lifecycle to acquire datasets from - used by
     *                          the read paths (must not be {@code null})
     * @param resourceIdFactory mints the opaque IRI of each derived acceptance-criterion resource
     *                          (issue #266; must not be {@code null}) - the same kernel-owned
     *                          scheme {@code KognioRdfUseCaseRepository} uses to mint its own
     *                          derived step resources
     * @param displayLocale     the display-language preference selecting which {@code
     *                          dcterms:title}/{@code dcterms:description}/
     *                          {@code arkreq:rationale}/each acceptance
     *                          criterion's {@code arkreq:criterionText} the read paths surface for
     *                          a multilingual requirement (must not be {@code null})
     * @param funnel        the shared write funnel every write runs through - both
     *                      {@link #create} and {@link #compareAndUpdate} (must not be
     *                      {@code null})
     */
    KognioRdfRequirementRepository(DatasetLifecycle lifecycle, ResourceIdFactory resourceIdFactory,
            DisplayLocale displayLocale, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, Requirement requirement, String language) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(requirement, "requirement");
        String tag = LanguageTag.canonicalize(language);
        Map<Integer, String> criteriaTags = new LinkedHashMap<>();
        requirement.acceptanceCriteria().forEach(criterion -> criteriaTags.put(criterion.position(), tag));

        // ResourceId#of validates IRIREF-safety at construction, so requirement.id()'s
        // wrapped IRI is already guaranteed safe to embed here - no separate check needed.
        String subjectIriString = requirement.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);

        // 1. Every term/constraint reference already carries its resolved identity (see
        //    class-level note), guaranteed IRIREF-safe by ResourceId#of same as the subject
        //    above.
        List<IRI> termIris = requirement.usesTerms().stream()
                .map(this::termIriFor)
                .toList();
        List<IRI> constraintIris = requirement.constrainedBy().stream()
                .map(this::constraintIriFor)
                .toList();

        // 2. Build the candidate graph and, from it, the structural gate check. The usesTerm
        //    shape carries an sh:class skos:Concept constraint, but the type triples of the
        //    referenced terms live in the sibling terms graph, not in this candidate graph. They
        //    are handed to the gate as a validation-only asserted context (never persisted here).
        //    This is safe: the term was already proven to exist and be a concept at the moment it
        //    was resolved (KognioRdfTermLookup, called once from the application service when the
        //    term was linked) - the lookup, not the shape, is what keeps the edge non-dangling;
        //    this adapter no longer re-verifies it. constrainedBy is different (see
        //    #constraintAssertedContext): its target's own ConstraintShape lives in this same
        //    shapes file, so a bare type assertion is not enough to satisfy it.
        RequirementCandidate candidate = buildCandidateGraph(
                subjectIri, requirement, termIris, constraintIris, tag, tag, tag, criteriaTags);
        Graph graph = candidate.graph();
        Graph assertedContext = rdf.createGraph();
        for (IRI termIri : termIris) {
            assertedContext.add(termIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
        }
        constraintAssertedContext(projectId, constraintIris, assertedContext);

        IRI graphIri = rdf.createIRI(REQUIREMENTS_GRAPH);

        funnel.create(new DatasetId(projectId.value()), REQUIREMENTS_GRAPH, subjectIriString,
                requirement.code().value(), graph, assertedContext,
                () -> new ResourceAlreadyExistsException(projectId, requirement.id().value()),
                () -> new DuplicateRequirementCodeException(projectId, requirement.code()),
                tx -> replaceTriplesForCreate(tx, graphIri, graph));
    }

    /**
     * Compare-and-set update (degenerated from a full-snapshot comparison to a head
     * comparison decision 4): replaces the requirement's triples only if
     * its {@code arkprov:head} still equals {@code expectedHead} at the moment the shared
     * {@link WriteFunnel} checks it inside the write transaction - closing the lost-update window
     * a plain read (via {@link #findCurrentByCode}) followed by an unconditional replace would
     * otherwise leave open between the read and the write.
     */
    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Requirement updated,
            String titleLanguage, String descriptionLanguage, String rationaleLanguage,
            Map<Integer, String> acceptanceCriteriaLanguageByPosition, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updated, "updated");
        Objects.requireNonNull(acceptanceCriteriaLanguageByPosition, "acceptanceCriteriaLanguageByPosition");
        String titleTag = canonicalizeLenient(titleLanguage);
        String descriptionTag = canonicalizeLenient(descriptionLanguage);
        String rationaleTag = canonicalizeLenient(rationaleLanguage);
        String defaultTag = canonicalizeLenient(defaultLanguage);
        Map<Integer, String> criteriaTags = new LinkedHashMap<>();
        acceptanceCriteriaLanguageByPosition.forEach((position, tag) -> criteriaTags.put(position, canonicalizeLenient(tag)));

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);

        List<IRI> termIris = updated.usesTerms().stream()
                .map(this::termIriFor)
                .toList();
        List<IRI> constraintIris = updated.constrainedBy().stream()
                .map(this::constraintIriFor)
                .toList();
        RequirementCandidate candidate = buildCandidateGraph(
                subjectIri, updated, termIris, constraintIris, titleTag, descriptionTag, rationaleTag,
                criteriaTags);
        Graph graph = candidate.graph();
        Graph assertedContext = rdf.createGraph();
        for (IRI termIri : termIris) {
            assertedContext.add(termIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
        }
        constraintAssertedContext(projectId, constraintIris, assertedContext);
        IRI graphIri = rdf.createIRI(REQUIREMENTS_GRAPH);

        funnel.compareAndUpdate(new DatasetId(projectId.value()), REQUIREMENTS_GRAPH, subjectIriString,
                expectedHead == null ? null : expectedHead.value(), graph, assertedContext,
                () -> new RequirementNotFoundException(projectId, updated.code()),
                () -> new RequirementConcurrentlyModifiedException(projectId, updated.code()),
                tx -> replaceTriplesForUpdate(tx, graphIri, subjectIri, subject, graph, titleTag, descriptionTag,
                        rationaleTag, updated.rationale() != null, criteriaTags, defaultTag,
                        candidate.criterionIriByPosition()));
    }

    /**
     * {@link #buildCandidateGraph}'s result: the candidate {@link Graph} itself, plus the opaque
     * IRI freshly minted for each acceptance-criterion position (issue #266) - a criterion node is
     * an aggregate-internal value object re-minted on every write (mirroring
     * {@code KognioRdfUseCaseRepository}'s {@code Step} nodes), so {@link #replaceTriplesForUpdate}
     * needs this mapping to know which new subject a preserved other-language criterion-text
     * variant re-attaches to.
     */
    private record RequirementCandidate(Graph graph, Map<Integer, IRI> criterionIriByPosition) {
    }

    /**
     * Builds the candidate graph for one requirement's triples: five mandatory triples
     * (identifier, type, title, description, status), one or more mandatory
     * {@code arkreq:acceptanceCriterion} edges to freshly minted {@code arkreq:AcceptanceCriterion}
     * resources (issue #266; own {@code arkreq:position}/{@code arkreq:criterionText} triples each,
     * mirroring {@code arkreq:mainStep}/{@code arkreq:Step}), up to four optional triples
     * ({@code rationale}, {@code priority}, {@code motivatedBy}, {@code qualityCategory}), zero or
     * more
     * {@code arkreq:usesTerm} edges to {@code termIris}, and zero or more
     * {@code oslc_rm:constrainedBy} edges to {@code constraintIris}. Shared by {@link #create} and
     * {@link #compareAndUpdate} so both write paths serialise a {@link Requirement} identically.
     * {@code title}/{@code description}/a non-{@code null} {@code rationale}/each criterion's text
     * are written as the language-tagged
     * (or, for a {@code null} tag, plain untagged) literal named by {@code titleTag}/
     * {@code descriptionTag}/{@code rationaleTag}/{@code criteriaTagByPosition} - never more than
     * one each, since
     * preserving every other language variant a store-first or earlier {@code req_update}
     * may have left is {@link #replaceTriplesForUpdate}'s job, run after this candidate has already
     * passed the gate.
     */
    private RequirementCandidate buildCandidateGraph(IRI subjectIri, Requirement requirement, List<IRI> termIris,
            List<IRI> constraintIris, String titleTag, String descriptionTag, String rationaleTag,
            Map<Integer, String> criteriaTagByPosition) {
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(typeIriFor(requirement.type())));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(requirement.code().value()));
        graph.add(subjectIri, rdf.createIRI(TITLE_PROPERTY), literalOf(requirement.title(), titleTag));
        graph.add(subjectIri, rdf.createIRI(DESCRIPTION_PROPERTY),
                literalOf(requirement.description(), descriptionTag));
        graph.add(subjectIri, rdf.createIRI(STATUS_PROPERTY), rdf.createIRI(statusIriFor(requirement.status())));
        if (requirement.rationale() != null) {
            graph.add(subjectIri, rdf.createIRI(RATIONALE_PROPERTY),
                    literalOf(requirement.rationale(), rationaleTag));
        }
        if (requirement.priority() != null) {
            graph.add(subjectIri, rdf.createIRI(PRIORITY_PROPERTY),
                    rdf.createIRI(priorityIriFor(requirement.priority())));
        }
        if (requirement.motivatedBy() != null) {
            graph.add(subjectIri, rdf.createIRI(MOTIVATED_BY_PROPERTY), rdf.createIRI(requirement.motivatedBy()));
        }
        if (requirement.qualityCategory() != null) {
            graph.add(subjectIri, rdf.createIRI(QUALITY_CATEGORY_PROPERTY),
                    rdf.createLiteral(requirement.qualityCategory()));
        }
        for (IRI termIri : termIris) {
            graph.add(subjectIri, rdf.createIRI(USES_TERM_PROPERTY), termIri);
        }
        Map<Integer, IRI> criterionIriByPosition = new LinkedHashMap<>();
        for (AcceptanceCriterion criterion : requirement.acceptanceCriteria()) {
            IRI criterionIri = mintCriterionIri();
            criterionIriByPosition.put(criterion.position(), criterionIri);
            graph.add(subjectIri, rdf.createIRI(ACCEPTANCE_CRITERION_PROPERTY), criterionIri);
            graph.add(criterionIri, VocabRdf.TYPE, rdf.createIRI(ACCEPTANCE_CRITERION_TYPE));
            graph.add(criterionIri, rdf.createIRI(POSITION_PROPERTY),
                    rdf.createLiteral(Integer.toString(criterion.position()), VocabXsd.INTEGER));
            graph.add(criterionIri, rdf.createIRI(CRITERION_TEXT_PROPERTY),
                    literalOf(criterion.text(), criteriaTagByPosition.get(criterion.position())));
        }
        for (IRI constraintIri : constraintIris) {
            graph.add(subjectIri, rdf.createIRI(CONSTRAINED_BY_PROPERTY), constraintIri);
        }
        return new RequirementCandidate(graph, criterionIriByPosition);
    }

    /**
     * Mints an opaque IRI for a derived acceptance-criterion resource (issue #266), from the same
     * kernel scheme as the requirement root - mirrors
     * {@code KognioRdfUseCaseRepository#mintStepIri}. A criterion is a value object with no stable
     * identity of its own: nothing outside this adapter ever references its IRI.
     */
    private IRI mintCriterionIri() {
        return rdf.createIRI(resourceIdFactory.newId().value());
    }

    /**
     * Writes {@code graph} for a freshly minted subject inside an already-open write transaction -
     * the tail of {@link #create}, reached once the funnel's own existence check has decided the
     * write may proceed. Unlike {@link #replaceTriplesForUpdate}, there is nothing under this
     * identity yet: no triples to delete, and consequently no {@code arkreq:usesTerm} edge that
     * could need preserving (that concern is specific to replacing an already-existing subject's
     * triples - see {@link #replaceTriplesForUpdate}'s javadoc).
     */
    private void replaceTriplesForCreate(DatasetTx tx, IRI graphIri, Graph graph) {
        tx.add(graphIri, graph);
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write
     * transaction - the tail of {@link #compareAndUpdate}, reached once the funnel's own head
     * comparison has decided the write should proceed. (The read-modify-write tail of a
     * {@code create} instead runs {@link #replaceTriplesForCreate}: a freshly minted identity has
     * nothing to delete or preserve.)
     *
     * <p>Reduced complement of what {@link #readUsesTerms} can now read: since reading
     * no longer joins into the terms graph (a usesTerm edge's target IRI <em>is</em> the
     * {@code TermRef}, no re-derivation needed), the only edges {@code Requirement#usesTerms()}
     * can never carry are ones whose target is not an IRI at all - a store-first edge
     * may legally point at a blank node ({@code [ a skos:Concept ]}), which {@code ResourceId}
     * cannot represent. The preservation query below finds exactly those.</p>
     *
     * <p><strong>Same preservation, for {@code title}/{@code description}/{@code rationale}'s
     * other language variants.</strong> They may each legally carry several language-tagged
     * literals (SKOS-S14-style {@code sh:uniqueLang}); {@code graph} (from
     * {@link #buildCandidateGraph}) carries exactly one of each, tagged {@code titleTag}/
     * {@code descriptionTag}/{@code rationaleTag}. Every <em>other</em> existing language variant
     * is captured here,
     * before {@code deleteExisting} would otherwise wipe it along with everything else on this
     * subject, and re-attached afterwards - the identical capture/delete/reattach shape
     * {@code unjoinableUsesTerms} already uses, just scoped by language tag instead of by
     * IRI-ness. A variant already carrying {@code titleTag}/{@code descriptionTag}/
     * {@code rationaleTag} is <em>not</em> re-attached: it is exactly the one {@code graph} is
     * about to (re)write, so
     * re-attaching it too would duplicate it.</p>
     *
     * <p><strong>{@code rationale} is optional, so its preservation has a second mode (issue
     * #321).</strong> {@code rationaleWritten} says whether {@code graph} carries an
     * {@code arkreq:rationale} literal at all. When it does, the rule above applies unchanged.
     * When it does not - the caller passed a {@code null} rationale, which every port above means
     * as "leave it alone", never as "remove the recorded reason" - <em>every</em> existing
     * variant is preserved, including one under {@code rationaleTag} and including an untagged
     * one: with nothing being written under any tag, no existing literal is a stale duplicate of
     * a fresh write, and the issue #258 sweep below would otherwise silently delete a reason
     * nobody asked to remove.</p>
     *
     * <p><strong>Sweeping a stale untagged sibling of a default-language write (issue #258).</strong>
     * {@code defaultTag} is the target project's configured default language, canonicalized. When
     * {@code titleTag}/{@code descriptionTag} equals it, the literal {@code graph} is about to
     * write <em>is</em>, by construction, what an omitted {@code language} argument would have
     * resolved to ({@link de.hauschel.arknet.kernel.LanguageTag#resolveWriteLanguage}) - so an
     * existing <em>untagged</em> literal on that same predicate is no longer a genuine other-
     * language variant to preserve, it is a stale duplicate of the very value now being written
     * under its proper tag, left over from before this project had, or before a caller supplied,
     * a language. {@link #otherLanguageLiterals} excludes it from what it preserves in exactly
     * that one case, so it is silently dropped by {@code deleteExisting} along with everything
     * else - a lazy, incremental normalisation triggered only by the next write that happens to
     * touch this field, not a batch migration. A write under any other tag (including a
     * non-default {@code language} explicitly supplied against a project that does have a
     * default) leaves an existing untagged literal untouched, exactly as before this fix -
     * {@code defaultTag} being {@code null} (no project default configured) never matches a
     * non-{@code null} {@code titleTag}/{@code descriptionTag}, so this sweep is unreachable for
     * a project without one.</p>
     *
     * <p><strong>Acceptance-criterion resources are followed and re-minted too (issue
     * #266).</strong> {@code deleteExisting} now also follows every {@code arkreq:acceptanceCriterion}
     * edge and deletes the pointed-at {@code arkreq:AcceptanceCriterion} resource's own triples -
     * mirroring {@code KognioRdfUseCaseRepository}'s {@code mainStep}/{@code extensionStep}
     * traversal for {@code arkreq:Step}: a criterion node has no stable identity of its own (see
     * {@link #mintCriterionIri}), so leaving its old triples behind would only accumulate orphaned
     * garbage no future read ever reaches again. {@code criteriaTagByPosition}/
     * {@code newCriterionIriByPosition} extend the very same capture-before-delete/reattach-after-
     * write mechanism to each criterion's {@code arkreq:criterionText}, keyed by
     * {@code arkreq:position} rather than the (about-to-be-deleted) criterion IRI - exactly
     * {@code KognioRdfUseCaseRepository#otherLanguageStepTexts}'s own reasoning, safe here without
     * that class's extra {@code stableExtensionPrefixLength} guard because {@code req_update} never
     * lets a criterion's position shift (append-only + in-place patch, never reorder/delete - see
     * {@code Requirement#withAppendedAcceptanceCriteria}/{@code #withAcceptanceCriteriaTextPatches}).
     */
    private void replaceTriplesForUpdate(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            String titleTag, String descriptionTag, String rationaleTag, boolean rationaleWritten,
            Map<Integer, String> criteriaTagByPosition, String defaultTag,
            Map<Integer, IRI> newCriterionIriByPosition) {
        String selectUnjoinableUsesTerms = "SELECT ?term WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + USES_TERM_PROPERTY + "> ?term } "
                + "FILTER(!isIRI(?term)) }";
        // constrainedBy's shape carries sh:nodeKind sh:IRI - unlike usesTerm, a non-IRI target is
        // SHACL-illegal at write time - but that gate only guards this adapter's own writes, never
        // a store-first edit, so the same non-IRI edge can and does exist here too; the
        // same preserve-past-the-gate mechanism applies, for the same reason.
        String selectUnjoinableConstrainedBy = "SELECT ?constraint WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + CONSTRAINED_BY_PROPERTY
                + "> ?constraint } FILTER(!isIRI(?constraint)) }";
        // Deletes the requirement subject's own triples AND, following each acceptanceCriterion
        // edge, every existing AcceptanceCriterion resource's triples (issue #266) - see this
        // method's class-level note above. Mirrors KognioRdfUseCaseRepository#write's own
        // deleteExisting for mainStep/extensionStep exactly, one edge instead of two.
        String deleteExisting = "DELETE { GRAPH <" + REQUIREMENTS_GRAPH + "> { ?s ?p ?o } } WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "{ " + subject + " ?p ?o . BIND(" + subject + " AS ?s) } UNION "
                + "{ " + subject + " <" + ACCEPTANCE_CRITERION_PROPERTY + "> ?s . ?s ?p ?o } } }";

        // Capture what a replacing write is about to destroy but could never have read (see
        // selectUnjoinableUsesTerms above) before deleteExisting wipes it, inside this same
        // transaction - a separate read beforehand would leave a TOCTOU window the caller's own
        // head comparison deliberately avoids. The binding is read as a bare RDFTerm, not
        // cast to IRI: arkreq:usesTerm carries no sh:nodeKind constraint, so its target is
        // RDF-legally allowed to be a blank node, and a store-first edge can and does point at
        // one - exactly the non-IRI target selectUnjoinableUsesTerms filters for. Casting here
        // would trade the earlier silent data loss for a crash on every update of the affected
        // requirement - a regression, not a fix.
        List<RDFTerm> unjoinableUsesTerms = tx.select(selectUnjoinableUsesTerms).map(row -> termOf(row, "term"))
                .toList();
        List<RDFTerm> unjoinableConstrainedBy = tx.select(selectUnjoinableConstrainedBy)
                .map(row -> termOf(row, "constraint"))
                .toList();
        List<Literal> preservedTitles = otherLanguageLiterals(tx, subject, TITLE_PROPERTY, titleTag, defaultTag);
        List<Literal> preservedDescriptions =
                otherLanguageLiterals(tx, subject, DESCRIPTION_PROPERTY, descriptionTag, defaultTag);
        // An unwritten rationale preserves every variant, not just the other-language ones - see
        // this method's "rationale is optional" note above.
        List<Literal> preservedRationales = rationaleWritten
                ? otherLanguageLiterals(tx, subject, RATIONALE_PROPERTY, rationaleTag, defaultTag)
                : allLiterals(tx, subject, RATIONALE_PROPERTY);
        Map<Integer, List<Literal>> preservedCriteriaTextsByPosition =
                otherLanguageAcceptanceCriterionTexts(tx, subject, criteriaTagByPosition, defaultTag);
        tx.update(deleteExisting);
        tx.add(graphIri, graph);
        // Re-attach the preserved edges only after the gate has already run and the rewritten
        // graph is committed - never mixed into `graph` before gate.enforce ran on it. A
        // preserved edge's target is, by construction, not an IRI and therefore cannot appear in
        // the write's assertedContext (built from the requirement's termIris only, which are
        // always IRIs); feeding it to the gate would fail the usesTerm shape's sh:class
        // skos:Concept constraint and block every future update of this requirement. Appending it
        // here instead is safe precisely because nothing new is introduced - the edge already
        // existed in the store and is carried forward untouched. A blank-node target keeps its
        // identity across this delete-and-readd cycle: deleteExisting only removes triples whose
        // subject is the requirement, never the target node's own triples, and the RDFTerm
        // captured by the select above is the same object tx.add below writes back - RDF4J
        // compares blank nodes by id, so this re-attaches to the very node the store already
        // knows, not a fresh one.
        if (!unjoinableUsesTerms.isEmpty()) {
            Graph preservedEdges = rdf.createGraph();
            for (RDFTerm termNode : unjoinableUsesTerms) {
                preservedEdges.add(subjectIri, rdf.createIRI(USES_TERM_PROPERTY), termNode);
            }
            tx.add(graphIri, preservedEdges);
        }
        if (!unjoinableConstrainedBy.isEmpty()) {
            Graph preservedEdges = rdf.createGraph();
            for (RDFTerm constraintNode : unjoinableConstrainedBy) {
                preservedEdges.add(subjectIri, rdf.createIRI(CONSTRAINED_BY_PROPERTY), constraintNode);
            }
            tx.add(graphIri, preservedEdges);
        }
        if (!preservedTitles.isEmpty() || !preservedDescriptions.isEmpty() || !preservedRationales.isEmpty()
                || !preservedCriteriaTextsByPosition.isEmpty()) {
            Graph preservedLanguageVariants = rdf.createGraph();
            for (Literal title : preservedTitles) {
                preservedLanguageVariants.add(subjectIri, rdf.createIRI(TITLE_PROPERTY), title);
            }
            for (Literal description : preservedDescriptions) {
                preservedLanguageVariants.add(subjectIri, rdf.createIRI(DESCRIPTION_PROPERTY), description);
            }
            for (Literal rationale : preservedRationales) {
                preservedLanguageVariants.add(subjectIri, rdf.createIRI(RATIONALE_PROPERTY), rationale);
            }
            // A preserved criterion-text variant re-attaches to the FRESHLY minted criterion IRI at
            // the same position (newCriterionIriByPosition) - the old criterion subject no longer
            // exists after deleteExisting, but nothing outside this adapter ever referenced it
            // (mirrors KognioRdfUseCaseRepository's identical Step re-attachment).
            preservedCriteriaTextsByPosition.forEach((position, texts) -> {
                IRI newCriterionIri = newCriterionIriByPosition.get(position);
                if (newCriterionIri != null) {
                    for (Literal text : texts) {
                        preservedLanguageVariants.add(newCriterionIri, rdf.createIRI(CRITERION_TEXT_PROPERTY), text);
                    }
                }
            });
            tx.add(graphIri, preservedLanguageVariants);
        }
    }

    /**
     * Every existing literal of {@code subject} on {@code predicateIri}, captured inside the live
     * write transaction before {@code deleteExisting} wipes the subject - {@link
     * #otherLanguageLiterals} without any exclusion. Used for the one predicate this adapter may
     * legitimately write nothing at all for on an update ({@code arkreq:rationale}, issue #321):
     * with no literal being written, no existing one is the variant being replaced and none is a
     * stale untagged duplicate, so all of them are carried forward verbatim.
     */
    private List<Literal> allLiterals(DatasetTx tx, String subject, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + subject + " <" + predicateIri + "> ?o } }";
        return tx.select(query).map(row -> literalOf(row, "o")).toList();
    }

    /**
     * Reads every existing literal of {@code subject} on {@code predicateIri} whose language tag
     * differs from {@code writtenTag}, inside the live write transaction, before
     * {@code deleteExisting} would otherwise wipe them along with the rest of the subject's
     * triples. Mirrors {@code KognioRdfTermRepository#deleteTriplesOfLanguage}'s scoping logic,
     * inverted into a capture-and-reattach rather than a targeted delete, because
     * {@link #replaceTriplesForUpdate}'s {@code deleteExisting} is an unconditional
     * whole-subject wipe, not a per-predicate patch.
     *
     * @param writtenTag the tag of the literal {@code graph} is about to (re)write for this
     *                   predicate, or {@code null} for untagged - excluded here since it is not
     *                   being preserved, it is being replaced
     * @param defaultTag the target project's configured default language, canonicalized, or
     *                   {@code null} if it has none - when it equals {@code writtenTag}, an
     *                   existing <em>untagged</em> literal is excluded here too (issue #258): see
     *                   {@link #replaceTriplesForUpdate}'s "Sweeping a stale untagged sibling"
     *                   note for why that untagged literal is a stale duplicate, not a genuine
     *                   other-language variant, in exactly that case
     */
    private List<Literal> otherLanguageLiterals(
            DatasetTx tx, String subject, String predicateIri, String writtenTag, String defaultTag) {
        String query = "SELECT ?o WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + subject + " <" + predicateIri + "> ?o } }";
        boolean sweepUntagged = defaultTag != null && defaultTag.equals(writtenTag);
        return tx.select(query)
                .map(row -> literalOf(row, "o"))
                .filter(literal -> {
                    String existingTag = canonicalizeLenient(literal.getLanguageTag().orElse(null));
                    if (sweepUntagged && existingTag == null) {
                        return false;
                    }
                    return !Objects.equals(existingTag, writtenTag);
                })
                .toList();
    }

    /**
     * {@link #otherLanguageLiterals} for {@code arkreq:criterionText}, keyed by acceptance-criterion
     * {@code arkreq:position} rather than by (about-to-be-deleted) criterion IRI (issue #266) - a
     * criterion's own subject is re-minted on every write ({@link #mintCriterionIri}), so what
     * survives an update is the position's <em>other-language text</em>, re-attached to whichever
     * new criterion IRI ends up at that same position - not the old criterion IRI itself. Mirrors
     * {@code KognioRdfUseCaseRepository#otherLanguageStepTexts} exactly, minus that method's
     * {@code stableExtensionPrefixLength} restructuring guard: unreachable here, since
     * {@code req_update} never reorders or removes an acceptance criterion (append-only + in-place
     * patch), so every position this query finds is by construction still stable.
     *
     * @param defaultTag the target project's configured default language, canonicalized, or
     *                   {@code null} if it has none - same issue #258 sweep as
     *                   {@link #otherLanguageLiterals}'s own {@code defaultTag}, applied per
     *                   position: a position whose written tag equals {@code defaultTag} sweeps an
     *                   existing untagged criterion text at that position instead of preserving it
     */
    private Map<Integer, List<Literal>> otherLanguageAcceptanceCriterionTexts(
            DatasetTx tx, String subject, Map<Integer, String> criteriaTagByPosition, String defaultTag) {
        String query = "SELECT ?position ?text WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + subject + " <" + ACCEPTANCE_CRITERION_PROPERTY + "> ?criterion . "
                + "?criterion <" + POSITION_PROPERTY + "> ?position ; <" + CRITERION_TEXT_PROPERTY + "> ?text } }";
        Map<Integer, List<Literal>> byPosition = new LinkedHashMap<>();
        tx.select(query).forEach(row -> {
            int position = Integer.parseInt(literalOf(row, "position").getLexicalForm());
            Literal text = literalOf(row, "text");
            String writtenTag = criteriaTagByPosition.get(position);
            String existingTag = canonicalizeLenient(text.getLanguageTag().orElse(null));
            boolean sweepUntagged = defaultTag != null && defaultTag.equals(writtenTag) && existingTag == null;
            if (!sweepUntagged && !Objects.equals(existingTag, writtenTag)) {
                byPosition.computeIfAbsent(position, key -> new ArrayList<>()).add(text);
            }
        });
        return byPosition;
    }

    /**
     * {@link LanguageTag#canonicalize(String)}, but falls back to {@code null} (untagged) instead
     * of throwing {@link InvalidLanguageTagException} - used at the two places in this class that
     * handle a tag which may be a verbatim pass-through of whatever is already sitting in the store
     * ({@code RequirementService#updateWithOptimisticRetry} reads {@code current.titleLanguage()}/
     * {@code current.descriptionLanguage()} straight off {@link LocalizedLiteral#languageTag()}, the
     * raw tag as read from the store, never re-validated), rather than a freshly caller-supplied
     * value already validated before it reached this adapter (as {@link #create}'s {@code language}
     * argument is - that call site stays on the strict {@link LanguageTag#canonicalize(String)}).
     *
     * <p>The fallback is {@code null}, not the raw tag, deliberately: RDF4J's own literal
     * construction ({@code Literals#isValidLanguageTag}, reached from {@link #compareAndUpdate}'s
     * SHACL gate by way of {@code buildCandidateGraph}) rejects exactly the same not-well-formed
     * tags {@link LanguageTag#canonicalize(String)} does - via the identical {@code
     * Locale.Builder#setLanguageTag} check - so re-embedding the untouched raw tag would still
     * crash the gate a moment later with a different exception, not fix anything. Falling back to
     * {@code null} instead writes the pass-through value as a plain, untagged literal - the one
     * literal form no tag validation ever rejects - so a requirement whose store-first
     * title/description tag is irreparably malformed becomes editable again (at the cost of that
     * one field's language tag) rather than permanently blocking every future correction, even one
     * that never touches title/description at all.</p>
     *
     * <p>{@link #otherLanguageLiterals} uses the same helper for its comparison, not to fall back
     * on write: canonicalizing the read literal's tag before comparing makes the check robust
     * against case-only drift (raw {@code "en-us"} vs. the now-canonicalized {@code "en-US"} being
     * (re)written would otherwise look like two different languages, preserving the former as a
     * spurious "other" variant and duplicating the literal) - and folding an irreparably malformed
     * existing tag to {@code null} there too keeps it consistent with what {@link #compareAndUpdate}
     * is about to write for that same case, rather than preserving it as a bogus "other" variant.</p>
     */
    private static String canonicalizeLenient(String tag) {
        try {
            return LanguageTag.canonicalize(tag);
        } catch (InvalidLanguageTagException e) {
            return null;
        }
    }

    /**
     * Builds the WHERE-clause body (inside {@code GRAPH <REQUIREMENTS_GRAPH>}) shared by
     * {@link #findByCode}, {@link #findCurrentByCode} and {@link #findAll}: the mandatory type
     * join (filtered to the two known requirement types, rather than an unfiltered "a ?type": a
     * store-first subject carrying a third {@code rdf:type} triple alongside its real
     * one would otherwise bind an extra, unpredictable row, and {@link #typeFromIri} throws
     * {@link IllegalStateException} for any type that is neither FunctionalRequirement nor
     * NonFunctionalRequirement), the mandatory status join, and the three optional joins
     * (priority, motivatedBy, qualityCategory). {@code status} is deliberately
     * <strong>not</strong> filtered the same way {@code type} is - see {@link #statusFromIri} for
     * why a SHACL-legal but MVP-unsupported status fails loudly ({@link
     * UnsupportedRequirementStatusException}) instead of being filtered into invisibility.
     * {@code identifierClause} supplies
     * the one join that differs per caller - a specific {@link RequirementCode} literal for
     * {@link #findByCode}/{@link #findCurrentByCode} (via {@link #requirementByCodeWhereClause}),
     * an unbound {@code ?identifier} variable for {@link #findAll}. Extracted because all three
     * callers build a {@link Requirement} (or, for {@link #findAll}, a {@link RequirementAssembly})
     * from the same row shape - drift between near-identical read paths in this class was a real
     * bug twice before (the {@link #findAll} row-grouping fix), so this text now
     * lives in one place. The caller supplies the surrounding {@code SELECT}/{@code GRAPH}/
     * {@code WHERE} wrapping and, in {@link #findCurrentByCode}'s case, the additional
     * provenance-graph join - only the WHERE body itself is common.
     *
     * <p><strong>{@code title}/{@code description}/{@code rationale} are read separately, not
     * joined here.</strong> They may each legally carry several language-tagged literals
     * (SKOS-S14-style
     * {@code sh:uniqueLang}), so joining them into this single-row scalar clause would multiply a
     * subject into a row per title/description candidate combination - exactly the row
     * multiplication {@code priority}/{@code qualityCategory} already cause, but for two
     * <em>mandatory</em> fields every caller of this clause currently assumes are single-valued
     * per row. {@link #readTitles}/{@link #readDescriptions}/{@link #readRationales} read them as
     * their own follow-up
     * queries instead, the same way {@link #readAcceptanceCriterionAssemblies} already does for
     * {@code arkreq:acceptanceCriterion}. {@code rationale} joins them for the same reason plus a
     * second one: being optional, an {@code OPTIONAL} join would bind {@code null} for a
     * requirement that carries none, which no caller of this clause distinguishes from a missing
     * language candidate (issue #321).</p>
     *
     * <p>{@code FILTER(isIRI(?s))} lives in this clause and not in {@link #requirementTypeClause}
     * (kogn-io/arknet#401): every read built on top of it casts {@code ?s} to an
     * {@link IRI} to name the requirement's identity, and nothing in
     * {@code requirements-shapes.ttl} constrains a requirement's node kind, so a store-first
     * blank-node subject used to make {@code findByCode}/{@code findAll} throw a
     * {@link ClassCastException} for the whole project instead of skipping the one subject they
     * cannot address. {@link #findAllCodes} joins {@link #requirementTypeClause} alone and stays
     * deliberately unguarded - see its own javadoc.</p>
     */
    private static String requirementWhereClause(String identifierClause) {
        return requirementTypeClause()
                + identifierClause
                + "?s <" + STATUS_PROPERTY + "> ?status . "
                + "FILTER(isIRI(?s)) "
                + "OPTIONAL { ?s <" + PRIORITY_PROPERTY + "> ?priority } "
                + "OPTIONAL { ?s <" + MOTIVATED_BY_PROPERTY + "> ?motivatedBy } "
                + "OPTIONAL { ?s <" + QUALITY_CATEGORY_PROPERTY + "> ?qualityCategory } ";
    }

    /**
     * The mandatory type join alone, shared by {@link #requirementWhereClause} and
     * {@link #findAllCodes}: the latter needs this filter and nothing else of the listing read, and
     * a second hand-written copy of it would be free to drift the day a third requirement type
     * appears - the very drift {@link #requirementWhereClause} was extracted to prevent.
     */
    private static String requirementTypeClause() {
        return "?s a ?type . "
                + "FILTER(?type = <" + FUNCTIONAL_REQUIREMENT_TYPE + "> || ?type = <"
                + NON_FUNCTIONAL_REQUIREMENT_TYPE + ">) ";
    }

    /**
     * {@link #requirementWhereClause} specialised to one {@link RequirementCode}, for
     * {@link #findByCode} and {@link #findCurrentByCode}.
     */
    private static String requirementByCodeWhereClause(RequirementCode code) {
        return requirementWhereClause(
                "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . ");
    }

    /**
     * Builds one {@link Requirement} from a row of {@link #requirementByCodeWhereClause}'s
     * projection ({@code ?s ?type ?status ?priority ?motivatedBy ?qualityCategory}), including
     * the follow-up reads {@link #readTitles}/{@link #readDescriptions}/{@link #readRationales}/
     * {@link #readUsesTerms}
     * (via {@code query}) and the legacy-placeholder substitution ({@link
     * #acceptanceCriteriaOrLegacyPlaceholder}) for {@code acceptanceCriteria}. Shared by
     * {@link #findByCode} and {@link #findCurrentByCode} so both single-requirement read paths
     * build a {@link Requirement} the same way - drift between near-identical read paths in this
     * class was a real bug twice before (the {@link #findAll} row-grouping fix).
     *
     * <p>{@code displayLocale} selects one {@code title}/{@code description}/{@code rationale}
     * candidate out of however many language-tagged variants {@link #readTitles}/
     * {@link #readDescriptions}/{@link #readRationales} find;
     * {@link #findByCode} passes its per-call override, {@link #findCurrentByCode} passes this
     * adapter's own configured {@link #displayLocale} (an internal read-modify-write round trip
     * has no per-call display preference of its own to honour - see that method's javadoc).</p>
     *
     * <p>{@code query} is deliberately typed as the neutral {@link SparqlQuery} port, not
     * {@link DatasetHandle}: {@link #findByCode} passes the live {@link DatasetTx} of a single
     * transaction it opened around both this call and its own main query (see that method's
     * javadoc for why), while {@link #findCurrentByCode} keeps passing
     * {@link DatasetHandle#sparqlQuery()} - its own snapshot guarantee is documented on
     * {@link RequirementRepository#findCurrentByCode} and does not need this seam to change.</p>
     *
     * @throws UnsupportedRequirementStatusException see {@link #statusFromIri}
     */
    private Optional<Requirement> requirementOf(
            ProjectId projectId, BindingSet row, RequirementCode code, SparqlQuery query, DisplayLocale locale) {
        String subjectIriString = iriOf(row, "s").getIRIString();
        String subject = SparqlTerms.iriRef(subjectIriString);
        List<AcceptanceCriterionAssembly> assemblies = readAcceptanceCriterionAssemblies(query::select, subject);
        List<AcceptanceCriterion> criteria =
                acceptanceCriteriaOrLegacyPlaceholder(toAcceptanceCriteria(assemblies, locale));
        return requirementOf(projectId, row, code, query, locale, criteria);
    }

    /**
     * {@link #requirementOf(ProjectId, BindingSet, RequirementCode, SparqlQuery, DisplayLocale)}
     * with the caller already holding the resolved {@code acceptanceCriteria} list - the seam
     * {@link #findCurrentByCode} uses so it can read {@link #readAcceptanceCriterionAssemblies} exactly once
     * and still learn, before building the {@link Requirement}, whether the result it is about to
     * embed is a real store value or the legacy placeholder (see {@link
     * RequirementRepository.CurrentRequirement#acceptanceCriteriaIsSynthesized()}).
     *
     * <p>Returns {@link Optional#empty()} if this subject carries no {@code dcterms:title}/
     * {@code dcterms:description} literal at all - {@code Requirement-title}/
     * {@code Requirement-description} carry {@code sh:minCount 1} at {@code sh:Violation}
     * severity, so this is unreachable via the MCP tools; a store-first requirement
     * missing either is skipped here the same way {@code KognioRdfUseCaseRepository} skips a use
     * case with zero main steps, rather than crashing {@link #findByCode}/{@link #findAll} for the
     * whole project.</p>
     *
     * @throws UnsupportedRequirementStatusException see {@link #statusFromIri}
     */
    private Optional<Requirement> requirementOf(ProjectId projectId, BindingSet row, RequirementCode code,
            SparqlQuery query, DisplayLocale locale, List<AcceptanceCriterion> acceptanceCriteria) {
        String subjectIriString = iriOf(row, "s").getIRIString();
        String subject = SparqlTerms.iriRef(subjectIriString);
        return selectTitleDescription(query::select, subject, locale).map(selection -> new Requirement(
                new RequirementId(ResourceId.of(subjectIriString)),
                code,
                selection.title().value(),
                selection.description().value(),
                selectRationale(query::select, subject, locale).map(LocalizedLiteral::value).orElse(null),
                typeFromIri(iriOf(row, "type").getIRIString()),
                statusFromIri(projectId, code, iriOf(row, "status").getIRIString()),
                priorityOf(row),
                motivatedByOf(row),
                qualityCategoryOf(row),
                readUsesTerms(query::select, subject),
                acceptanceCriteria,
                readConstrainedBy(query::select, subject)));
    }

    /**
     * One requirement's selected {@code dcterms:title}/{@code dcterms:description} literal, each
     * carrying the {@link LocalizedLiteral#languageTag()} it was chosen under - {@link
     * #requirementOf(ProjectId, BindingSet, RequirementCode, SparqlQuery, DisplayLocale, List)}
     * needs only the values, but {@link #findCurrentByCode} also needs the tags, to pass through
     * unchanged into {@link RequirementRepository.CurrentRequirement#titleLanguage()}/
     * {@link RequirementRepository.CurrentRequirement#descriptionLanguage()}.
     */
    private record TitleDescriptionSelection(LocalizedLiteral title, LocalizedLiteral description) {
    }

    /**
     * Selects one {@code dcterms:title}/{@code dcterms:description} candidate each via
     * {@code locale}, or {@link Optional#empty()} if this subject carries no literal for either -
     * see {@link #requirementOf(ProjectId, BindingSet, RequirementCode, SparqlQuery, DisplayLocale,
     * List)}'s javadoc for why that is possible and how it is handled.
     */
    private Optional<TitleDescriptionSelection> selectTitleDescription(
            Function<String, Stream<BindingSet>> selectFn, String subject, DisplayLocale locale) {
        Optional<LocalizedLiteral> title = locale.select(readTitles(selectFn, subject));
        Optional<LocalizedLiteral> description = locale.select(readDescriptions(selectFn, subject));
        if (title.isEmpty() || description.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TitleDescriptionSelection(title.get(), description.get()));
    }

    /** Reads the {@code dcterms:title} candidates of one requirement, tagged for {@link DisplayLocale}. */
    private List<LocalizedLiteral> readTitles(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?o WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + subject + " <" + TITLE_PROPERTY + "> ?o } }";
        return selectFn.apply(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** {@link #readTitles} for {@code dcterms:description}. */
    private List<LocalizedLiteral> readDescriptions(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?o WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + subject + " <" + DESCRIPTION_PROPERTY + "> ?o } }";
        return selectFn.apply(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /**
     * {@link #readTitles} for the optional {@code arkreq:rationale} (issue #321). An empty result
     * is the ordinary case for a requirement whose reason nobody recorded, not the
     * store-first anomaly an empty {@link #readTitles}/{@link #readDescriptions} signals - which
     * is why {@link #selectRationale} maps it to a {@code null} field rather than to a skipped
     * requirement.
     */
    private List<LocalizedLiteral> readRationales(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?o WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + subject + " <" + RATIONALE_PROPERTY + "> ?o } }";
        return selectFn.apply(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /**
     * Selects one {@code arkreq:rationale} candidate via {@code locale}, or {@link Optional#empty()}
     * if this requirement carries none at all. Deliberately <em>not</em> folded into
     * {@link #selectTitleDescription}: an absent rationale is legal and must leave the requirement
     * readable, whereas an absent title/description is the store-first anomaly that method skips
     * the whole requirement for.
     */
    private Optional<LocalizedLiteral> selectRationale(
            Function<String, Stream<BindingSet>> selectFn, String subject, DisplayLocale locale) {
        return locale.select(readRationales(selectFn, subject));
    }

    /** Bulk variant of {@link #readTitles}: every requirement's title candidates in one query. */
    private Map<String, List<LocalizedLiteral>> readTitlesBySubject(SparqlQuery query) {
        return literalsBySubject(query, TITLE_PROPERTY);
    }

    /** Bulk variant of {@link #readDescriptions}: every requirement's description candidates in one query. */
    private Map<String, List<LocalizedLiteral>> readDescriptionsBySubject(SparqlQuery query) {
        return literalsBySubject(query, DESCRIPTION_PROPERTY);
    }

    /** Bulk variant of {@link #readRationales}: every requirement's rationale candidates in one query. */
    private Map<String, List<LocalizedLiteral>> readRationalesBySubject(SparqlQuery query) {
        return literalsBySubject(query, RATIONALE_PROPERTY);
    }

    /**
     * Carries a {@code FILTER(isIRI(?s))} of its own (kogn-io/arknet#401): this read joins the
     * predicate alone, with no type join in front of it, so it binds any subject in the graph that
     * carries the literal - including a blank node {@link #findAll}'s own query would already have
     * dropped, and including the derived acceptance-criterion resources' own texts.
     */
    private Map<String, List<LocalizedLiteral>> literalsBySubject(SparqlQuery query, String predicateIri) {
        String sparql = "SELECT ?s ?o WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "?s <" + predicateIri + "> ?o . FILTER(isIRI(?s)) } }";
        Map<String, List<LocalizedLiteral>> bySubject = new LinkedHashMap<>();
        query.select(sparql).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(localizedLiteralOf(row, "o")));
        return bySubject;
    }

    /**
     * Runs a read-only {@code work} function inside one transaction (see {@link #findByCode}'s and
     * {@link #findAll}'s javadoc for why a shared transaction, not merely a shared connection, is
     * required), retrying up to {@link #MAX_READ_RETRY_ATTEMPTS} times if the store rejects the
     * transaction's own commit as a lost {@code SERIALIZABLE} race against a concurrent write
     * (issue #171). {@code SERIALIZABLE} isolation (see {@code DatasetTransactorRdf4j}'s javadoc)
     * tracks every statement pattern a transaction observed, including a pure reader's - a
     * read-only transaction can therefore lose exactly the race a write can, even though it never
     * writes anything itself. Unlike a write's retry (which must recompute its candidate against
     * fresh state before trying again), a lost read-only transaction has nothing to recompute: it
     * only ever observed a pattern a concurrent writer changed and committed before this
     * transaction's own commit, so re-running the identical read against the store's now-current
     * state is always the correct response, not merely a convenient one.
     *
     * <p><strong>Exhausted retries never leak the raw store exception.</strong> The shared {@link
     * de.hauschel.arknet.persistence.WriteFunnel} translates every write path's lost
     * {@code ConcurrencyConflictException} into a bounded-context-owned signal before it reaches a
     * caller - this read path is the adapter's own, funnel-external retry loop, but the same
     * convention applies: a pathological, sustained storm of concurrent writers that outlasts
     * {@link #MAX_READ_RETRY_ATTEMPTS} surfaces as {@link RequirementReadConflictException}, never
     * as the raw {@code io.kogn.rdf} type, with the last observed conflict preserved as {@linkplain
     * Throwable#getCause() cause}.
     */
    private <T> T readInTransaction(ProjectId projectId, DatasetHandle handle, Function<DatasetTx, T> work) {
        ConcurrencyConflictException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_READ_RETRY_ATTEMPTS; attempt++) {
            try {
                return handle.transactor().inTransaction(work);
            } catch (ConcurrencyConflictException e) {
                lastConflict = e;
            }
        }
        throw new RequirementReadConflictException(projectId, MAX_READ_RETRY_ATTEMPTS, lastConflict);
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>One-transaction snapshot (issue #171).</strong> The main row, {@link
     * #readUsesTerms} and {@link #readAcceptanceCriterionAssemblies} all run against the same live
     * {@link DatasetTx}, inside one {@link #readInTransaction} call - unlike
     * {@link #findCurrentByCode}, this method has no concurrency token a caller compares before
     * acting on the result, so a concurrent funnel write landing between two of these three reads
     * would otherwise be silently invisible to the caller and produce a {@link Requirement}
     * combining field values that never coexisted in the store at any single point in time (a torn
     * read). Running all three against one transaction gives them the same consistent snapshot the
     * main query alone already had.</p>
     */
    @Override
    public Optional<Requirement> findByCode(ProjectId projectId, RequirementCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        DisplayLocale effective = withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?type ?status ?priority ?motivatedBy ?qualityCategory "
                + "WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + requirementByCodeWhereClause(code)
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return readInTransaction(projectId, handle, tx -> {
                Optional<BindingSet> head = tx.select(query).findFirst();
                if (head.isEmpty()) {
                    return Optional.empty();
                }
                return requirementOf(projectId, head.get(), code, tx, effective);
            });
        }
    }

    /**
     * Overrides this repository's own configured {@link #displayLocale}'s {@code requested} tier
     * for one call - shared by {@link #findByCode} and {@link #findAll}, e.g. an explicit
     * {@code req_get} {@code displayLocale} argument or a project's own default language merged in
     * by the caller ({@code req_list} has no explicit {@code displayLocale} tool argument of its
     * own to merge against - before {@link #findAll} sees it, issue #281). Mirrors {@code
     * KognioRdfTermRepository#withRequestedOverride}.
     *
     * @param requestedOverride a BCP-47 language tag, or {@code null}/blank to use the configured
     *                          {@link #displayLocale} unchanged
     */
    private DisplayLocale withRequestedOverride(String requestedOverride) {
        if (requestedOverride == null || requestedOverride.isBlank()) {
            return displayLocale;
        }
        return new DisplayLocale(Locale.forLanguageTag(requestedOverride), displayLocale.systemDefault());
    }

    /**
     * Reads a requirement's current state together with its concurrency token. The row built from
     * {@link #requirementByCodeWhereClause} (the core fields) plus the head itself come from this
     * method's one query call - one snapshot, which is the load-bearing guarantee,
     * not an ordering of clauses within that query. {@link #requirementOf} then issues two
     * further, independent queries, via {@link #readUsesTerms} and {@link #readAcceptanceCriterionAssemblies},
     * to fill in {@code usesTerms} and {@code acceptanceCriteria}; those later reads are safe
     * precisely because they can only be fresher, never staler, than the head: a concurrent funnel
     * write landing in between moves the head, so {@link RequirementRepository#compareAndUpdate}
     * then fails its comparison and the caller re-reads instead of silently overwriting a state it
     * never actually saw. Builds the {@link Requirement} the same way {@link #findByCode} does -
     * both call {@link #requirementOf} on their row, so the two read paths cannot drift apart
     * field-by-field the way two near-identical read paths in this class already did twice before
     * - plus one {@code OPTIONAL} join into
     * {@link ArkprovVocabulary#PROVENANCE_GRAPH} for the head, and the {@code
     * acceptanceCriteriaIsSynthesized} flag {@link #findByCode} has no need for: this method reads
     * {@link #readAcceptanceCriterionAssemblies} itself (rather than delegating to {@link
     * #requirementOf(ProjectId, BindingSet, RequirementCode, DatasetHandle)}) so it can tell, before the
     * {@link Requirement} is built, whether {@link #hasConsecutiveAcceptanceCriterionPositions} left anything at
     * all - i.e. whether the subject carries a real {@code arkreq:acceptanceCriterion} triple or
     * none, the fact {@link RequirementRepository.CurrentRequirement#acceptanceCriteriaIsSynthesized()}
     * exists to carry back to the caller (see {@link
     * de.hauschel.arknet.req.application.RequirementService#updateWithOptimisticRetry}, which
     * rejects a write that would otherwise turn this read-time placeholder into a real, persisted
     * literal).
     */
    @Override
    public Optional<RequirementRepository.CurrentRequirement> findCurrentByCode(
            ProjectId projectId, RequirementCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?type ?status ?priority ?motivatedBy ?qualityCategory ?head "
                + "WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + requirementByCodeWhereClause(code)
                + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?s <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Optional<BindingSet> found = handle.sparqlQuery().select(query).findFirst();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            BindingSet row = found.get();
            String subjectIriString = iriOf(row, "s").getIRIString();
            String subject = SparqlTerms.iriRef(subjectIriString);
            // No per-call display-language override here: an internal read-modify-write round
            // trip is not a caller-facing read, so this adapter's own configured displayLocale
            // (never withRequestedOverride's per-call one) is used, exactly like
            // KognioRdfTermRepository#attemptUpdate uses its own displayLocale field for
            // resultingTerm rather than any override.
            Optional<TitleDescriptionSelection> titleAndDescription =
                    selectTitleDescription(handle.sparqlQuery()::select, subject, displayLocale);
            if (titleAndDescription.isEmpty()) {
                return Optional.empty();
            }
            List<AcceptanceCriterionAssembly> criterionAssemblies =
                    readAcceptanceCriterionAssemblies(handle.sparqlQuery()::select, subject);
            List<AcceptanceCriterion> rawCriteria = toAcceptanceCriteria(criterionAssemblies, displayLocale);
            boolean acceptanceCriteriaIsSynthesized =
                    rawCriteria.isEmpty() || !hasConsecutiveAcceptanceCriterionPositions(rawCriteria);
            List<AcceptanceCriterion> acceptanceCriteria = acceptanceCriteriaIsSynthesized
                    ? LEGACY_ACCEPTANCE_CRITERION_PLACEHOLDER
                    : rawCriteria;
            Map<Integer, String> acceptanceCriteriaLanguageByPosition = acceptanceCriteriaIsSynthesized
                    ? Map.of()
                    : toAcceptanceCriteriaLanguages(criterionAssemblies, displayLocale);
            TitleDescriptionSelection selection = titleAndDescription.get();
            // Optional, so unlike title/description an empty selection is a null field rather than
            // a skipped requirement - and its tag is null both for an untagged literal and for no
            // literal at all, which the service tells apart by the value itself (issue #321).
            Optional<LocalizedLiteral> rationale =
                    selectRationale(handle.sparqlQuery()::select, subject, displayLocale);
            Requirement requirement = new Requirement(
                    new RequirementId(ResourceId.of(subjectIriString)),
                    code,
                    selection.title().value(),
                    selection.description().value(),
                    rationale.map(LocalizedLiteral::value).orElse(null),
                    typeFromIri(iriOf(row, "type").getIRIString()),
                    statusFromIri(projectId, code, iriOf(row, "status").getIRIString()),
                    priorityOf(row),
                    motivatedByOf(row),
                    qualityCategoryOf(row),
                    readUsesTerms(handle.sparqlQuery()::select, subject),
                    acceptanceCriteria,
                    readConstrainedBy(handle.sparqlQuery()::select, subject));
            RevisionToken head = row.getValue("head")
                    .filter(IRI.class::isInstance)
                    .map(value -> new RevisionToken(((IRI) value).getIRIString()))
                    .orElse(null);
            return Optional.of(new RequirementRepository.CurrentRequirement(
                    requirement, head, acceptanceCriteriaIsSynthesized,
                    selection.title().languageTag(), selection.description().languageTag(),
                    rationale.map(LocalizedLiteral::languageTag).orElse(null),
                    acceptanceCriteriaLanguageByPosition));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>One-transaction snapshot (issue #171).</strong> The main query, {@link
     * #readUsesTermsBySubject} and {@link #readAcceptanceCriteriaBySubject} all run against the
     * same live {@link DatasetTx}, inside one {@link #readInTransaction} call - see
     * {@link #findByCode}'s javadoc for why a read-only path with no concurrency token needs this.
     * Without it the exposure would be wider here than on {@link #findByCode}: every requirement
     * in the project shares the same narrow window between the three reads, so one funnel write
     * landing inside it could produce a torn combination for any of them, not just the one
     * requirement a caller happened to ask for.</p>
     */
    @Override
    public List<Requirement> findAll(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?identifier ?type ?status ?priority ?motivatedBy "
                + "?qualityCategory WHERE { GRAPH <"
                + REQUIREMENTS_GRAPH + "> { "
                + requirementWhereClause("?s <" + IDENTIFIER_PROPERTY + "> ?identifier . ")
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return readInTransaction(projectId, handle, tx -> {
                Map<String, List<TermRef>> termsBySubject = readUsesTermsBySubject(tx);
                Map<String, List<ConstraintRef>> constraintsBySubject = readConstrainedByBySubject(tx);
                Map<String, List<AcceptanceCriterionAssembly>> criteriaAssembliesBySubject =
                        readAcceptanceCriterionAssembliesBySubject(tx);
                Map<String, List<LocalizedLiteral>> titlesBySubject = readTitlesBySubject(tx);
                Map<String, List<LocalizedLiteral>> descriptionsBySubject = readDescriptionsBySubject(tx);
                Map<String, List<LocalizedLiteral>> rationalesBySubject = readRationalesBySubject(tx);
                // Grouped by subject (see the class-level note above): priority/
                // qualityCategory are OPTIONAL joins without an enforced sh:maxCount, so a
                // store-first requirement with two triples on either predicate binds a
                // cross-product of rows for the same subject. Mapping each row straight to a
                // Requirement (the earlier code) would have surfaced that subject twice in the
                // result list instead of once.
                Map<String, RequirementAssembly> bySubject = new LinkedHashMap<>();
                tx.select(query).forEach(row -> {
                    RequirementAssembly assembly = assemblyFor(projectId, bySubject, row);
                    assembly.addPriorityCandidate(priorityOf(row));
                    assembly.addQualityCategoryCandidate(qualityCategoryOf(row));
                });
                return bySubject.entrySet().stream()
                        .map(entry -> {
                            Optional<LocalizedLiteral> title =
                                    effective.select(titlesBySubject.getOrDefault(entry.getKey(), List.of()));
                            Optional<LocalizedLiteral> description = effective.select(
                                    descriptionsBySubject.getOrDefault(entry.getKey(), List.of()));
                            if (title.isEmpty() || description.isEmpty()) {
                                // Requirement-title/Requirement-description carry sh:minCount 1
                                // at sh:Violation severity, so this is unreachable via the MCP
                                // tools - skip this one store-first requirement rather
                                // than crash the whole listing, mirroring
                                // KognioRdfUseCaseRepository's zero-main-step skip.
                                return null;
                            }
                            // Absent rationale is ordinary, not the store-first anomaly the
                            // title/description skip above guards against (issue #321).
                            String rationale = effective
                                    .select(rationalesBySubject.getOrDefault(entry.getKey(), List.of()))
                                    .map(LocalizedLiteral::value)
                                    .orElse(null);
                            return entry.getValue().toRequirement(title.get().value(), description.get().value(),
                                    rationale,
                                    termsBySubject.getOrDefault(entry.getKey(), List.of()),
                                    acceptanceCriteriaOrLegacyPlaceholder(toAcceptanceCriteria(
                                            criteriaAssembliesBySubject.getOrDefault(entry.getKey(), List.of()),
                                            effective)),
                                    constraintsBySubject.getOrDefault(entry.getKey(), List.of()));
                        })
                        .filter(Objects::nonNull)
                        .toList();
            });
        }
    }

    /**
     * Reads the codes off {@code dcterms:identifier} alone, next to the same type filter
     * {@link #requirementWhereClause} applies - and nothing else. No status join, no
     * {@link #readTitlesBySubject}/{@link #readDescriptionsBySubject} lookup, hence none of the
     * conditions under which {@link #findAll} returns a subject fewer than it read: exactly what
     * {@link RequirementRepository#findAllCodes} promises, and the reason a code counted here
     * cannot be minted twice (kogn-io/arknet#360).
     *
     * <p>The type join is kept, and shared with the listing through
     * {@link #requirementTypeClause}, so both reads agree on what counts as a requirement. This
     * graph is not requirements-only - the derived acceptance-criterion resources live in it too -
     * so an unfiltered read would be a standing invitation for some later coded sub-resource to
     * start feeding foreign numbers into the {@code FR}/{@code NFR} counters.</p>
     *
     * <p>Distinct, because {@code ashapes:Requirement-identifier} enforces {@code sh:maxCount 1}
     * on the way in only: a subject that acquired a second identifier triple store-first would
     * otherwise appear twice. Duplicates would not change the maximum, but they would make the
     * list say something untrue about the project.</p>
     *
     * <p><strong>No {@code FILTER(isIRI(?s))}, unlike the read paths around it
     * (kogn-io/arknet#360).</strong> {@code WriteFunnel#create}'s uniqueness check is
     * {@code tx.contains(graph, null, dcterms:identifier, code)} - a wildcard subject, so it sees a
     * blank-node subject holding a code just as well as an IRI one and rejects the write either way.
     * A counter that filtered blank nodes out would therefore be blind to a code the write path
     * still refuses, which is this bug over again through a different skip. Counting one number too
     * many costs a number; counting one too few costs the {@code add}.</p>
     */
    @Override
    public List<RequirementCode> findAllCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + requirementTypeClause()
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .distinct()
                    .map(RequirementCode::new)
                    .toList();
        }
    }

    /**
     * Groups the (potentially several) rows of one requirement - an {@code OPTIONAL} join on
     * {@code priority}/{@code qualityCategory} without an enforced {@code sh:maxCount} multiplies a
     * requirement into a row per candidate value combination - into a single
     * {@link RequirementAssembly}, keyed by subject IRI. The single-valued fields (identity, code,
     * type, status, motivatedBy - all either {@code sh:maxCount 1} at {@code sh:Violation}
     * severity or otherwise guaranteed single-triple by the domain) are read once from the first
     * row of a subject; every row contributes its {@code priority}/{@code qualityCategory} binding
     * (if present) as a candidate via {@link RequirementAssembly#addPriorityCandidate}/
     * {@link RequirementAssembly#addQualityCategoryCandidate}, called by {@link #findAll} once per
     * row. {@code title}/{@code description}/{@code rationale} are not part of this assembly at
     * all - {@link #findAll} selects them separately, once per subject, from
     * {@link #readTitlesBySubject}/
     * {@link #readDescriptionsBySubject}/{@link #readRationalesBySubject}'s candidate maps.
     *
     * @throws UnsupportedRequirementStatusException see {@link #statusFromIri}
     */
    private static RequirementAssembly assemblyFor(
            ProjectId projectId, Map<String, RequirementAssembly> bySubject, BindingSet row) {
        String subjectIri = iriOf(row, "s").getIRIString();
        RequirementCode code = new RequirementCode(literalOf(row, "identifier").getLexicalForm());
        return bySubject.computeIfAbsent(subjectIri, iri -> new RequirementAssembly(
                new RequirementId(ResourceId.of(iri)),
                code,
                typeFromIri(iriOf(row, "type").getIRIString()),
                statusFromIri(projectId, code, iriOf(row, "status").getIRIString()),
                motivatedByOf(row)));
    }

    /**
     * Mutable per-subject accumulator collecting a requirement's {@code priority} and
     * {@code qualityCategory} candidates across rows, then choosing one of each
     * deterministically (first-seen) when the requirement is finally materialised, logging a
     * {@code WARN} if more than one distinct value was collected for a field.
     */
    private static final class RequirementAssembly {

        private final RequirementId id;
        private final RequirementCode code;
        private final RequirementType type;
        private final RequirementStatus status;
        private final String motivatedBy;
        private final List<Priority> priorities = new ArrayList<>();
        private final List<String> qualityCategories = new ArrayList<>();

        private RequirementAssembly(RequirementId id, RequirementCode code,
                RequirementType type, RequirementStatus status, String motivatedBy) {
            this.id = id;
            this.code = code;
            this.type = type;
            this.status = status;
            this.motivatedBy = motivatedBy;
        }

        private void addPriorityCandidate(Priority priority) {
            if (priority != null) {
                priorities.add(priority);
            }
        }

        private void addQualityCategoryCandidate(String qualityCategory) {
            if (qualityCategory != null) {
                qualityCategories.add(qualityCategory);
            }
        }

        private Requirement toRequirement(String title, String description, String rationale,
                List<TermRef> usesTerms, List<AcceptanceCriterion> acceptanceCriteria,
                List<ConstraintRef> constrainedBy) {
            return new Requirement(id, code, title, description, rationale, type, status,
                    firstDistinct(priorities, "priority"), motivatedBy,
                    firstDistinct(qualityCategories, "qualityCategory"), usesTerms, acceptanceCriteria,
                    constrainedBy);
        }

        /**
         * Returns the first-seen candidate for {@code fieldName} (stable across repeated calls,
         * since {@link LinkedHashMap}/row order preserves insertion order), or {@code null} if the
         * {@code OPTIONAL} join never bound - logging a single {@code WARN} when more than one
         * distinct value was collected - the "stille Luege" this makes visible instead of
         * silently overwriting/duplicating.
         */
        private <T> T firstDistinct(List<T> candidates, String fieldName) {
            if (candidates.isEmpty()) {
                return null;
            }
            long distinctCount = candidates.stream().distinct().count();
            if (distinctCount > 1) {
                LOG.warn("Requirement {}: field '{}' had {} distinct values, returning the first",
                        id.value().value(), fieldName, distinctCount);
            }
            return candidates.get(0);
        }
    }

    /**
     * Batch lookup of requirements by opaque identity, keyed for {@link ResolveRequirements}:
     * resolves every id in {@code ids} present in the project to a
     * {@link ResolveRequirements.ResolvedRequirement}, in one {@code VALUES}-bound query - an id
     * absent from the project is simply absent from the result, never an error. Returns the slim
     * {@link ResolveRequirements.ResolvedRequirement} projection ({@code identifier} only), not
     * the full {@link Requirement} aggregate. At most one result per subject; if a subject's
     * identifier constraint is violated (store-first, no enforced {@code sh:maxCount}), which of
     * its candidate identifiers wins is deliberately unspecified.
     *
     * <p><strong>Why.</strong> One query for the whole batch, not one per id, because the caller
     * (a sibling bounded context's driving adapter, rendering several requirement references at
     * once) must not pay an N+1 store round-trip. Unlike the sibling ubiquitous-language adapter's
     * {@code KognioRdfTermRepository#findByIds}, this join carries no type filter: that adapter
     * joins {@code ?s a <skos:Concept>} because every terms-graph subject carries that one type,
     * but requirements are typed either {@code arkreq:FunctionalRequirement} or
     * {@code arkreq:NonFunctionalRequirement}, and a filter here would either need both
     * alternatives (no benefit - {@code dcterms:identifier} already scopes the join to subjects
     * that carry a code) or arbitrarily exclude one requirement type.</p>
     */
    @Override
    public List<ResolveRequirements.ResolvedRequirement> findByIds(ProjectId projectId, List<ResourceId> ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }

        // ResourceId#of validates IRIREF-safety at construction, so every id here is
        // already guaranteed safe to embed - restores ResolveRequirements#resolveExisting's "never
        // rejects" contract, which this used to violate by throwing on an impossible identity.
        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value()))
                .collect(Collectors.joining(" "));

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<String, ResolveRequirements.ResolvedRequirement> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                String subjectIri = iriOf(row, "s").getIRIString();
                // putIfAbsent, not put: the first row wins if a subject has several identifiers.
                bySubject.putIfAbsent(subjectIri, new ResolveRequirements.ResolvedRequirement(
                        ResourceId.of(subjectIri),
                        new RequirementCode(literalOf(row, "identifier").getLexicalForm())));
            });
            return List.copyOf(bySubject.values());
        }
    }

    // ---- usesTerm reading --------------------------------------------------------------

    /**
     * Reads the {@code arkreq:usesTerm} edges of one requirement back as term references, ordered
     * by target IRI (RDF has no intrinsic statement order, and {@link Requirement} compares its
     * {@code usesTerms} list positionally). Excludes any edge whose target is not an IRI -
     * {@code arkreq:usesTerm} carries no {@code sh:nodeKind} constraint, so a store-first
     * edge may legally target a blank node, which
     * {@link de.hauschel.arknet.kernel.ResourceId} cannot represent; such an edge never appears in
     * {@link Requirement#usesTerms()}. Every edge written through {@code req_link_term} targets a
     * resolved subject IRI by construction, so this exclusion cannot bite via the MCP tools; a
     * store-first blank-node edge instead survives a later update via
     * {@link #replaceTriplesForUpdate}, which re-attaches it after rewriting the subject's triples.
     *
     * <p><strong>History.</strong> The edge's target IRI <em>is</em> the
     * {@link TermRef} now - {@link TermRef#value()} wraps it directly - so this reads only the
     * requirements graph; the sibling terms graph is never consulted here. Previously, this read
     * joined the terms graph by {@code dcterms:identifier}, so a target carrying an identifier but
     * not the {@code skos:Concept} type still bound a row here, yet the resolution query demanded
     * that type and rejected, on the next {@link #compareAndUpdate}, the very {@link TermRef} this
     * read had produced - an unwritable requirement that the preservation mechanism could not
     * preserve because the read did bind it. Carrying identity removes that mismatch at its
     * root: there is no resolution on the read path at all anymore.</p>
     */
    private List<TermRef> readUsesTerms(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?term WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + USES_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?term)) } ORDER BY ?term";
        return selectFn.apply(query)
                .map(row -> new TermRef(ResourceId.of(iriOf(row, "term").getIRIString())))
                .toList();
    }

    /** Bulk variant of {@link #readUsesTerms}: all requirements' term references in one query. */
    private Map<String, List<TermRef>> readUsesTermsBySubject(SparqlQuery query) {
        String sparql = "SELECT ?s ?term WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { ?s <" + USES_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?s) && isIRI(?term)) } ORDER BY ?s ?term";
        Map<String, List<TermRef>> bySubject = new LinkedHashMap<>();
        query.select(sparql).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(new TermRef(ResourceId.of(iriOf(row, "term").getIRIString()))));
        return bySubject;
    }

    // ---- constrainedBy reading ----------------------------------------------------------

    /**
     * Reads the {@code oslc_rm:constrainedBy} edges of one requirement back as constraint
     * references, ordered by target IRI - mirrors {@link #readUsesTerms} exactly, including the
     * IRI-only filter: {@code constrainedBy}'s shape carries {@code sh:nodeKind sh:IRI}, but that
     * only guards this adapter's own writes, not a store-first edge, so the filter still
     * matters here.
     */
    private List<ConstraintRef> readConstrainedBy(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?constraint WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + CONSTRAINED_BY_PROPERTY
                + "> ?constraint } FILTER(isIRI(?constraint)) } ORDER BY ?constraint";
        return selectFn.apply(query)
                .map(row -> new ConstraintRef(ResourceId.of(iriOf(row, "constraint").getIRIString())))
                .toList();
    }

    /** Bulk variant of {@link #readConstrainedBy}: all requirements' constraint references in one query. */
    private Map<String, List<ConstraintRef>> readConstrainedByBySubject(SparqlQuery query) {
        String sparql = "SELECT ?s ?constraint WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { ?s <" + CONSTRAINED_BY_PROPERTY + "> ?constraint } "
                + "FILTER(isIRI(?s) && isIRI(?constraint)) } ORDER BY ?s ?constraint";
        Map<String, List<ConstraintRef>> bySubject = new LinkedHashMap<>();
        query.select(sparql).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(new ConstraintRef(ResourceId.of(iriOf(row, "constraint").getIRIString()))));
        return bySubject;
    }

    // ---- acceptanceCriterion reading ---------------------------------------------------

    /**
     * One acceptance criterion's position and every {@code arkreq:criterionText} candidate
     * collected across rows (tagged for {@link DisplayLocale}) - the per-criterion accumulator
     * {@link #readAcceptanceCriterionAssemblies} builds, since {@code arkreq:criterionText} may
     * legally carry several language-tagged literals (SKOS-S14-style {@code sh:uniqueLang}),
     * multiplying a criterion into one row per candidate. Mirrors
     * {@code KognioRdfUseCaseRepository}'s {@code StepAssembly} (issue #266).
     */
    private record AcceptanceCriterionAssembly(int position, List<LocalizedLiteral> textCandidates) {
    }

    /**
     * Reads every acceptance criterion's position and {@code criterionText} candidates, grouped by
     * criterion IRI then sorted by position - the position, not the criterion's own (opaque,
     * re-minted-on-every-write) IRI, is what a caller ({@link #toAcceptanceCriteria}/
     * {@link #toAcceptanceCriteriaLanguages}) actually keys on. Mirrors
     * {@code KognioRdfUseCaseRepository#readMainStepAssemblies}.
     *
     * <p>{@code FILTER(isIRI(?criterion))} mirrors {@link #readUsesTerms}: {@code
     * arkreq:acceptanceCriterion} carries no {@code sh:nodeKind} constraint, so a store-first
     * edge may legally target a blank node - excluded here rather than crashing on the
     * {@link IRI} cast, unreachable via the MCP tools since {@link #mintCriterionIri} always mints
     * a proper IRI.</p>
     */
    private List<AcceptanceCriterionAssembly> readAcceptanceCriterionAssemblies(
            Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?criterion ?position ?text WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + subject + " <" + ACCEPTANCE_CRITERION_PROPERTY + "> ?criterion . "
                + "?criterion <" + POSITION_PROPERTY + "> ?position ; <" + CRITERION_TEXT_PROPERTY + "> ?text } "
                + "FILTER(isIRI(?criterion)) }";
        Map<String, Integer> positionByCriterion = new LinkedHashMap<>();
        Map<String, List<LocalizedLiteral>> textsByCriterion = new LinkedHashMap<>();
        selectFn.apply(query).forEach(row -> {
            String criterionIri = iriOf(row, "criterion").getIRIString();
            positionByCriterion.putIfAbsent(
                    criterionIri, Integer.parseInt(literalOf(row, "position").getLexicalForm()));
            textsByCriterion.computeIfAbsent(criterionIri, key -> new ArrayList<>())
                    .add(localizedLiteralOf(row, "text"));
        });
        return positionByCriterion.entrySet().stream()
                .map(entry -> new AcceptanceCriterionAssembly(entry.getValue(), textsByCriterion.get(entry.getKey())))
                .sorted(Comparator.comparingInt(AcceptanceCriterionAssembly::position))
                .toList();
    }

    /** Bulk variant of {@link #readAcceptanceCriterionAssemblies}: every requirement's criteria in one query. */
    private Map<String, List<AcceptanceCriterionAssembly>> readAcceptanceCriterionAssembliesBySubject(
            SparqlQuery query) {
        String sparql = "SELECT ?s ?criterion ?position ?text WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "?s <" + ACCEPTANCE_CRITERION_PROPERTY + "> ?criterion . "
                + "?criterion <" + POSITION_PROPERTY + "> ?position ; <" + CRITERION_TEXT_PROPERTY + "> ?text } "
                + "FILTER(isIRI(?s) && isIRI(?criterion)) } ORDER BY ?s ?position";
        Map<String, Map<String, Integer>> positionByCriterionBySubject = new LinkedHashMap<>();
        Map<String, Map<String, List<LocalizedLiteral>>> textsByCriterionBySubject = new LinkedHashMap<>();
        query.select(sparql).forEach(row -> {
            String subjectIri = iriOf(row, "s").getIRIString();
            String criterionIri = iriOf(row, "criterion").getIRIString();
            positionByCriterionBySubject.computeIfAbsent(subjectIri, key -> new LinkedHashMap<>())
                    .putIfAbsent(criterionIri, Integer.parseInt(literalOf(row, "position").getLexicalForm()));
            textsByCriterionBySubject.computeIfAbsent(subjectIri, key -> new LinkedHashMap<>())
                    .computeIfAbsent(criterionIri, key -> new ArrayList<>())
                    .add(localizedLiteralOf(row, "text"));
        });
        Map<String, List<AcceptanceCriterionAssembly>> bySubject = new LinkedHashMap<>();
        positionByCriterionBySubject.forEach((subjectIri, positionByCriterion) -> {
            Map<String, List<LocalizedLiteral>> textsByCriterion = textsByCriterionBySubject.get(subjectIri);
            bySubject.put(subjectIri, positionByCriterion.entrySet().stream()
                    .map(entry -> new AcceptanceCriterionAssembly(entry.getValue(), textsByCriterion.get(entry.getKey())))
                    .sorted(Comparator.comparingInt(AcceptanceCriterionAssembly::position))
                    .toList());
        });
        return bySubject;
    }

    /**
     * Selects one {@code criterionText} candidate per criterion via {@code locale}, building the
     * ordered acceptance-criteria list - mirrors {@code KognioRdfUseCaseRepository#toSteps}. A
     * candidate that is empty (no language variant matched, unreachable for a criterion whose
     * {@code criterionText} carries {@code sh:minCount 1}) or resolves to a blank string (a
     * store-first, malformed literal the SHACL gate only guards at write time) is skipped
     * rather than handed to {@link AcceptanceCriterion}'s blank-rejecting constructor - the
     * resulting gap in the position sequence is caught by
     * {@link #hasConsecutiveAcceptanceCriterionPositions} and folds into the same legacy-placeholder
     * substitution as a requirement with no criteria at all (see
     * {@link #acceptanceCriteriaOrLegacyPlaceholder}), rather than crashing {@link #findByCode}/
     * {@link #findAll} for the whole project.
     */
    private static List<AcceptanceCriterion> toAcceptanceCriteria(
            List<AcceptanceCriterionAssembly> assemblies, DisplayLocale locale) {
        List<AcceptanceCriterion> result = new ArrayList<>();
        for (AcceptanceCriterionAssembly assembly : assemblies) {
            locale.select(assembly.textCandidates())
                    .map(LocalizedLiteral::value)
                    .filter(text -> !text.isBlank())
                    .ifPresent(text -> result.add(new AcceptanceCriterion(assembly.position(), text)));
        }
        return result;
    }

    /**
     * The BCP-47 language tag each criterion's currently-selected {@code criterionText} candidate
     * carries, keyed by position - backs
     * {@link RequirementRepository.CurrentRequirement#acceptanceCriteriaLanguageByPosition()}.
     * Mirrors {@code KognioRdfUseCaseRepository#toStepLanguages}.
     */
    private static Map<Integer, String> toAcceptanceCriteriaLanguages(
            List<AcceptanceCriterionAssembly> assemblies, DisplayLocale locale) {
        Map<Integer, String> languageByPosition = new LinkedHashMap<>();
        assemblies.forEach(assembly -> locale.select(assembly.textCandidates())
                .ifPresent(selected -> languageByPosition.put(assembly.position(), selected.languageTag())));
        return languageByPosition;
    }

    /**
     * Mirrors {@code UseCase#requireConsecutiveStepPositions} as a non-throwing predicate: the
     * criterion at list index {@code i} must carry position {@code i + 1}. A store-first
     * gap or duplicate position - nothing in SHACL forbids two {@code arkreq:AcceptanceCriterion}
     * nodes under the same requirement sharing a position - is detected here before it ever reaches
     * {@link Requirement}'s constructor.
     */
    private static boolean hasConsecutiveAcceptanceCriterionPositions(List<AcceptanceCriterion> criteria) {
        for (int i = 0; i < criteria.size(); i++) {
            if (criteria.get(i).position() != i + 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Substitutes {@link #LEGACY_ACCEPTANCE_CRITERION_PLACEHOLDER} for a read result that is empty
     * or, once read back, does not carry gap-free, duplicate-free, ascending positions (a
     * store-first, malformed acceptance-criteria set) - see
     * {@link #hasConsecutiveAcceptanceCriterionPositions} and the placeholder constant's javadoc for
     * why neither must ever reach {@link Requirement}'s constructor.
     */
    private static List<AcceptanceCriterion> acceptanceCriteriaOrLegacyPlaceholder(List<AcceptanceCriterion> criteria) {
        return !criteria.isEmpty() && hasConsecutiveAcceptanceCriterionPositions(criteria)
                ? criteria
                : LEGACY_ACCEPTANCE_CRITERION_PLACEHOLDER;
    }

    /**
     * Converts an already-resolved {@link TermRef} to an {@link IRI} for writing.
     * {@link de.hauschel.arknet.kernel.ResourceId#of(String)} validates IRIREF-safety at
     * construction, so the wrapped IRI is already guaranteed safe here.
     */
    private IRI termIriFor(TermRef term) {
        return rdf.createIRI(term.value().value());
    }

    /** {@link #termIriFor}, for an already-resolved {@link ConstraintRef}. */
    private IRI constraintIriFor(ConstraintRef constraint) {
        return rdf.createIRI(constraint.value().value());
    }

    /**
     * Adds every constraint referenced by {@code constraintIris} to {@code assertedContext},
     * copying its triples wholesale straight out of {@link KognioRdfConstraintRepository}'s own
     * named graph ({@code CONSTRAINTS_GRAPH}).
     *
     * <p><strong>Different from {@code usesTerm}'s bare type assertion.</strong> A term's shape
     * lives in a different {@code .ttl} file the requirements gate never loads, so asserting only
     * {@code skos:Concept} is enough to satisfy {@code Requirement-usesTerm}'s {@code sh:class}.
     * {@code Constraint}'s own shape ({@code rshapes:ConstraintShape}, requiring
     * {@code arkreq:constraintStatement}), by contrast, lives in this <em>same</em> shapes file -
     * asserting only the abstract {@code arkreq:Constraint} type would make that constraint node a
     * target of its own shape and fail it (no statement in the merged validation graph). Copying
     * the constraint's real triples avoids that: {@code ConstraintShape} then validates against
     * data that is actually there, the same data already proven to conform when
     * {@code ConstraintService#add} created it.</p>
     *
     * <p><strong>Consequence: a constrainedBy edge is re-verified, not merely trusted.</strong>
     * Unlike a {@code usesTerm} edge to a term that no longer exists (which this adapter still
     * persists, see {@link #termIriFor}'s callers), a {@code constrainedBy} edge to a constraint
     * that carries no triples in {@code CONSTRAINTS_GRAPH} contributes nothing here and therefore
     * fails {@code sh:class} at write time. This is unreachable via the MCP tools -
     * {@code req_link_constraint} always resolves an existing, immutable {@link Constraint} via
     * {@code ConstraintRepository#findByCode} first - and only reachable via a store-first
     * edge to a dangling identity, which is rejected rather than silently persisted.</p>
     */
    private void constraintAssertedContext(ProjectId projectId, List<IRI> constraintIris, Graph assertedContext) {
        if (constraintIris.isEmpty()) {
            return;
        }
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            for (IRI constraintIri : constraintIris) {
                String query = "SELECT ?p ?o WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                        + SparqlTerms.iriRef(constraintIri.getIRIString()) + " ?p ?o } }";
                handle.sparqlQuery().select(query).forEach(row -> assertedContext.add(
                        constraintIri,
                        (IRI) row.getValue("p").orElseThrow(),
                        row.getValue("o").orElseThrow()));
            }
        }
    }

    /** Builds a language-tagged literal, or a plain untagged one when {@code tag} is {@code null}. */
    private Literal literalOf(String value, String tag) {
        return tag == null ? rdf.createLiteral(value) : rdf.createLiteral(value, tag);
    }

    // ---- helpers -----------------------------------------------------------------------

    private static String typeIriFor(RequirementType type) {
        return switch (type) {
            case FUNCTIONAL -> FUNCTIONAL_REQUIREMENT_TYPE;
            case NON_FUNCTIONAL -> NON_FUNCTIONAL_REQUIREMENT_TYPE;
        };
    }

    private static RequirementType typeFromIri(String iri) {
        if (FUNCTIONAL_REQUIREMENT_TYPE.equals(iri)) {
            return RequirementType.FUNCTIONAL;
        }
        if (NON_FUNCTIONAL_REQUIREMENT_TYPE.equals(iri)) {
            return RequirementType.NON_FUNCTIONAL;
        }
        throw new IllegalStateException("unexpected requirement type " + iri);
    }

    private static String statusIriFor(RequirementStatus status) {
        return switch (status) {
            case PROPOSED -> PROPOSED_STATUS;
            case ACCEPTED -> ACCEPTED_STATUS;
        };
    }

    /**
     * Decodes a status IRI into the MVP subset {@link RequirementStatus} implements.
     *
     * <p>Unlike {@link #typeFromIri}, whose only two legal inputs are guaranteed by
     * {@link #requirementWhereClause}'s {@code FILTER} on {@code ?type}, {@code ?status} is
     * <strong>not</strong> filtered the same way: {@code requirements-shapes.ttl}'s
     * {@code Requirement-status} shape SHACL-legally allows six status individuals, but
     * {@link RequirementStatus} only implements two. Filtering the other four out here would
     * silently make a SHACL-legal, store-first requirement invisible to
     * {@code req_list}/{@code req_get} instead of failing loudly -
     * {@link UnsupportedRequirementStatusException} is thrown directly (never as a wrapped
     * {@link IllegalStateException}) so the caller sees which requirement and which unsupported
     * status, instead of every other requirement in the project becoming unreachable too.</p>
     *
     * @throws UnsupportedRequirementStatusException if {@code iri} is SHACL-legal but not one of
     *                                                 the two individuals {@link RequirementStatus}
     *                                                 implements
     */
    private static RequirementStatus statusFromIri(ProjectId projectId, RequirementCode code, String iri) {
        if (PROPOSED_STATUS.equals(iri)) {
            return RequirementStatus.PROPOSED;
        }
        if (ACCEPTED_STATUS.equals(iri)) {
            return RequirementStatus.ACCEPTED;
        }
        throw new UnsupportedRequirementStatusException(projectId, code, iri);
    }

    private static String priorityIriFor(Priority priority) {
        return switch (priority) {
            case MUST_HAVE -> MUST_HAVE_PRIORITY;
            case SHOULD_HAVE -> SHOULD_HAVE_PRIORITY;
            case COULD_HAVE -> COULD_HAVE_PRIORITY;
            case WONT_HAVE -> WONT_HAVE_PRIORITY;
        };
    }

    private static Priority priorityFromIri(String iri) {
        if (MUST_HAVE_PRIORITY.equals(iri)) {
            return Priority.MUST_HAVE;
        }
        if (SHOULD_HAVE_PRIORITY.equals(iri)) {
            return Priority.SHOULD_HAVE;
        }
        if (COULD_HAVE_PRIORITY.equals(iri)) {
            return Priority.COULD_HAVE;
        }
        if (WONT_HAVE_PRIORITY.equals(iri)) {
            return Priority.WONT_HAVE;
        }
        throw new IllegalStateException("unexpected priority " + iri);
    }

    /**
     * Decodes the optional {@code arkreq:priority} binding, guarding the {@link IRI} cast against
     * a store-first value of the wrong RDF term kind - {@code requirements-shapes.ttl}'s
     * {@code Requirement-priority} shape has no {@code sh:nodeKind}, so a literal there is
     * SHACL-legal at {@code sh:Warning} severity and never rejected by the write gate. Unlike
     * {@link #statusFromIri} (a mandatory field, so a SHACL-legal but undecodable value fails
     * loudly via {@link UnsupportedRequirementStatusException}), {@code priority} is already an
     * optional domain field: a type-mismatched value is logged at {@code WARN} and read as
     * "not set" instead of aborting the whole row, the same "stille Luege, sichtbar gemacht"
     * idiom {@link RequirementAssembly#firstDistinct} uses for colliding candidates.
     */
    private static Priority priorityOf(BindingSet row) {
        Optional<RDFTerm> value = row.getValue("priority");
        if (value.isEmpty()) {
            return null;
        }
        if (value.get() instanceof IRI iri) {
            return priorityFromIri(iri.getIRIString());
        }
        LOG.warn("Requirement {}: field 'priority' expected an IRI but found a {}, ignoring the value",
                iriOf(row, "s").getIRIString(), value.get().getClass().getSimpleName());
        return null;
    }

    /** {@link #priorityOf} with {@code arkreq:motivatedBy} in place of {@code arkreq:priority}. */
    private static String motivatedByOf(BindingSet row) {
        Optional<RDFTerm> value = row.getValue("motivatedBy");
        if (value.isEmpty()) {
            return null;
        }
        if (value.get() instanceof IRI iri) {
            return iri.getIRIString();
        }
        LOG.warn("Requirement {}: field 'motivatedBy' expected an IRI but found a {}, ignoring the value",
                iriOf(row, "s").getIRIString(), value.get().getClass().getSimpleName());
        return null;
    }

    /**
     * {@link #priorityOf} with {@code arkreq:qualityCategory} in place of {@code arkreq:priority},
     * except the expected RDF term kind is a {@link Literal} (the field's SHACL shape declares
     * {@code sh:datatype xsd:string}), not an {@link IRI}.
     */
    private static String qualityCategoryOf(BindingSet row) {
        Optional<RDFTerm> value = row.getValue("qualityCategory");
        if (value.isEmpty()) {
            return null;
        }
        if (value.get() instanceof Literal literal) {
            return literal.getLexicalForm();
        }
        LOG.warn("Requirement {}: field 'qualityCategory' expected a Literal but found a {}, ignoring the value",
                iriOf(row, "s").getIRIString(), value.get().getClass().getSimpleName());
        return null;
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    /** Converts a bound literal into the technology-neutral {@link LocalizedLiteral} projection. */
    private static LocalizedLiteral localizedLiteralOf(BindingSet row, String name) {
        Literal literal = literalOf(row, name);
        return new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null));
    }

    /**
     * Reads a binding as the bare {@link RDFTerm} it is, without narrowing it to {@link IRI} -
     * unlike {@link #iriOf}, deliberately used where the binding's kind is not known in advance
     * (e.g. an {@code arkreq:usesTerm} target, which may legally be a blank node).
     */
    private static RDFTerm termOf(BindingSet row, String name) {
        return row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
