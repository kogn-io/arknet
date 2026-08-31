// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotDeletableException;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrReferencedException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.adr.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.adr.domain.TermRef;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.InvalidLanguageTagException;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Out-adapter: {@link AdrRepository} backed by the kognio-rdf substrate ({@code io.kogn.rdf},
 * embeddable RDF store).
 *
 * <p>Maps an {@link Adr} to its opaque {@link AdrId} as the subject IRI, stored in one named graph
 * shared by all decisions. Every predicate and type IRI comes from the shared
 * {@link ArkarchVocabulary}. This class depends only on the neutral kognio-rdf ports and
 * {@link SimpleRdf} - it never imports RDF4J. The backend ({@link DatasetLifecycle} implementation)
 * is supplied by the composition root.</p>
 *
 * <p><strong>Consequences and considered options (kogn-io/arknet#357).</strong> Both are lists of
 * own, positioned resources - {@code arkarch:Consequence}/{@code arkarch:ConsideredOption} - mirroring
 * {@code KognioRdfRequirementRepository}'s {@code arkreq:AcceptanceCriterion} handling (issue #266)
 * almost exactly: a fresh opaque IRI minted per child on every write, {@code arknet:position} as the
 * shared, domain-agnostic ordinal (kogn-io/arknet#357 issue E), and a capture-before-delete/
 * reattach-after-write mechanism for every language variant a child's text carries that this write
 * does not touch. Two differences from that precedent: both lists are optional (never {@code
 * sh:minCount 1} at {@code sh:Violation}), and the pre-#357 flat {@code arkarch:adrConsequences}/
 * {@code arkarch:adrAlternatives} literals are preserved <em>unconditionally</em> on every write
 * (like {@code arkarch:supersedes}) rather than surfaced as a domain field - see
 * {@link #legacyConsequenceOrNone}/{@link #legacyConsideredOptionOrNone} for how the read side turns
 * an otherwise-empty structured list back into one synthesised entry, for display only.</p>
 *
 * <p><strong>Legacy fallback never reaches the read-modify-write path.</strong>
 * {@link #findCurrentByCode} (backing every {@code adr_update}/{@code adr_set_status}/
 * {@code adr_supersede} round trip) never synthesises a legacy placeholder into {@code
 * Adr#consequences()}/{@code #consideredOptions()} - only {@link #findByCode}/{@link #findAll} (the
 * caller-facing display reads) do. Both lists are simply optional, unlike the mandatory
 * {@code acceptanceCriterion} placeholder {@code KognioRdfRequirementRepository} must actively guard
 * a write against re-persisting: an empty structured list here is already a legal state to write,
 * so a synthesised entry never has anywhere it could be silently promoted to a real, persisted
 * literal.</p>
 *
 * <p><strong>Multilingual fields.</strong> {@code arknet:name}, {@code arkarch:adrContext},
 * {@code arkarch:adrDecision}, each consequence's {@code arkarch:consequenceStatement} and each
 * considered option's {@code arknet:name}/{@code arkarch:optionRationale} may each legally carry
 * several language-tagged variants ({@code sh:uniqueLang}). Read via {@link DisplayLocale}, written
 * one variant per call, every other variant preserved past the gate - the exact mechanism
 * {@code KognioRdfRequirementRepository} already carries for {@code title}/{@code description}/
 * {@code rationale}/{@code criterionText}, including the issue #258 stale-untagged-sibling sweep.
 * {@code adr_add}/{@code adr_update} share one {@code language} argument for the whole call
 * (deliberate simplification vs. the requirements bounded context's per-field arguments -
 * see {@code AdrService}'s class javadoc), so every touched field/position in one call resolves the
 * same tag.</p>
 *
 * <p><strong>Tolerant reads.</strong> A store-first (ADR-005) anomaly this adapter cannot decode into
 * a legal {@link Adr} - an unrecognised status, a broken status/supersededBy bi-implication, or any
 * other {@link Adr} constructor invariant (e.g. two {@code arkarch:optionOutcome Chosen} children) -
 * is logged at {@code WARN} and the one decision is skipped, never crashing {@link #findByCode}/
 * {@link #findAll} for the whole project. See {@link #toAdrOrNull}.</p>
 */
public class KognioRdfAdrRepository implements AdrRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfAdrRepository.class);

    private static final String ARKNET_NAMESPACE = "https://w3id.org/arknet/core#";
    private static final String ADR_GRAPH = "https://w3id.org/arknet/model/adr";

    private static final String CODE_PREFIX = "ADR-";

    private static final String ADR_TYPE = ArkarchVocabulary.ADR_TYPE;
    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String NAME_PROPERTY = ARKNET_NAMESPACE + "name";
    private static final String POSITION_PROPERTY = ARKNET_NAMESPACE + "position";
    private static final String STATUS_PROPERTY = ArkarchVocabulary.ADR_STATUS;
    private static final String CONTEXT_PROPERTY = ArkarchVocabulary.ADR_CONTEXT;
    private static final String DECISION_PROPERTY = ArkarchVocabulary.ADR_DECISION;
    private static final String CONSEQUENCES_PROPERTY = ArkarchVocabulary.ADR_CONSEQUENCES;
    private static final String ALTERNATIVES_PROPERTY = ArkarchVocabulary.ADR_ALTERNATIVES;
    private static final String DECISION_DATE_PROPERTY = ArkarchVocabulary.DECISION_DATE;
    private static final String ADDRESSES_REQUIREMENT_PROPERTY = ArkarchVocabulary.ADDRESSES_REQUIREMENT;
    private static final String AFFECTS_CONTEXT_PROPERTY = ArkarchVocabulary.AFFECTS_CONTEXT;
    private static final String USES_TERM_PROPERTY = ArkarchVocabulary.USES_TERM;
    private static final String SUPERSEDES_PROPERTY = ArkarchVocabulary.SUPERSEDES;
    private static final String SUPERSEDED_BY_PROPERTY = ArkarchVocabulary.SUPERSEDED_BY;
    private static final String RELATED_TO_PROPERTY = ArkarchVocabulary.RELATED_TO;

    private static final String CONSEQUENCE_PROPERTY = ArkarchVocabulary.CONSEQUENCE;
    private static final String CONSEQUENCE_TYPE_CLASS = ArkarchVocabulary.CONSEQUENCE_TYPE_CLASS;
    private static final String CONSEQUENCE_STATEMENT_PROPERTY = ArkarchVocabulary.CONSEQUENCE_STATEMENT;
    private static final String CONSEQUENCE_TYPE_PROPERTY = ArkarchVocabulary.CONSEQUENCE_TYPE_PROPERTY;

    private static final String CONSIDERED_OPTION_PROPERTY = ArkarchVocabulary.CONSIDERED_OPTION;
    private static final String CONSIDERED_OPTION_TYPE_CLASS = ArkarchVocabulary.CONSIDERED_OPTION_TYPE_CLASS;
    private static final String OPTION_RATIONALE_PROPERTY = ArkarchVocabulary.OPTION_RATIONALE;
    private static final String OPTION_OUTCOME_PROPERTY = ArkarchVocabulary.OPTION_OUTCOME_PROPERTY;

    /** Legacy-fallback placeholder name for a store-first {@code arkarch:adrAlternatives} literal. */
    private static final String LEGACY_OPTION_NAME_PLACEHOLDER = "(Altdatensatz - kein Name hinterlegt)";

    private static final Comparator<String> CODE_BY_RUNNING_NUMBER =
            Comparator.<String>comparingInt(KognioRdfAdrRepository::runningNumber)
                    .thenComparing(Comparator.naturalOrder());

    private final DatasetLifecycle lifecycle;
    private final ResourceIdFactory resourceIdFactory;
    private final DisplayLocale displayLocale;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle         the kognio-rdf dataset lifecycle to acquire datasets from - read
     *                          paths only, the write path goes through {@code funnel} (must not be
     *                          {@code null})
     * @param resourceIdFactory mints the opaque IRI of each derived consequence/considered-option
     *                          resource (kogn-io/arknet#357; must not be {@code null})
     * @param displayLocale     the display-language preference selecting which candidate of a
     *                          multilingual field the read paths surface (must not be {@code null})
     * @param funnel            the shared write funnel (ADR-013) running the SHACL gate, dataset
     *                          acquisition and existence/head checks for every {@link #create}/
     *                          {@link #compareAndUpdate} (must not be {@code null})
     */
    KognioRdfAdrRepository(DatasetLifecycle lifecycle, ResourceIdFactory resourceIdFactory,
            DisplayLocale displayLocale, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, Adr adr, String language) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(adr, "adr");
        String tag = LanguageTag.canonicalize(language);

        String subjectIriString = adr.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(ADR_GRAPH);
        Map<Integer, String> consequenceTags = new LinkedHashMap<>();
        adr.consequences().forEach(c -> consequenceTags.put(c.position(), tag));
        Map<Integer, String> optionTags = new LinkedHashMap<>();
        adr.consideredOptions().forEach(o -> optionTags.put(o.position(), tag));
        AdrCandidate candidate =
                buildCandidateGraph(subjectIri, adr, tag, tag, tag, consequenceTags, optionTags);
        Graph graph = candidate.graph();

        funnel.create(new DatasetId(projectId.value()), ADR_GRAPH, subjectIriString,
                adr.code().value(), graph, crossReferenceAssertedContext(projectId, adr),
                () -> new ResourceAlreadyExistsException(projectId, adr.id().value()),
                () -> new DuplicateAdrCodeException(projectId, adr.code()),
                tx -> replaceTriples(tx, graphIri, subjectIri, subject, graph, false, tag, tag, tag, null,
                        consequenceTags, optionTags, null, candidate));
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, String expectedHead, Adr updated,
            String nameLanguage, String contextLanguage, String decisionLanguage,
            Map<Integer, String> consequenceLanguageByPosition, Map<Integer, String> optionLanguageByPosition,
            String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updated, "updated");
        Objects.requireNonNull(consequenceLanguageByPosition, "consequenceLanguageByPosition");
        Objects.requireNonNull(optionLanguageByPosition, "optionLanguageByPosition");
        String nameTag = canonicalizeLenient(nameLanguage);
        String contextTag = canonicalizeLenient(contextLanguage);
        String decisionTag = canonicalizeLenient(decisionLanguage);
        String defaultTag = canonicalizeLenient(defaultLanguage);
        Map<Integer, String> consequenceTags = new LinkedHashMap<>();
        consequenceLanguageByPosition.forEach((position, tag) -> consequenceTags.put(position, canonicalizeLenient(tag)));
        Map<Integer, String> optionTags = new LinkedHashMap<>();
        optionLanguageByPosition.forEach((position, tag) -> optionTags.put(position, canonicalizeLenient(tag)));

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(ADR_GRAPH);
        AdrCandidate candidate = buildCandidateGraph(
                subjectIri, updated, nameTag, contextTag, decisionTag, consequenceTags, optionTags);
        Graph graph = candidate.graph();

        funnel.compareAndUpdate(new DatasetId(projectId.value()), ADR_GRAPH, subjectIriString,
                expectedHead, graph, crossReferenceAssertedContext(projectId, updated),
                () -> new AdrNotFoundException(projectId, updated.code()),
                () -> new AdrConcurrentlyModifiedException(projectId, updated.code()),
                tx -> replaceTriples(tx, graphIri, subjectIri, subject, graph, true, nameTag, contextTag,
                        decisionTag, defaultTag, consequenceTags, optionTags, defaultTag, candidate));
    }

    /**
     * {@link #buildCandidateGraph}'s result: the graph plus the freshly minted IRI of each
     * consequence/considered-option position - {@link #replaceTriples} needs the mapping to know
     * which new child subject a preserved other-language variant re-attaches to (mirrors
     * {@code KognioRdfRequirementRepository.RequirementCandidate}).
     */
    private record AdrCandidate(
            Graph graph, Map<Integer, IRI> consequenceIriByPosition, Map<Integer, IRI> optionIriByPosition) {
    }

    /**
     * Builds the candidate graph for one decision's triples: type, identifier, status, the three
     * multilingual scalar literals (each written under its own tag), the two optional pre-#357 flat
     * literals (never written by this method - see class javadoc), {@code decisionDate}, every
     * consequence/considered-option as its own freshly minted resource, and the five reference
     * edges. Shared by {@link #create} and {@link #compareAndUpdate}.
     */
    private AdrCandidate buildCandidateGraph(IRI subjectIri, Adr adr, String nameTag, String contextTag,
            String decisionTag, Map<Integer, String> consequenceTagByPosition, Map<Integer, String> optionTagByPosition) {
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(ADR_TYPE));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(adr.code().value()));
        graph.add(subjectIri, rdf.createIRI(NAME_PROPERTY), literalOf(adr.name(), nameTag));
        graph.add(subjectIri, rdf.createIRI(STATUS_PROPERTY), rdf.createIRI(statusIriFor(adr.status())));
        graph.add(subjectIri, rdf.createIRI(CONTEXT_PROPERTY), literalOf(adr.context(), contextTag));
        graph.add(subjectIri, rdf.createIRI(DECISION_PROPERTY), literalOf(adr.decision(), decisionTag));
        if (adr.decisionDate() != null) {
            graph.add(subjectIri, rdf.createIRI(DECISION_DATE_PROPERTY),
                    rdf.createLiteral(adr.decisionDate().toString(), VocabXsd.DATE));
        }
        Map<Integer, IRI> consequenceIriByPosition = new LinkedHashMap<>();
        for (Consequence consequence : adr.consequences()) {
            IRI consequenceIri = mintChildIri();
            consequenceIriByPosition.put(consequence.position(), consequenceIri);
            graph.add(subjectIri, rdf.createIRI(CONSEQUENCE_PROPERTY), consequenceIri);
            graph.add(consequenceIri, VocabRdf.TYPE, rdf.createIRI(CONSEQUENCE_TYPE_CLASS));
            graph.add(consequenceIri, rdf.createIRI(POSITION_PROPERTY),
                    rdf.createLiteral(Integer.toString(consequence.position()), VocabXsd.INTEGER));
            graph.add(consequenceIri, rdf.createIRI(CONSEQUENCE_TYPE_PROPERTY),
                    rdf.createIRI(consequenceTypeIriFor(consequence.type())));
            graph.add(consequenceIri, rdf.createIRI(CONSEQUENCE_STATEMENT_PROPERTY),
                    literalOf(consequence.statement(), consequenceTagByPosition.get(consequence.position())));
        }
        Map<Integer, IRI> optionIriByPosition = new LinkedHashMap<>();
        for (ConsideredOption option : adr.consideredOptions()) {
            IRI optionIri = mintChildIri();
            optionIriByPosition.put(option.position(), optionIri);
            graph.add(subjectIri, rdf.createIRI(CONSIDERED_OPTION_PROPERTY), optionIri);
            graph.add(optionIri, VocabRdf.TYPE, rdf.createIRI(CONSIDERED_OPTION_TYPE_CLASS));
            graph.add(optionIri, rdf.createIRI(POSITION_PROPERTY),
                    rdf.createLiteral(Integer.toString(option.position()), VocabXsd.INTEGER));
            graph.add(optionIri, rdf.createIRI(NAME_PROPERTY),
                    literalOf(option.name(), optionTagByPosition.get(option.position())));
            graph.add(optionIri, rdf.createIRI(OPTION_RATIONALE_PROPERTY),
                    literalOf(option.rationale(), optionTagByPosition.get(option.position())));
            // outcome() is null only for a legacy-literal-synthesised option, which never reaches
            // this method (see class javadoc "Legacy fallback never reaches the read-modify-write
            // path") - every real write carries one.
            if (option.outcome() != null) {
                graph.add(optionIri, rdf.createIRI(OPTION_OUTCOME_PROPERTY),
                        rdf.createIRI(optionOutcomeIriFor(option.outcome())));
            }
        }
        for (RequirementRef ref : adr.addressesRequirements()) {
            graph.add(subjectIri, rdf.createIRI(ADDRESSES_REQUIREMENT_PROPERTY),
                    rdf.createIRI(ref.value().value()));
        }
        for (BoundedContextRef ref : adr.affectsContexts()) {
            graph.add(subjectIri, rdf.createIRI(AFFECTS_CONTEXT_PROPERTY), rdf.createIRI(ref.value().value()));
        }
        for (TermRef ref : adr.usesTerms()) {
            graph.add(subjectIri, rdf.createIRI(USES_TERM_PROPERTY), rdf.createIRI(ref.value().value()));
        }
        if (adr.supersededBy() != null) {
            graph.add(subjectIri, rdf.createIRI(SUPERSEDED_BY_PROPERTY),
                    rdf.createIRI(adr.supersededBy().value().value()));
        }
        for (AdrId peer : adr.relatedTo()) {
            graph.add(subjectIri, rdf.createIRI(RELATED_TO_PROPERTY), rdf.createIRI(peer.value().value()));
        }
        return new AdrCandidate(graph, consequenceIriByPosition, optionIriByPosition);
    }

    /** Mints an opaque IRI for a derived consequence/considered-option resource, re-minted on every write. */
    private IRI mintChildIri() {
        return rdf.createIRI(resourceIdFactory.newId().value());
    }

    /**
     * Collects the validation-only triples both {@code ashapes:ADR-relatedTo} and
     * {@code ashapes:ADR-supersededBy}'s {@code sh:class} constraints need: for each {@code
     * relatedTo} peer and the {@code supersededBy} target, its type plus the five fields
     * {@code ashapes:ADRShape}'s {@code sh:Violation} property shapes require.
     */
    private Graph crossReferenceAssertedContext(ProjectId projectId, Adr adr) {
        Graph assertedContext = rdf.createGraph();
        List<AdrId> peers = adr.supersededBy() == null
                ? adr.relatedTo()
                : Stream.concat(adr.relatedTo().stream(), Stream.of(adr.supersededBy())).distinct().toList();
        if (peers.isEmpty()) {
            return assertedContext;
        }
        String values = peers.stream()
                .map(peer -> SparqlTerms.iriRef(peer.value().value()))
                .distinct()
                .collect(Collectors.joining(" "));
        String query = "SELECT ?s ?p ?o WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "VALUES ?s { " + values + " } ?s ?p ?o } "
                + "FILTER( ?p IN (<" + VocabRdf.TYPE.getIRIString() + ">, <" + IDENTIFIER_PROPERTY
                + ">, <" + NAME_PROPERTY + ">, <" + STATUS_PROPERTY + ">, <" + CONTEXT_PROPERTY
                + ">, <" + DECISION_PROPERTY + ">) ) }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.sparqlQuery().select(query).forEach(row -> assertedContext.add(
                    iriOf(row, "s"), iriOf(row, "p"), row.getValue("o").orElseThrow()));
        }
        return assertedContext;
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write transaction,
     * capturing everything a replace-by-identity write would otherwise erase but could not itself
     * carry forward, and re-attaching it after the rewrite:
     *
     * <ul>
     * <li><strong>Unconditionally</strong>: every {@code arkarch:supersedes} edge (pre-#357 legacy
     * shape) and both flat {@code arkarch:adrConsequences}/{@code arkarch:adrAlternatives} literals
     * (kogn-io/arknet#357 - the pre-#357 shape their own structured resources replace; see class
     * javadoc). None of the three is a field of {@link Adr} any more, so the candidate graph never
     * carries them forward on its own.</li>
     * <li>{@code addressesRequirement}/{@code affectsContext} edges whose target is not an IRI.</li>
     * <li>Every other-language variant of {@code name}/{@code context}/{@code decision} not written
     * by this call, and of each consequence/considered-option's own multilingual text, keyed by
     * {@code arknet:position} rather than by the about-to-be-deleted child IRI (mirrors
     * {@code KognioRdfRequirementRepository#otherLanguageAcceptanceCriterionTexts} - safe here for
     * the same reason: {@code adr_update} never reorders or removes a position, only appends or
     * patches in place).</li>
     * </ul>
     *
     * <p>{@code deleteExisting} follows both {@code arkarch:consequence}/{@code arkarch:consideredOption}
     * edges and deletes the pointed-at child's own triples (the UNION hop, mirrors
     * {@code KognioRdfRequirementRepository}'s {@code acceptanceCriterion} traversal).</p>
     */
    private void replaceTriples(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            boolean exists, String nameTag, String contextTag, String decisionTag, String defaultTag,
            Map<Integer, String> consequenceTagByPosition, Map<Integer, String> optionTagByPosition,
            String childDefaultTag, AdrCandidate candidate) {
        String selectPreservedEdges = "SELECT ?p ?o WHERE { GRAPH <" + ADR_GRAPH + "> { " + subject + " ?p ?o } "
                + "FILTER( ?p = <" + SUPERSEDES_PROPERTY + "> "
                + "|| ( ?p IN (<" + ADDRESSES_REQUIREMENT_PROPERTY + ">, <" + AFFECTS_CONTEXT_PROPERTY
                + ">) && !isIRI(?o) ) ) }";
        String selectPreservedLiterals = "SELECT ?p ?o WHERE { GRAPH <" + ADR_GRAPH + "> { " + subject + " ?p ?o } "
                + "FILTER( ?p IN (<" + CONSEQUENCES_PROPERTY + ">, <" + ALTERNATIVES_PROPERTY + ">) ) }";
        String deleteExisting = "DELETE { GRAPH <" + ADR_GRAPH + "> { ?s ?p ?o } } WHERE { "
                + "GRAPH <" + ADR_GRAPH + "> { "
                + "{ " + subject + " ?p ?o . BIND(" + subject + " AS ?s) } UNION "
                + "{ " + subject + " <" + CONSEQUENCE_PROPERTY + "> ?s . ?s ?p ?o } UNION "
                + "{ " + subject + " <" + CONSIDERED_OPTION_PROPERTY + "> ?s . ?s ?p ?o } } }";

        List<PreservedEdge> preservedEdges = exists
                ? tx.select(selectPreservedEdges)
                        .map(row -> new PreservedEdge(iriOf(row, "p"), termOf(row, "o")))
                        .toList()
                : List.of();
        List<PreservedEdge> preservedLiterals = exists
                ? tx.select(selectPreservedLiterals)
                        .map(row -> new PreservedEdge(iriOf(row, "p"), termOf(row, "o")))
                        .toList()
                : List.of();
        List<Literal> preservedNames = exists
                ? otherLanguageLiterals(tx, subject, NAME_PROPERTY, nameTag, defaultTag) : List.of();
        List<Literal> preservedContexts = exists
                ? otherLanguageLiterals(tx, subject, CONTEXT_PROPERTY, contextTag, defaultTag) : List.of();
        List<Literal> preservedDecisions = exists
                ? otherLanguageLiterals(tx, subject, DECISION_PROPERTY, decisionTag, defaultTag) : List.of();
        Map<Integer, List<Literal>> preservedConsequenceTexts = exists
                ? otherLanguageChildTexts(tx, subject, CONSEQUENCE_PROPERTY, CONSEQUENCE_STATEMENT_PROPERTY,
                        consequenceTagByPosition, childDefaultTag)
                : Map.of();
        Map<Integer, List<Literal>> preservedOptionNames = exists
                ? otherLanguageChildTexts(tx, subject, CONSIDERED_OPTION_PROPERTY, NAME_PROPERTY,
                        optionTagByPosition, childDefaultTag)
                : Map.of();
        Map<Integer, List<Literal>> preservedOptionRationales = exists
                ? otherLanguageChildTexts(tx, subject, CONSIDERED_OPTION_PROPERTY, OPTION_RATIONALE_PROPERTY,
                        optionTagByPosition, childDefaultTag)
                : Map.of();

        if (exists) {
            tx.update(deleteExisting);
        }
        tx.add(graphIri, graph);

        Graph preservedGraph = rdf.createGraph();
        for (PreservedEdge edge : preservedEdges) {
            preservedGraph.add(subjectIri, edge.predicate(), edge.object());
        }
        for (PreservedEdge literal : preservedLiterals) {
            preservedGraph.add(subjectIri, literal.predicate(), literal.object());
        }
        for (Literal name : preservedNames) {
            preservedGraph.add(subjectIri, rdf.createIRI(NAME_PROPERTY), name);
        }
        for (Literal context : preservedContexts) {
            preservedGraph.add(subjectIri, rdf.createIRI(CONTEXT_PROPERTY), context);
        }
        for (Literal decision : preservedDecisions) {
            preservedGraph.add(subjectIri, rdf.createIRI(DECISION_PROPERTY), decision);
        }
        reattachChildTexts(preservedGraph, preservedConsequenceTexts, candidate.consequenceIriByPosition(),
                CONSEQUENCE_STATEMENT_PROPERTY);
        reattachChildTexts(preservedGraph, preservedOptionNames, candidate.optionIriByPosition(), NAME_PROPERTY);
        reattachChildTexts(preservedGraph, preservedOptionRationales, candidate.optionIriByPosition(),
                OPTION_RATIONALE_PROPERTY);
        tx.add(graphIri, preservedGraph);
    }

    private void reattachChildTexts(Graph target, Map<Integer, List<Literal>> textsByPosition,
            Map<Integer, IRI> newChildIriByPosition, String predicateIri) {
        textsByPosition.forEach((position, texts) -> {
            IRI newChildIri = newChildIriByPosition.get(position);
            if (newChildIri != null) {
                for (Literal text : texts) {
                    target.add(newChildIri, rdf.createIRI(predicateIri), text);
                }
            }
        });
    }

    /** One edge/literal {@link #replaceTriples} captures before the rewrite and re-attaches afterwards. */
    private record PreservedEdge(IRI predicate, RDFTerm object) {
    }

    /**
     * Every existing literal of {@code subject} on {@code predicateIri} whose language tag differs
     * from {@code writtenTag}, captured before {@code deleteExisting} wipes them. Mirrors
     * {@code KognioRdfRequirementRepository#otherLanguageLiterals} exactly, including the issue #258
     * sweep of a stale untagged sibling when {@code writtenTag} equals {@code defaultTag}.
     */
    private List<Literal> otherLanguageLiterals(
            DatasetTx tx, String subject, String predicateIri, String writtenTag, String defaultTag) {
        String query = "SELECT ?o WHERE { GRAPH <" + ADR_GRAPH + "> { "
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
     * {@link #otherLanguageLiterals} for a child resource's text predicate, keyed by
     * {@code arknet:position} rather than by the about-to-be-deleted child IRI - mirrors
     * {@code KognioRdfRequirementRepository#otherLanguageAcceptanceCriterionTexts}.
     *
     * @param childEdgePredicate {@code arkarch:consequence}/{@code arkarch:consideredOption}
     * @param textPredicate      {@code arkarch:consequenceStatement}/{@code arknet:name}/
     *                           {@code arkarch:optionRationale}
     * @param writtenTagByPosition the tag this write is about to (re)write at each position
     * @param defaultTag         the target project's configured default language, canonicalized
     */
    private Map<Integer, List<Literal>> otherLanguageChildTexts(DatasetTx tx, String subject,
            String childEdgePredicate, String textPredicate, Map<Integer, String> writtenTagByPosition,
            String defaultTag) {
        String query = "SELECT ?position ?text WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + childEdgePredicate + "> ?child . "
                + "?child <" + POSITION_PROPERTY + "> ?position ; <" + textPredicate + "> ?text } }";
        Map<Integer, List<Literal>> byPosition = new LinkedHashMap<>();
        tx.select(query).forEach(row -> {
            int position = Integer.parseInt(literalOf(row, "position").getLexicalForm());
            Literal text = literalOf(row, "text");
            String writtenTag = writtenTagByPosition.get(position);
            String existingTag = canonicalizeLenient(text.getLanguageTag().orElse(null));
            boolean sweepUntagged = defaultTag != null && defaultTag.equals(writtenTag) && existingTag == null;
            if (!sweepUntagged && !Objects.equals(existingTag, writtenTag)) {
                byPosition.computeIfAbsent(position, key -> new ArrayList<>()).add(text);
            }
        });
        return byPosition;
    }

    /** {@link LanguageTag#canonicalize(String)} falling back to {@code null} instead of throwing. */
    private static String canonicalizeLenient(String tag) {
        try {
            return LanguageTag.canonicalize(tag);
        } catch (InvalidLanguageTagException e) {
            return null;
        }
    }

    /** Builds a language-tagged literal, or a plain untagged one when {@code tag} is {@code null}. */
    private Literal literalOf(String value, String tag) {
        return tag == null ? rdf.createLiteral(value) : rdf.createLiteral(value, tag);
    }

    /**
     * Deletes the decision identified by {@code code}, and every triple it carries in
     * {@link #ADR_GRAPH} (including every consequence/considered-option child's own triples), from
     * the project. Passes {@code code} through to {@link WriteFunnel#delete}, which keeps it out of
     * circulation on the tombstoned revision (issue #350) - shared with {@code term_delete}/
     * {@code actor_delete} rather than kept as a local mechanism, see {@link #findRetainedCodes}.
     */
    @Override
    public void delete(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        DatasetId dataset = new DatasetId(projectId.value());
        String subjectIriString;
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            String query = "SELECT ?s WHERE { GRAPH <" + ADR_GRAPH + "> { "
                    + "?s a <" + ADR_TYPE + "> . "
                    + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . "
                    + "FILTER(isIRI(?s)) } }";
            subjectIriString = handle.sparqlQuery().select(query).findFirst()
                    .map(row -> iriOf(row, "s").getIRIString())
                    .orElseThrow(() -> new AdrNotFoundException(projectId, code));
        }
        String subject = SparqlTerms.iriRef(subjectIriString);

        funnel.delete(dataset, ADR_GRAPH, subjectIriString, code.value(),
                () -> new AdrNotFoundException(projectId, code),
                tx -> {
                    rejectIfNotProposed(tx, code, subjectIriString);
                    rejectIfReferenced(tx, projectId, code, subjectIriString);
                    tx.update("DELETE { GRAPH <" + ADR_GRAPH + "> { ?s ?p ?o } } WHERE { "
                            + "GRAPH <" + ADR_GRAPH + "> { "
                            + "{ " + subject + " ?p ?o . BIND(" + subject + " AS ?s) } UNION "
                            + "{ " + subject + " <" + CONSEQUENCE_PROPERTY + "> ?s . ?s ?p ?o } UNION "
                            + "{ " + subject + " <" + CONSIDERED_OPTION_PROPERTY + "> ?s . ?s ?p ?o } } }");
                });
    }

    private void rejectIfNotProposed(DatasetTx tx, AdrCode code, String subjectIri) {
        String subject = SparqlTerms.iriRef(subjectIri);
        String query = "SELECT ?status WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + STATUS_PROPERTY + "> ?status } }";
        AdrStatus status = tx.select(query).findFirst().map(KognioRdfAdrRepository::statusOf).orElse(null);
        if (status != AdrStatus.PROPOSED) {
            throw new AdrNotDeletableException(code, status);
        }
    }

    private void rejectIfReferenced(DatasetTx tx, ProjectId projectId, AdrCode code, String subjectIri) {
        String target = SparqlTerms.iriRef(subjectIri);
        List<AdrReferencedException.Reference> references = new ArrayList<>();
        collectReferences(tx, target, SUPERSEDES_PROPERTY, AdrReferencedException.SUPERSEDES, references);
        collectReferences(tx, target, SUPERSEDED_BY_PROPERTY, AdrReferencedException.SUPERSEDED_BY, references);
        collectReferences(tx, target, RELATED_TO_PROPERTY, AdrReferencedException.RELATED_TO, references);
        if (!references.isEmpty()) {
            throw new AdrReferencedException(projectId, code, references);
        }
    }

    private static void collectReferences(DatasetTx tx, String target, String predicate, String shorthand,
            List<AdrReferencedException.Reference> into) {
        String query = "SELECT ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "?s <" + predicate + "> " + target + " . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier } }";
        tx.select(query)
                .map(row -> literalOf(row, "identifier").getLexicalForm())
                .collect(Collectors.toCollection(() -> new TreeSet<>(CODE_BY_RUNNING_NUMBER)))
                .forEach(value -> into.add(
                        new AdrReferencedException.Reference(new AdrCode(value), shorthand)));
    }

    /**
     * Reads back the codes {@link WriteFunnel#delete}'s {@code code} parameter retained (issue
     * #350): the shared funnel keeps the number out of circulation, this hexagon only maps its raw
     * strings to {@link AdrCode} and orders them by running number.
     */
    @Override
    public List<AdrCode> findRetainedCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        return funnel.findRetainedCodes(new DatasetId(projectId.value()), CODE_PREFIX).stream()
                .collect(Collectors.toCollection(() -> new TreeSet<>(CODE_BY_RUNNING_NUMBER)))
                .stream()
                .map(AdrCode::new)
                .toList();
    }

    // ---- reads ---------------------------------------------------------------------------

    private DisplayLocale withRequestedOverride(String requestedOverride) {
        if (requestedOverride == null || requestedOverride.isBlank()) {
            return displayLocale;
        }
        return new DisplayLocale(Locale.forLanguageTag(requestedOverride), displayLocale.systemDefault());
    }

    @Override
    public Optional<Adr> findByCode(ProjectId projectId, AdrCode code, String requestedDisplayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        DisplayLocale effective = withRequestedOverride(requestedDisplayLocale);

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Optional<String> subject = subjectFor(handle, code);
            if (subject.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(assembleAdr(handle, subject.get(), code, effective, true));
        }
    }

    @Override
    public Optional<CurrentAdr> findCurrentByCode(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?head WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "?s a <" + ADR_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . "
                + "FILTER(isIRI(?s)) } "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?s <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Optional<BindingSet> row = handle.sparqlQuery().select(query).findFirst();
            if (row.isEmpty()) {
                return Optional.empty();
            }
            String subjectIriString = iriOf(row.get(), "s").getIRIString();
            // Never applies the legacy-literal fallback (see class javadoc "Legacy fallback never
            // reaches the read-modify-write path") - findCurrentByCode's structured lists stay
            // truthfully empty when nothing structured exists.
            Adr adr = assembleAdr(handle, subjectIriString, code, displayLocale, false);
            if (adr == null) {
                return Optional.empty();
            }
            String head = row.get().getValue("head")
                    .filter(IRI.class::isInstance)
                    .map(value -> ((IRI) value).getIRIString())
                    .orElse(null);
            String subject = SparqlTerms.iriRef(subjectIriString);
            Set<String> nameTags = allLanguageTags(handle, subject, NAME_PROPERTY);
            Set<String> contextTags = allLanguageTags(handle, subject, CONTEXT_PROPERTY);
            Set<String> decisionTags = allLanguageTags(handle, subject, DECISION_PROPERTY);
            Set<String> nameContextDecisionTags = new LinkedHashSet<>();
            nameContextDecisionTags.addAll(nameTags);
            nameContextDecisionTags.addAll(contextTags);
            nameContextDecisionTags.addAll(decisionTags);
            return Optional.of(new CurrentAdr(adr, head,
                    displayLocale.select(readNameContextDecision(handle, subject).name())
                            .map(LocalizedLiteral::languageTag).orElse(null),
                    displayLocale.select(readNameContextDecision(handle, subject).context())
                            .map(LocalizedLiteral::languageTag).orElse(null),
                    displayLocale.select(readNameContextDecision(handle, subject).decision())
                            .map(LocalizedLiteral::languageTag).orElse(null),
                    nameContextDecisionTags,
                    childLanguageByPosition(handle, subject, CONSEQUENCE_PROPERTY, CONSEQUENCE_STATEMENT_PROPERTY),
                    childLanguageByPosition(handle, subject, CONSIDERED_OPTION_PROPERTY, OPTION_RATIONALE_PROPERTY),
                    allLanguageTagsByPosition(handle, subject, CONSEQUENCE_PROPERTY, CONSEQUENCE_STATEMENT_PROPERTY),
                    allLanguageTagsByPosition(
                            handle, subject, CONSIDERED_OPTION_PROPERTY, OPTION_RATIONALE_PROPERTY)));
        }
    }

    /**
     * Every <em>tagged</em> language {@code subject} carries on {@code predicateIri}, for the
     * new-variant check. An untagged literal contributes nothing: {@link
     * de.hauschel.arknet.kernel.LanguageTag#resolveWriteLanguage} never resolves to {@code null}, so
     * the set this feeds ({@link AdrRepository.CurrentAdr#nameContextDecisionLanguages()}) is never
     * queried for {@code null} membership - and {@link Set#copyOf} rejects a {@code null} element
     * outright, so admitting one here would crash every read of a decision with an untagged
     * name/context/decision literal (store-first, ADR-005, or written before this issue).
     */
    private Set<String> allLanguageTags(DatasetHandle handle, String subject, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + ADR_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o } }";
        Set<String> tags = new LinkedHashSet<>();
        handle.sparqlQuery().select(query).forEach(row -> {
            String tag = canonicalizeLenient(literalOf(row, "o").getLanguageTag().orElse(null));
            if (tag != null) {
                tags.add(tag);
            }
        });
        return tags;
    }

    /**
     * The BCP-47 tag currently selected (by {@link #displayLocale}) for each existing child position
     * on {@code textPredicate}, for {@link #findCurrentByCode}'s touched/pass-through language
     * resolution - mirrors {@code KognioRdfRequirementRepository}'s
     * {@code acceptanceCriteriaLanguageByPosition}.
     */
    private Map<Integer, String> childLanguageByPosition(DatasetHandle handle, String subject,
            String childEdgePredicate, String textPredicate) {
        String query = "SELECT ?position ?text WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + childEdgePredicate + "> ?child . "
                + "?child <" + POSITION_PROPERTY + "> ?position ; <" + textPredicate + "> ?text } }";
        Map<Integer, List<LocalizedLiteral>> candidatesByPosition = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> {
            int position = Integer.parseInt(literalOf(row, "position").getLexicalForm());
            candidatesByPosition.computeIfAbsent(position, key -> new ArrayList<>())
                    .add(localizedLiteralOf(row, "text"));
        });
        Map<Integer, String> result = new LinkedHashMap<>();
        candidatesByPosition.forEach((position, candidates) -> displayLocale.select(candidates)
                .ifPresent(selected -> result.put(position, selected.languageTag())));
        return result;
    }

    /**
     * {@link #allLanguageTags} grouped by {@code arknet:position} instead of collected flat - every
     * <em>tagged</em> language a position's {@code textPredicate} currently carries (not just the one
     * {@link #displayLocale} would select), for
     * {@link AdrRepository.CurrentAdr#consequenceLanguagesByPosition()}/{@code optionLanguagesByPosition()}'s
     * per-position new-variant check (kogn-io/arknet#357's follow-up to {@code Adr#withConsequenceCorrections}/
     * {@code #withConsideredOptionCorrections}). Runs the same query {@link #childLanguageByPosition}
     * already issues for the very same predicate pair, one row per existing language variant - the set
     * this method groups from is already being read, not newly fetched.
     */
    private Map<Integer, Set<String>> allLanguageTagsByPosition(DatasetHandle handle, String subject,
            String childEdgePredicate, String textPredicate) {
        String query = "SELECT ?position ?text WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + childEdgePredicate + "> ?child . "
                + "?child <" + POSITION_PROPERTY + "> ?position ; <" + textPredicate + "> ?text } }";
        Map<Integer, Set<String>> byPosition = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> {
            int position = Integer.parseInt(literalOf(row, "position").getLexicalForm());
            String tag = canonicalizeLenient(literalOf(row, "text").getLanguageTag().orElse(null));
            if (tag != null) {
                byPosition.computeIfAbsent(position, key -> new LinkedHashSet<>()).add(tag);
            }
        });
        return byPosition;
    }

    @Override
    public List<Adr> findAll(ProjectId projectId, String requestedDisplayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = withRequestedOverride(requestedDisplayLocale);

        String query = "SELECT ?s WHERE { GRAPH <" + ADR_GRAPH + "> { ?s a <" + ADR_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . FILTER(isIRI(?s)) } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<String> subjects = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "s").getIRIString())
                    .distinct()
                    .toList();
            List<Adr> result = new ArrayList<>();
            for (String subjectIriString : subjects) {
                AdrCode code = codeFor(handle, subjectIriString);
                if (code == null) {
                    continue;
                }
                Adr adr = assembleAdr(handle, subjectIriString, code, effective, true);
                if (adr != null) {
                    result.add(adr);
                }
            }
            return List.copyOf(result);
        }
    }

    /**
     * Reads every recorded decision's business code straight off {@code dcterms:identifier}, without
     * joining a single one of the optional or scalar fields {@link #assembleAdr} requires and
     * {@link #toAdrOrNull} can therefore reject - the whole point (kogn-io/arknet#359, see
     * {@link AdrRepository#findAllCodes}'s own javadoc). Deduplicated, since a store-first subject
     * could in principle carry two {@code dcterms:identifier} triples ({@code ashapes:ADR-identifier}
     * enforces {@code sh:maxCount 1} only at write time); {@link #nextCode} only ever wants the
     * highest running number, so which of two identical duplicates survives does not matter.
     */
    @Override
    public List<AdrCode> findAllCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "?s a <" + ADR_TYPE + "> . ?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "FILTER(isIRI(?s)) } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .distinct()
                    .map(AdrCode::new)
                    .toList();
        }
    }

    private AdrCode codeFor(DatasetHandle handle, String subjectIriString) {
        String subject = SparqlTerms.iriRef(subjectIriString);
        String query = "SELECT ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + IDENTIFIER_PROPERTY + "> ?identifier } }";
        List<String> identifiers = handle.sparqlQuery().select(query)
                .map(row -> literalOf(row, "identifier").getLexicalForm())
                .distinct()
                .toList();
        if (identifiers.isEmpty()) {
            return null;
        }
        if (identifiers.size() > 1) {
            LOG.warn("ADR {}: predicate 'dcterms:identifier' had {} distinct values, using the first",
                    subjectIriString, identifiers.size());
        }
        return new AdrCode(identifiers.get(0));
    }

    private Optional<String> subjectFor(DatasetHandle handle, AdrCode code) {
        String query = "SELECT ?s WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "?s a <" + ADR_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . "
                + "FILTER(isIRI(?s)) } }";
        return handle.sparqlQuery().select(query).findFirst().map(row -> iriOf(row, "s").getIRIString());
    }

    /**
     * Assembles one decision from its subject IRI: the single-valued fields (status, decisionDate),
     * the multilingual {@code name}/{@code context}/{@code decision} selected via {@code locale}, the
     * structured consequence/considered-option lists (falling back to a synthesised legacy entry only
     * when {@code applyLegacyFallback} and the structured list is empty - see class javadoc), and the
     * five reference lists. Returns {@code null} - logged at {@code WARN} - for any store-first
     * anomaly {@link Adr}'s own constructor would otherwise reject, or a missing mandatory
     * name/context/decision candidate.
     */
    private Adr assembleAdr(DatasetHandle handle, String subjectIriString, AdrCode code, DisplayLocale locale,
            boolean applyLegacyFallback) {
        String subject = SparqlTerms.iriRef(subjectIriString);
        AdrId id = new AdrId(ResourceId.of(subjectIriString));

        AdrStatus status = readStatus(handle, subject);
        if (status == null) {
            LOG.warn("ADR {}: unrecognised or missing arkarch:adrStatus, skipping this decision", subjectIriString);
            return null;
        }
        NameContextDecision literals = readNameContextDecision(handle, subject);
        Optional<LocalizedLiteral> name = locale.select(literals.name());
        Optional<LocalizedLiteral> context = locale.select(literals.context());
        Optional<LocalizedLiteral> decision = locale.select(literals.decision());
        if (name.isEmpty() || context.isEmpty() || decision.isEmpty()) {
            LOG.warn("ADR {}: missing a mandatory name/context/decision candidate, skipping this decision",
                    subjectIriString);
            return null;
        }
        LocalDate decisionDate = decisionDateOf(handle, subject);
        List<Consequence> consequences = readConsequences(handle, subject, locale);
        if (consequences.isEmpty() && applyLegacyFallback) {
            consequences = legacyConsequenceOrNone(handle, subject);
        }
        List<ConsideredOption> consideredOptions = readConsideredOptions(handle, subject, locale);
        if (consideredOptions.isEmpty() && applyLegacyFallback) {
            consideredOptions = legacyConsideredOptionOrNone(handle, subject);
        }
        List<RequirementRef> requirements =
                readRefs(handle.sparqlQuery()::select, subject, ADDRESSES_REQUIREMENT_PROPERTY, RequirementRef::new);
        List<BoundedContextRef> contexts =
                readRefs(handle.sparqlQuery()::select, subject, AFFECTS_CONTEXT_PROPERTY, BoundedContextRef::new);
        List<TermRef> terms = readRefs(handle.sparqlQuery()::select, subject, USES_TERM_PROPERTY, TermRef::new);
        AdrId supersededBy = firstRefOrNull(handle.sparqlQuery()::select, subject, SUPERSEDED_BY_PROPERTY,
                subjectIriString);
        List<AdrId> relatedTo = readRefs(handle.sparqlQuery()::select, subject, RELATED_TO_PROPERTY, AdrId::new);

        return toAdrOrNull(id, code, name.get().value(), status, context.get().value(), decision.get().value(),
                consequences, consideredOptions, decisionDate, requirements, contexts, terms, supersededBy,
                relatedTo);
    }

    /**
     * {@code new Adr(...)}, tolerant of every invariant that constructor enforces: a store-first
     * (ADR-005) violation (a status/supersededBy bi-implication break, a gap in a child list's
     * positions, more than one {@code Chosen} option, ...) is logged at {@code WARN} and skips this
     * one decision instead of taking {@link #findByCode}/{@link #findAll} down with it.
     */
    private static Adr toAdrOrNull(AdrId id, AdrCode code, String name, AdrStatus status, String context,
            String decision, List<Consequence> consequences, List<ConsideredOption> consideredOptions,
            LocalDate decisionDate, List<RequirementRef> requirements, List<BoundedContextRef> contexts,
            List<TermRef> terms, AdrId supersededBy, List<AdrId> relatedTo) {
        try {
            return new Adr(id, code, name, status, context, decision, consequences, consideredOptions,
                    decisionDate, requirements, contexts, terms, supersededBy, relatedTo);
        } catch (IllegalArgumentException e) {
            LOG.warn("ADR {}: {}, skipping this decision", id.value().value(), e.getMessage());
            return null;
        }
    }

    private record NameContextDecision(
            List<LocalizedLiteral> name, List<LocalizedLiteral> context, List<LocalizedLiteral> decision) {
    }

    private NameContextDecision readNameContextDecision(DatasetHandle handle, String subject) {
        return new NameContextDecision(
                readLiterals(handle, subject, NAME_PROPERTY),
                readLiterals(handle, subject, CONTEXT_PROPERTY),
                readLiterals(handle, subject, DECISION_PROPERTY));
    }

    private List<LocalizedLiteral> readLiterals(DatasetHandle handle, String subject, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + ADR_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o } }";
        return handle.sparqlQuery().select(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    private AdrStatus readStatus(DatasetHandle handle, String subject) {
        String query = "SELECT ?status WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + STATUS_PROPERTY + "> ?status } }";
        List<AdrStatus> candidates = handle.sparqlQuery().select(query)
                .map(KognioRdfAdrRepository::statusOf)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            LOG.warn("ADR {}: field 'status' had {} distinct values, using the first", subject, candidates.size());
        }
        return candidates.get(0);
    }

    private LocalDate decisionDateOf(DatasetHandle handle, String subject) {
        String query = "SELECT ?decisionDate WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + DECISION_DATE_PROPERTY + "> ?decisionDate } }";
        return handle.sparqlQuery().select(query)
                .findFirst()
                .map(row -> literalOrNull(row, "decisionDate"))
                .map(lexical -> {
                    try {
                        return LocalDate.parse(lexical);
                    } catch (DateTimeParseException e) {
                        LOG.warn("ignoring unparseable arkarch:decisionDate '{}'", lexical);
                        return null;
                    }
                })
                .orElse(null);
    }

    // ---- consequence reading --------------------------------------------------------------

    private record ConsequenceAssembly(int position, ConsequenceType type, List<LocalizedLiteral> statementCandidates) {
    }

    private List<Consequence> readConsequences(DatasetHandle handle, String subject, DisplayLocale locale) {
        String query = "SELECT ?c ?position ?type ?statement WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + CONSEQUENCE_PROPERTY + "> ?c . "
                + "?c <" + POSITION_PROPERTY + "> ?position ; <" + CONSEQUENCE_TYPE_PROPERTY + "> ?type ; "
                + "<" + CONSEQUENCE_STATEMENT_PROPERTY + "> ?statement } FILTER(isIRI(?c)) }";
        Map<String, Integer> positionByChild = new LinkedHashMap<>();
        Map<String, List<ConsequenceType>> typeByChild = new LinkedHashMap<>();
        Map<String, List<LocalizedLiteral>> textsByChild = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> {
            String childIri = iriOf(row, "c").getIRIString();
            positionByChild.putIfAbsent(childIri, Integer.parseInt(literalOf(row, "position").getLexicalForm()));
            row.getValue("type").filter(IRI.class::isInstance)
                    .map(value -> consequenceTypeFromIri(((IRI) value).getIRIString()))
                    .filter(Objects::nonNull)
                    .ifPresent(type -> typeByChild.computeIfAbsent(childIri, key -> new ArrayList<>()).add(type));
            textsByChild.computeIfAbsent(childIri, key -> new ArrayList<>()).add(localizedLiteralOf(row, "statement"));
        });
        List<ConsequenceAssembly> assemblies = positionByChild.entrySet().stream()
                .map(entry -> new ConsequenceAssembly(entry.getValue(),
                        firstDistinctOrNull(typeByChild.getOrDefault(entry.getKey(), List.of())),
                        textsByChild.get(entry.getKey())))
                .sorted(Comparator.comparingInt(ConsequenceAssembly::position))
                .toList();
        List<Consequence> result = new ArrayList<>();
        for (ConsequenceAssembly assembly : assemblies) {
            if (assembly.type() == null) {
                continue;
            }
            locale.select(assembly.statementCandidates())
                    .map(LocalizedLiteral::value)
                    .filter(text -> !text.isBlank())
                    .ifPresent(text -> result.add(new Consequence(assembly.position(), text, assembly.type())));
        }
        return result;
    }

    // ---- considered-option reading ---------------------------------------------------------

    private record OptionAssembly(int position, OptionOutcome outcome, List<LocalizedLiteral> nameCandidates,
            List<LocalizedLiteral> rationaleCandidates) {
    }

    private List<ConsideredOption> readConsideredOptions(DatasetHandle handle, String subject, DisplayLocale locale) {
        String structureQuery = "SELECT ?o ?position ?outcome WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + CONSIDERED_OPTION_PROPERTY + "> ?o . "
                + "?o <" + POSITION_PROPERTY + "> ?position ; <" + OPTION_OUTCOME_PROPERTY + "> ?outcome } "
                + "FILTER(isIRI(?o)) }";
        String namesQuery = "SELECT ?o ?name WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + CONSIDERED_OPTION_PROPERTY + "> ?o . ?o <" + NAME_PROPERTY + "> ?name } "
                + "FILTER(isIRI(?o)) }";
        String rationalesQuery = "SELECT ?o ?rationale WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + CONSIDERED_OPTION_PROPERTY + "> ?o . "
                + "?o <" + OPTION_RATIONALE_PROPERTY + "> ?rationale } FILTER(isIRI(?o)) }";

        Map<String, Integer> positionByChild = new LinkedHashMap<>();
        Map<String, List<OptionOutcome>> outcomeByChild = new LinkedHashMap<>();
        handle.sparqlQuery().select(structureQuery).forEach(row -> {
            String childIri = iriOf(row, "o").getIRIString();
            positionByChild.putIfAbsent(childIri, Integer.parseInt(literalOf(row, "position").getLexicalForm()));
            row.getValue("outcome").filter(IRI.class::isInstance)
                    .map(value -> optionOutcomeFromIri(((IRI) value).getIRIString()))
                    .filter(Objects::nonNull)
                    .ifPresent(outcome -> outcomeByChild.computeIfAbsent(childIri, key -> new ArrayList<>())
                            .add(outcome));
        });
        Map<String, List<LocalizedLiteral>> namesByChild = new LinkedHashMap<>();
        handle.sparqlQuery().select(namesQuery).forEach(row -> namesByChild
                .computeIfAbsent(iriOf(row, "o").getIRIString(), key -> new ArrayList<>())
                .add(localizedLiteralOf(row, "name")));
        Map<String, List<LocalizedLiteral>> rationalesByChild = new LinkedHashMap<>();
        handle.sparqlQuery().select(rationalesQuery).forEach(row -> rationalesByChild
                .computeIfAbsent(iriOf(row, "o").getIRIString(), key -> new ArrayList<>())
                .add(localizedLiteralOf(row, "rationale")));

        List<OptionAssembly> assemblies = positionByChild.entrySet().stream()
                .map(entry -> new OptionAssembly(entry.getValue(),
                        firstDistinctOrNull(outcomeByChild.getOrDefault(entry.getKey(), List.of())),
                        namesByChild.getOrDefault(entry.getKey(), List.of()),
                        rationalesByChild.getOrDefault(entry.getKey(), List.of())))
                .sorted(Comparator.comparingInt(OptionAssembly::position))
                .toList();
        List<ConsideredOption> result = new ArrayList<>();
        for (OptionAssembly assembly : assemblies) {
            if (assembly.outcome() == null) {
                continue;
            }
            Optional<LocalizedLiteral> name = locale.select(assembly.nameCandidates());
            Optional<LocalizedLiteral> rationale = locale.select(assembly.rationaleCandidates());
            if (name.isEmpty() || rationale.isEmpty()) {
                continue;
            }
            result.add(new ConsideredOption(
                    assembly.position(), name.get().value(), rationale.get().value(), assembly.outcome()));
        }
        return result;
    }

    private static <T> T firstDistinctOrNull(List<T> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(0);
    }

    // ---- legacy fallback -------------------------------------------------------------------

    /**
     * Synthesises a single {@code NEUTRAL} {@link Consequence} at position {@code 1} from a
     * store-first {@code arkarch:adrConsequences} literal, or an empty list if the decision carries
     * neither structured consequences nor that legacy literal. Never persisted - a pure read-time
     * substitution (see class javadoc); {@link #assembleAdr} only calls this when the structured
     * read was empty.
     */
    private List<Consequence> legacyConsequenceOrNone(DatasetHandle handle, String subject) {
        String query = "SELECT ?text WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + CONSEQUENCES_PROPERTY + "> ?text } }";
        return handle.sparqlQuery().select(query).findFirst()
                .map(row -> literalOf(row, "text").getLexicalForm())
                .filter(text -> !text.isBlank())
                .map(text -> List.of(new Consequence(1, text, ConsequenceType.NEUTRAL)))
                .orElse(List.of());
    }

    /**
     * {@link #legacyConsequenceOrNone} for {@code arkarch:adrAlternatives}: synthesises a single,
     * outcome-less {@link ConsideredOption} whose {@code rationale} is the legacy text verbatim and
     * whose {@code name} is a fixed placeholder (the flat literal carries no separate name field).
     */
    private List<ConsideredOption> legacyConsideredOptionOrNone(DatasetHandle handle, String subject) {
        String query = "SELECT ?text WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + ALTERNATIVES_PROPERTY + "> ?text } }";
        return handle.sparqlQuery().select(query).findFirst()
                .map(row -> literalOf(row, "text").getLexicalForm())
                .filter(text -> !text.isBlank())
                .map(text -> List.of(new ConsideredOption(1, LEGACY_OPTION_NAME_PLACEHOLDER, text, null)))
                .orElse(List.of());
    }

    // ---- shared reference helpers ------------------------------------------------------------

    @Override
    public Map<AdrId, AdrCode> findCodesByIds(ProjectId projectId, java.util.Collection<AdrId> ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return Map.of();
        }
        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value().value()))
                .distinct()
                .collect(Collectors.joining(" "));
        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s a <" + ADR_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> ?identifier } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<AdrId, AdrCode> byId = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row ->
                    byId.putIfAbsent(new AdrId(ResourceId.of(iriOf(row, "s").getIRIString())),
                            new AdrCode(literalOf(row, "identifier").getLexicalForm())));
            return Map.copyOf(byId);
        }
    }

    @Override
    public List<AdrCode> findSupersedingCodes(ProjectId projectId, AdrId supersededId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(supersededId, "supersededId");

        String subject = SparqlTerms.iriRef(supersededId.value().value());
        String query = "SELECT ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "{ " + subject + " <" + SUPERSEDED_BY_PROPERTY + "> ?successor . "
                + "?successor <" + IDENTIFIER_PROPERTY + "> ?identifier } "
                + "UNION "
                + "{ ?predecessor <" + SUPERSEDES_PROPERTY + "> " + subject + " . "
                + "?predecessor <" + IDENTIFIER_PROPERTY + "> ?identifier } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .collect(Collectors.toCollection(() -> new TreeSet<>(CODE_BY_RUNNING_NUMBER)))
                    .stream()
                    .map(AdrCode::new)
                    .toList();
        }
    }

    @Override
    public List<AdrCode> findSupersededCodes(ProjectId projectId, AdrId supersedingId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(supersedingId, "supersedingId");

        String subject = SparqlTerms.iriRef(supersedingId.value().value());
        String query = "SELECT ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "{ ?predecessor <" + SUPERSEDED_BY_PROPERTY + "> " + subject + " . "
                + "?predecessor <" + IDENTIFIER_PROPERTY + "> ?identifier } "
                + "UNION "
                + "{ " + subject + " <" + SUPERSEDES_PROPERTY + "> ?predecessor . "
                + "?predecessor <" + IDENTIFIER_PROPERTY + "> ?identifier } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .collect(Collectors.toCollection(() -> new TreeSet<>(CODE_BY_RUNNING_NUMBER)))
                    .stream()
                    .map(AdrCode::new)
                    .toList();
        }
    }

    @Override
    public List<AdrCode> findSupersessionReferrers(ProjectId projectId, AdrId target) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(target, "target");

        String subject = SparqlTerms.iriRef(target.value().value());
        String query = "SELECT ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "{ ?s <" + SUPERSEDES_PROPERTY + "> " + subject + " . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier } "
                + "UNION "
                + "{ ?s <" + SUPERSEDED_BY_PROPERTY + "> " + subject + " . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .collect(Collectors.toCollection(() -> new TreeSet<>(CODE_BY_RUNNING_NUMBER)))
                    .stream()
                    .map(AdrCode::new)
                    .toList();
        }
    }

    @Override
    public List<LegacySupersession> findLegacySupersedesEdges(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?supersedingIdentifier ?supersededIdentifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "?superseding <" + SUPERSEDES_PROPERTY + "> ?superseded . "
                + "?superseding <" + IDENTIFIER_PROPERTY + "> ?supersedingIdentifier . "
                + "?superseded <" + IDENTIFIER_PROPERTY + "> ?supersededIdentifier } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> new LegacySupersession(
                            new AdrCode(literalOf(row, "supersedingIdentifier").getLexicalForm()),
                            new AdrCode(literalOf(row, "supersededIdentifier").getLexicalForm())))
                    .distinct()
                    .toList();
        }
    }

    @Override
    public List<AdrCode> findRelatedCodes(ProjectId projectId, AdrId relatedId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(relatedId, "relatedId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "?s <" + RELATED_TO_PROPERTY + "> " + SparqlTerms.iriRef(relatedId.value().value()) + " . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .collect(Collectors.toCollection(() -> new TreeSet<>(CODE_BY_RUNNING_NUMBER)))
                    .stream()
                    .map(AdrCode::new)
                    .toList();
        }
    }

    private static <T> List<T> readRefs(Function<String, Stream<BindingSet>> selectFn, String subject,
            String predicate, Function<ResourceId, T> wrap) {
        String query = "SELECT ?target WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + predicate + "> ?target } "
                + "FILTER(isIRI(?target)) } ORDER BY ?target";
        return selectFn.apply(query)
                .map(row -> wrap.apply(ResourceId.of(iriOf(row, "target").getIRIString())))
                .distinct()
                .toList();
    }

    private static AdrId firstRefOrNull(Function<String, Stream<BindingSet>> selectFn, String subject,
            String predicate, String subjectIri) {
        List<AdrId> values = readRefs(selectFn, subject, predicate, AdrId::new);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            LOG.warn("ADR {}: predicate '{}' had {} distinct values, returning the first",
                    subjectIri, predicate, values.size());
        }
        return values.get(0);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static int runningNumber(String code) {
        int dash = code.lastIndexOf('-');
        if (dash < 0 || dash == code.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(code.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String statusIriFor(AdrStatus status) {
        return switch (status) {
            case PROPOSED -> ArkarchVocabulary.PROPOSED;
            case ACCEPTED -> ArkarchVocabulary.ACCEPTED;
            case REJECTED -> ArkarchVocabulary.REJECTED;
            case DEPRECATED -> ArkarchVocabulary.DEPRECATED;
            case SUPERSEDED -> ArkarchVocabulary.SUPERSEDED;
        };
    }

    private static AdrStatus statusFromIri(String iri) {
        if (ArkarchVocabulary.PROPOSED.equals(iri)) {
            return AdrStatus.PROPOSED;
        }
        if (ArkarchVocabulary.ACCEPTED.equals(iri)) {
            return AdrStatus.ACCEPTED;
        }
        if (ArkarchVocabulary.REJECTED.equals(iri)) {
            return AdrStatus.REJECTED;
        }
        if (ArkarchVocabulary.DEPRECATED.equals(iri)) {
            return AdrStatus.DEPRECATED;
        }
        if (ArkarchVocabulary.SUPERSEDED.equals(iri)) {
            return AdrStatus.SUPERSEDED;
        }
        return null;
    }

    private static AdrStatus statusOf(BindingSet row) {
        return row.getValue("status")
                .filter(IRI.class::isInstance)
                .map(value -> statusFromIri(((IRI) value).getIRIString()))
                .orElse(null);
    }

    private static String consequenceTypeIriFor(ConsequenceType type) {
        return switch (type) {
            case POSITIVE -> ArkarchVocabulary.POSITIVE;
            case NEGATIVE -> ArkarchVocabulary.NEGATIVE;
            case NEUTRAL -> ArkarchVocabulary.NEUTRAL;
        };
    }

    private static ConsequenceType consequenceTypeFromIri(String iri) {
        if (ArkarchVocabulary.POSITIVE.equals(iri)) {
            return ConsequenceType.POSITIVE;
        }
        if (ArkarchVocabulary.NEGATIVE.equals(iri)) {
            return ConsequenceType.NEGATIVE;
        }
        if (ArkarchVocabulary.NEUTRAL.equals(iri)) {
            return ConsequenceType.NEUTRAL;
        }
        return null;
    }

    private static String optionOutcomeIriFor(OptionOutcome outcome) {
        return switch (outcome) {
            case CHOSEN -> ArkarchVocabulary.CHOSEN;
            case REJECTED -> ArkarchVocabulary.OPTION_REJECTED;
        };
    }

    private static OptionOutcome optionOutcomeFromIri(String iri) {
        if (ArkarchVocabulary.CHOSEN.equals(iri)) {
            return OptionOutcome.CHOSEN;
        }
        if (ArkarchVocabulary.OPTION_REJECTED.equals(iri)) {
            return OptionOutcome.REJECTED;
        }
        return null;
    }

    private static String literalOrNull(BindingSet row, String name) {
        return row.getValue(name)
                .filter(Literal.class::isInstance)
                .map(value -> ((Literal) value).getLexicalForm())
                .orElse(null);
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    private static LocalizedLiteral localizedLiteralOf(BindingSet row, String name) {
        Literal literal = literalOf(row, name);
        return new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null));
    }

    private static RDFTerm termOf(BindingSet row, String name) {
        return row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
