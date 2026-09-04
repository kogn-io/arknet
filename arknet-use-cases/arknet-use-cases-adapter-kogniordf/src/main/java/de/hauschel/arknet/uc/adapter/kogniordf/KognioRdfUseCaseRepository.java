// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.InvalidLanguageTagException;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.uc.application.port.out.RevisionToken;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.ConstraintRef;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.TermRef;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseConcurrentlyModifiedException;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Out-adapter: {@link UseCaseRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF store).
 *
 * <p>Maps a {@link UseCase} to an {@code arkreq:UseCase} whose subject IRI is the opaque
 * {@link UseCaseId} (minted once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory}, never
 * derived from the business code), stored in one named graph shared by all use cases of a
 * project. The {@code dcterms:identifier} triple carries the human-readable
 * {@link UseCaseCode} ({@code UC1}) - identity and label are deliberately different triples on
 * the same subject.</p>
 *
 * <p><strong>Steps are opaque value objects.</strong> Flow steps are <em>separate</em>
 * {@code arkreq:Step} resources whose IRI is an opaque one minted from the same kernel scheme
 * as the use case itself - no ordinal or business key is encoded in the step IRI (the ordering
 * is carried by the integer {@code arkreq:position}, never an {@code rdf:List}). A step has no
 * stable domain identity of its own: it is a value object inside the use-case aggregate,
 * reachable only through the {@code arkreq:mainStep}/{@code arkreq:extensionStep} edges (there
 * is no read entry point by step IRI). Because the whole aggregate is written by
 * replace-by-identity, a step's opaque IRI is minted afresh on every write - clean addressing,
 * not stable identity; that is harmless precisely because nothing ever references a step from
 * the outside. Minting the step node is therefore a persistence-serialization concern of this
 * adapter, whereas the use-case root's identity is minted store-neutrally above the store.</p>
 *
 * <p>The remaining properties are exactly those of the already-merged requirements/use-case
 * ontology: {@code arkreq:useCaseGoal}, {@code arkreq:designScope}, {@code arkreq:trigger},
 * {@code arkreq:useCasePrecondition}, {@code arkreq:useCasePostcondition}, {@code arkreq:primaryActor},
 * {@code arkreq:supportingActor}, {@code arkreq:mainStep}, {@code arkreq:extensionStep},
 * {@code arkreq:position}, {@code arkreq:stepText}, {@code arkreq:stepRealises} and
 * {@code oslc_rm:satisfies}.</p>
 *
 * <p><strong>Requirement/actor references arrive pre-resolved, identity-carrying.</strong>
 * {@link RequirementRef} and {@link ActorRef} carry the referenced
 * resource's opaque subject {@link ResourceId} directly - resolving a human-typed requirement
 * code (e.g. {@code FR-5}) or actor name (e.g. {@code Customer}) against the shared project
 * store, and rejecting an unknown or ambiguous one, is done once by
 * {@code KognioRdfRequirementLookup}/{@code KognioRdfActorLookup} at the moment a use case is
 * written (in the application service), not here on every write. This adapter therefore neither
 * queries the sibling requirements/terms graphs nor re-verifies that a referenced subject still
 * denotes a requirement or an actor; it trusts the identity it was handed, the same way it trusts
 * a use case's own scalar fields without re-resolving them. It still asserts each referenced
 * subject's type ({@code arkreq:Requirement}/{@code arkproc:Actor}) in the SHACL write-gate's
 * validation-only context (see below), because the shapes need that type to fire correctly
 * against a candidate graph that does not itself carry the referenced subject's type triple. The
 * coarse {@code UseCase oslc_rm:satisfies Requirement} edge is derived as the union of the
 * resolved {@code stepRealises} targets.</p>
 *
 * <p><strong>Still lossy for one, narrower case.</strong> Reading {@code primaryActor}/
 * {@code supportingActor}/{@code stepRealises} back filters for IRI-ness only
 * ({@code FILTER(isIRI(...))}), mirroring {@code KognioRdfRequirementRepository#readUsesTerms}:
 * none of the three properties carries an {@code sh:nodeKind} constraint, so a store-first
 * edge may legally target a blank node, which {@link ResourceId} cannot represent. For
 * {@code supportingActor}/{@code stepRealises} such an edge is simply absent from the
 * corresponding list. {@code primaryActor} is a required (non-{@code OPTIONAL}) triple pattern
 * in the scalar read, so filtering it out there instead makes the whole use case unreadable -
 * {@link #readBySubject} returns {@link Optional#empty()}, and {@link #findAll}/
 * {@link #findByCode} treat it as "not found", silently skipping only that one use case rather
 * than failing the whole result. This is unreachable via the MCP tools, which always resolve to
 * a subject IRI before writing.</p>
 *
 * <p><strong>{@code usesTerm}/{@code constrainedBy} (issue #329).</strong> {@link TermRef}/
 * {@link ConstraintRef} carry the referenced term's/constraint's opaque subject identity
 * directly, arriving pre-resolved from {@code KognioRdfTermLookup}/{@code KognioRdfConstraintLookup}
 * the same way {@link RequirementRef}/{@link ActorRef} do - {@link #write} therefore neither
 * queries the sibling terms/constraints graphs to resolve them nor re-verifies that a referenced
 * subject still denotes a term/constraint on every write; it trusts the identity it was handed.
 * It still asserts each referenced term's type ({@code skos:Concept}) in the SHACL write-gate's
 * validation-only context, mirroring {@code KognioRdfRequirementRepository#create}'s own
 * {@code usesTerm} handling exactly.</p>
 *
 * <p><strong>Deliberately NOT mirroring {@code KognioRdfRequirementRepository}'s
 * {@code constraintAssertedContext} (copying a referenced constraint's real triples into the gate
 * context).</strong> That mechanism exists there because the requirements out-adapter's SHACL
 * gate loads the <em>entire</em>, unfiltered {@code requirements-shapes.ttl}
 * ({@code KognioRdfRequirementRepositoryFactory#buildGate}), so {@code rshapes:ConstraintShape}
 * (targeting {@code arkreq:Constraint}) is itself active and would fire - and fail - against a
 * referenced constraint asserted only as a bare, triple-less {@code arkreq:Constraint} node. This
 * adapter's gate ({@link KognioRdfUseCaseRepositoryFactory#buildGate}) instead loads the same file
 * filtered down to only the {@code arkreq:UseCase}/{@code arkreq:Step} node shapes
 * ({@code loadUseCaseShapes}) - {@code ConstraintShape} loses its {@code sh:targetClass} triple in
 * that filtering and becomes untargeted, i.e. inert: a SHACL shape with no target validates
 * nothing, regardless of what triples the gate's merged data graph carries for a node satisfying
 * it. A bare {@code rdf:type arkreq:Constraint} assertion is therefore already sufficient here,
 * exactly like the {@code skos:Concept} assertion above - copying the constraint's real triples
 * would add complexity without changing what actually gets validated.</p>
 *
 * <p>A use case with zero main steps is handled the same way.
 * {@code arkreq:mainStep} is only {@code sh:Warning} severity at {@code sh:minCount 1} (not
 * {@code sh:Violation}), so {@link ShaclWriteGate#enforce} lets a store-first use case
 * through with no {@code arkreq:mainStep} triples at all - {@link UseCase}'s compact constructor
 * rejects an empty {@code steps} list unconditionally. {@link #readBySubject} skips such a use
 * case the same way it skips a blank-node {@code primaryActor}, rather than letting the
 * constructor's exception propagate out of {@link #findAll}/{@link #findByCode} and crash every
 * read for the whole project.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} +
 * {@code dataset}) and {@link SimpleRdf} - it never imports RDF4J or any other
 * backend-specific type. The backend ({@link DatasetLifecycle} implementation) is supplied
 * by the composition root.</p>
 *
 * <p><strong>ProjectId (local, single-user).</strong> Each {@link ProjectId} is mapped
 * 1:1 to a kognio-rdf {@link DatasetId}, so distinct projects are fully isolated datasets.</p>
 *
 * <p><strong>Create vs. update (opaque identity).</strong> Because identity is opaque and
 * minted once, "insert or replace by identity" is no longer one coherent operation. The
 * transactional mechanics of that distinction - the in-transaction {@code contains}/head checks,
 * the SHACL gate, the commit-conflict translation - live in the shared
 * {@link WriteFunnel}, not here: {@link #create} only builds the candidate graph and
 * rejects an existing subject with {@link ResourceAlreadyExistsException} or a colliding business
 * code with {@link DuplicateUseCaseCodeException}; {@link #compareAndUpdate} rejects a missing
 * subject with {@link UseCaseNotFoundException} and a stale {@code expectedHead} with
 * {@link UseCaseConcurrentlyModifiedException}, mirroring
 * {@code KognioRdfRequirementRepository}. There is no unconditional update: every correction to
 * an already-created use case goes through the compare-and-set guard. Either write otherwise
 * replaces the subject and all its derived step resources wholesale - the {@code deleteExisting}
 * query below stays this adapter's own business, since the funnel's write methods only know a
 * generic {@code body}, not the step-following delete a use case's opaque steps need.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate before the write
 * transaction opens, {@link WriteConstraintViolationException} on a violation, nothing
 * persisted - also live in {@link WriteFunnel}; {@link ShaclWriteGate} itself is no longer named
 * here.</p>
 */
public class KognioRdfUseCaseRepository implements UseCaseRepository {

    private static final String ARKREQ_NAMESPACE = "https://w3id.org/arknet/requirements#";
    private static final String ARKPROC_NAMESPACE = "https://w3id.org/arknet/process#";
    private static final String OSLC_RM_NAMESPACE = "http://open-services.net/ns/rm#";

    private static final String USE_CASES_GRAPH = "https://w3id.org/arknet/model/use-cases";

    private static final String USE_CASE_TYPE = ArkreqVocabulary.USE_CASE_TYPE;
    private static final String STEP_TYPE = ArkreqVocabulary.STEP_TYPE;
    private static final String REQUIREMENT_TYPE = ARKREQ_NAMESPACE + "Requirement";
    private static final String ACTOR_TYPE = ARKPROC_NAMESPACE + "Actor";
    private static final String CONCEPT_TYPE = ArkreqVocabulary.CONCEPT_TYPE;
    private static final String CONSTRAINT_TYPE = ARKREQ_NAMESPACE + "Constraint";
    private static final String USE_CASE_GOAL_PROPERTY = ArkreqVocabulary.USE_CASE_GOAL;
    private static final String DESIGN_SCOPE_PROPERTY = ArkreqVocabulary.DESIGN_SCOPE;
    private static final String TRIGGER_PROPERTY = ArkreqVocabulary.TRIGGER;
    private static final String PRECONDITION_PROPERTY = ArkreqVocabulary.USE_CASE_PRECONDITION;
    private static final String POSTCONDITION_PROPERTY = ArkreqVocabulary.USE_CASE_POSTCONDITION;
    private static final String PRIMARY_ACTOR_PROPERTY = ArkreqVocabulary.PRIMARY_ACTOR;
    private static final String SUPPORTING_ACTOR_PROPERTY = ArkreqVocabulary.SUPPORTING_ACTOR;
    private static final String MAIN_STEP_PROPERTY = ArkreqVocabulary.MAIN_STEP;
    private static final String EXTENSION_STEP_PROPERTY = ArkreqVocabulary.EXTENSION_STEP;
    private static final String POSITION_PROPERTY = ARKREQ_NAMESPACE + "position";
    private static final String STEP_TEXT_PROPERTY = ARKREQ_NAMESPACE + "stepText";
    private static final String STEP_REALISES_PROPERTY = ArkreqVocabulary.STEP_REALISES;
    private static final String SATISFIES_PROPERTY = OSLC_RM_NAMESPACE + "satisfies";
    private static final String TITLE_PROPERTY = VocabDct.NAMESPACE + "title";
    private static final String IDENTIFIER_PROPERTY = VocabDct.NAMESPACE + "identifier";
    private static final String USES_TERM_PROPERTY = ArkreqVocabulary.USES_TERM;
    private static final String CONSTRAINED_BY_PROPERTY = ArkreqVocabulary.CONSTRAINED_BY;

    private final DatasetLifecycle lifecycle;
    private final ResourceIdFactory resourceIdFactory;
    private final DisplayLocale displayLocale;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle         the kognio-rdf dataset lifecycle to acquire datasets from (must
     *                          not be {@code null}); used by the read paths, {@link #write}
     *                          delegates its own acquisition to {@code funnel}
     * @param resourceIdFactory mints the opaque IRI of each derived step resource (must not be
     *                          {@code null}); the use-case root's own identity is minted above
     *                          the store and arrives on the {@link UseCase}
     * @param displayLocale     the display-language preference selecting which {@code
     *                          dcterms:title}/{@code arkreq:useCaseGoal}/{@code arkreq:designScope}/
     *                          {@code arkreq:trigger}/{@code arkreq:useCasePrecondition}/
     *                          {@code arkreq:useCasePostcondition}/each main-flow-step's and
     *                          extension-step's {@code arkreq:stepText} the read paths surface for
     *                          a multilingual use case (must not be {@code null})
     * @param funnel            the shared write funnel both {@link #create} and
     *                          {@link #compareAndUpdate} run through - SHACL gate, dataset
     *                          acquisition, the in-transaction existence/head checks and the
     *                          commit-conflict translation (must not be {@code null})
     */
    KognioRdfUseCaseRepository(DatasetLifecycle lifecycle, ResourceIdFactory resourceIdFactory,
            DisplayLocale displayLocale, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, UseCase useCase, String language) {
        Objects.requireNonNull(useCase, "useCase");
        String tag = LanguageTag.canonicalize(language);
        Map<Integer, String> stepTags = new LinkedHashMap<>();
        useCase.steps().forEach(step -> stepTags.put(step.position(), tag));
        Map<Integer, String> extensionTags = new LinkedHashMap<>();
        for (int position = 1; position <= useCase.extensions().size(); position++) {
            extensionTags.put(position, tag);
        }
        write(projectId, useCase, true, null, tag, tag, tag, tag, tag, tag, stepTags, extensionTags, null,
                Integer.MAX_VALUE);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, UseCase updated,
            String titleLanguage, String goalLanguage, String scopeLanguage, String triggerLanguage,
            String preconditionLanguage, String postconditionLanguage,
            Map<Integer, String> stepTextLanguageByPosition, Map<Integer, String> extensionTextLanguageByPosition,
            String defaultLanguage, int stableExtensionPrefixLength) {
        Objects.requireNonNull(stepTextLanguageByPosition, "stepTextLanguageByPosition");
        Objects.requireNonNull(extensionTextLanguageByPosition, "extensionTextLanguageByPosition");
        Map<Integer, String> stepTags = new LinkedHashMap<>();
        stepTextLanguageByPosition.forEach((position, tag) -> stepTags.put(position, canonicalizeLenient(tag)));
        Map<Integer, String> extensionTags = new LinkedHashMap<>();
        extensionTextLanguageByPosition.forEach((position, tag) -> extensionTags.put(position, canonicalizeLenient(tag)));
        write(projectId, updated, false, expectedHead,
                canonicalizeLenient(titleLanguage), canonicalizeLenient(goalLanguage),
                canonicalizeLenient(scopeLanguage), canonicalizeLenient(triggerLanguage),
                canonicalizeLenient(preconditionLanguage), canonicalizeLenient(postconditionLanguage),
                stepTags, extensionTags, canonicalizeLenient(defaultLanguage), stableExtensionPrefixLength);
    }

    /**
     * @param defaultTag the target project's configured default language, canonicalized, or
     *                    {@code null} if it has none/this is a {@link #create} - see the class-
     *                    level "Sweeping a stale untagged sibling" note (mirrors {@code
     *                    KognioRdfRequirementRepository#replaceTriplesForUpdate}'s own parameter)
     * @param stableExtensionPrefixLength see {@link UseCaseRepository#compareAndUpdate}'s own
     *                    parameter of the same name; always {@link Integer#MAX_VALUE} for a
     *                    {@link #create} - there is no prior extensions list to have restructured
     *                    relative to, so every position is stable
     */
    private void write(ProjectId projectId, UseCase useCase, boolean expectAbsent, RevisionToken expectedHead,
            String titleTag, String goalTag, String scopeTag, String triggerTag, String preconditionTag,
            String postconditionTag, Map<Integer, String> stepTagByPosition,
            Map<Integer, String> extensionTagByPosition, String defaultTag, int stableExtensionPrefixLength) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(useCase, "useCase");

        // ResourceId#of validates IRIREF-safety at construction, so useCase.id()'s
        // wrapped IRI is already guaranteed safe to embed here - no separate check needed.
        String subjectIriString = useCase.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);

        // 1. Every actor/requirement reference already carries its resolved identity (see
        //    class-level note), guaranteed IRIREF-safe by ResourceId#of same as
        //    the subject above.
        IRI primaryActorIri = actorIriFor(useCase.primaryActor());
        List<IRI> supportingActorIris = useCase.supportingActors().stream()
                .map(this::actorIriFor)
                .toList();

        // 2. Build the candidate graph. title/goal/scope/trigger/precondition/postcondition are
        //    written as the language-tagged (or, for a null tag, plain untagged) literal named by
        //    their own *Tag parameter - never more than one each, since preserving every other
        //    language variant is the write body's job below, run after this candidate has already
        //    passed the gate.
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(USE_CASE_TYPE));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(useCase.code().value()));
        graph.add(subjectIri, rdf.createIRI(TITLE_PROPERTY), literalOf(useCase.title(), titleTag));
        graph.add(subjectIri, rdf.createIRI(USE_CASE_GOAL_PROPERTY), literalOf(useCase.goal(), goalTag));
        addOptional(graph, subjectIri, DESIGN_SCOPE_PROPERTY, useCase.scope(), scopeTag);
        addOptional(graph, subjectIri, TRIGGER_PROPERTY, useCase.trigger(), triggerTag);
        addOptional(graph, subjectIri, PRECONDITION_PROPERTY, useCase.precondition(), preconditionTag);
        addOptional(graph, subjectIri, POSTCONDITION_PROPERTY, useCase.postcondition(), postconditionTag);
        graph.add(subjectIri, rdf.createIRI(PRIMARY_ACTOR_PROPERTY), primaryActorIri);
        for (IRI supporting : supportingActorIris) {
            graph.add(subjectIri, rdf.createIRI(SUPPORTING_ACTOR_PROPERTY), supporting);
        }

        // 3. Main-flow steps (own opaque resources) + the coarse UC->Requirement satisfies edge.
        //    A step's IRI is minted afresh on every write (class-level note) - newStepIriByPosition
        //    tracks which freshly-minted IRI ended up at which position, so an update's write body
        //    below knows which new subject to re-attach a preserved other-language step-text
        //    variant to (the position is stable across an update; the step's own IRI is not).
        Map<String, IRI> satisfies = new LinkedHashMap<>();
        Map<Integer, IRI> newStepIriByPosition = new LinkedHashMap<>();
        for (Step step : useCase.steps()) {
            IRI stepIri = mintStepIri();
            newStepIriByPosition.put(step.position(), stepIri);
            graph.add(subjectIri, rdf.createIRI(MAIN_STEP_PROPERTY), stepIri);
            graph.add(stepIri, VocabRdf.TYPE, rdf.createIRI(STEP_TYPE));
            graph.add(stepIri, rdf.createIRI(POSITION_PROPERTY),
                    rdf.createLiteral(Integer.toString(step.position()), VocabXsd.INTEGER));
            graph.add(stepIri, rdf.createIRI(STEP_TEXT_PROPERTY),
                    literalOf(step.text(), stepTagByPosition.get(step.position())));
            for (RequirementRef ref : step.realises()) {
                IRI reqIri = requirementIriFor(ref);
                graph.add(stepIri, rdf.createIRI(STEP_REALISES_PROPERTY), reqIri);
                satisfies.putIfAbsent(reqIri.getIRIString(), reqIri);
            }
        }
        for (IRI reqIri : satisfies.values()) {
            graph.add(subjectIri, rdf.createIRI(SATISFIES_PROPERTY), reqIri);
        }

        // 4. Extensions: alternative/exception flows as opaque extensionStep resources - the same
        //    Step-shaped RDF structure as main-flow steps, tagged per position the same way
        //    (newExtensionStepIriByPosition mirrors newStepIriByPosition above, for the same
        //    re-minted-IRI/stable-position reason).
        Map<Integer, IRI> newExtensionStepIriByPosition = new LinkedHashMap<>();
        int extensionPosition = 1;
        for (String extension : useCase.extensions()) {
            IRI stepIri = mintStepIri();
            newExtensionStepIriByPosition.put(extensionPosition, stepIri);
            graph.add(subjectIri, rdf.createIRI(EXTENSION_STEP_PROPERTY), stepIri);
            graph.add(stepIri, VocabRdf.TYPE, rdf.createIRI(STEP_TYPE));
            graph.add(stepIri, rdf.createIRI(POSITION_PROPERTY),
                    rdf.createLiteral(Integer.toString(extensionPosition), VocabXsd.INTEGER));
            graph.add(stepIri, rdf.createIRI(STEP_TEXT_PROPERTY),
                    literalOf(extension, extensionTagByPosition.get(extensionPosition)));
            extensionPosition++;
        }

        // 5. usesTerm/constrainedBy (issue #329): both reference resolved identities the same way
        //    primaryActor/supportingActor/stepRealises do (class-level note) - one edge per
        //    reference, no re-resolution here.
        List<IRI> termIris = useCase.usesTerms().stream().map(this::termIriFor).toList();
        for (IRI termIri : termIris) {
            graph.add(subjectIri, rdf.createIRI(USES_TERM_PROPERTY), termIri);
        }
        List<IRI> constraintIris = useCase.constrainedBy().stream().map(this::constraintIriFor).toList();
        for (IRI constraintIri : constraintIris) {
            graph.add(subjectIri, rdf.createIRI(CONSTRAINED_BY_PROPERTY), constraintIri);
        }

        // 6. The shapes carry sh:class constraints on primaryActor (arkproc:Actor),
        //    stepRealises (arkreq:Requirement), usesTerm (skos:Concept) and constrainedBy
        //    (arkreq:Constraint). The type triples for those referenced nodes live in the
        //    sibling requirements/terms/constraints graphs, not in this candidate graph.
        //    They are handed to the funnel's gate as a validation-only asserted context (never
        //    persisted here). This is safe: the reference was already proven to exist and be
        //    of the right kind at the moment it was resolved (KognioRdfRequirementLookup /
        //    KognioRdfActorLookup / KognioRdfTermLookup / KognioRdfConstraintLookup, called once
        //    from the application service) - the lookup, not the shape, is what keeps the edge
        //    non-dangling; this adapter no longer re-verifies it. A bare arkreq:Constraint type
        //    assertion is enough for constrainedBy here (class-level note on why this
        //    deliberately does not mirror KognioRdfRequirementRepository#constraintAssertedContext).
        Graph assertedContext = rdf.createGraph();
        assertedContext.add(primaryActorIri, VocabRdf.TYPE, rdf.createIRI(ACTOR_TYPE));
        for (IRI supporting : supportingActorIris) {
            assertedContext.add(supporting, VocabRdf.TYPE, rdf.createIRI(ACTOR_TYPE));
        }
        for (IRI reqIri : satisfies.values()) {
            assertedContext.add(reqIri, VocabRdf.TYPE, rdf.createIRI(REQUIREMENT_TYPE));
        }
        for (IRI termIri : termIris) {
            assertedContext.add(termIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
        }
        for (IRI constraintIri : constraintIris) {
            assertedContext.add(constraintIri, VocabRdf.TYPE, rdf.createIRI(CONSTRAINT_TYPE));
        }

        // The step IRIs are opaque and not under the use-case IRI, so the old
        // "delete everything whose subject STRSTARTS the use-case IRI" no longer reaches
        // them. Delete the use-case subject's own triples and, following the
        // mainStep/extensionStep edges, each step resource's triples. Only ever run by the
        // update branch below - a create() by construction has no prior triples to delete.
        String deleteExisting = "DELETE { GRAPH <" + USE_CASES_GRAPH + "> { ?s ?p ?o } } WHERE { "
                + "GRAPH <" + USE_CASES_GRAPH + "> { "
                + "{ " + subject + " ?p ?o . BIND(" + subject + " AS ?s) } UNION "
                + "{ " + subject + " (<" + MAIN_STEP_PROPERTY + ">|<" + EXTENSION_STEP_PROPERTY
                + ">) ?s . ?s ?p ?o } } }";
        IRI graphIri = rdf.createIRI(USE_CASES_GRAPH);

        if (expectAbsent) {
            funnel.create(new DatasetId(projectId.value()), USE_CASES_GRAPH, subjectIriString,
                    useCase.code().value(), graph, assertedContext,
                    () -> new ResourceAlreadyExistsException(projectId, useCase.id().value()),
                    () -> new DuplicateUseCaseCodeException(projectId, useCase.code()),
                    tx -> tx.add(graphIri, graph));
        } else {
            funnel.compareAndUpdate(new DatasetId(projectId.value()), USE_CASES_GRAPH, subjectIriString,
                    expectedHead == null ? null : expectedHead.value(), graph, assertedContext,
                    () -> new UseCaseNotFoundException(projectId, useCase.code()),
                    () -> new UseCaseConcurrentlyModifiedException(projectId, useCase.code()),
                    tx -> {
                        // Capture usesTerm/constrainedBy edges deleteExisting is about to wipe but
                        // that UseCase#usesTerms()/#constrainedBy() (and therefore graph, built
                        // from useCase.usesTerms()/constrainedBy() above) could never have carried
                        // in the first place: both shapes carry sh:nodeKind sh:IRI, but that only
                        // guards this adapter's own writes - a store-first edge may
                        // still target a blank node, which ResourceId cannot represent - read()
                        // below excludes it the same way (FILTER(isIRI(...))). Mirrors
                        // KognioRdfRequirementRepository#replaceTriplesForUpdate's
                        // unjoinableUsesTerms/unjoinableConstrainedBy capture exactly.
                        String selectUnjoinableUsesTerms = "SELECT ?term WHERE { "
                                + "GRAPH <" + USE_CASES_GRAPH + "> { " + subject + " <" + USES_TERM_PROPERTY
                                + "> ?term } FILTER(!isIRI(?term)) }";
                        String selectUnjoinableConstrainedBy = "SELECT ?constraint WHERE { "
                                + "GRAPH <" + USE_CASES_GRAPH + "> { " + subject + " <" + CONSTRAINED_BY_PROPERTY
                                + "> ?constraint } FILTER(!isIRI(?constraint)) }";
                        List<RDFTerm> unjoinableUsesTerms =
                                tx.select(selectUnjoinableUsesTerms).map(row -> termOf(row, "term")).toList();
                        List<RDFTerm> unjoinableConstrainedBy = tx.select(selectUnjoinableConstrainedBy)
                                .map(row -> termOf(row, "constraint"))
                                .toList();
                        // Capture what deleteExisting is about to wipe but graph is not itself
                        // rewriting: every title/goal/scope/trigger/precondition/postcondition
                        // literal whose language tag differs from its own *Tag parameter, and
                        // every main-flow-step/extension-step text literal (keyed by POSITION, not
                        // step IRI - the old step subject is being deleted regardless) whose tag
                        // differs from that position's stepTagByPosition/extensionTagByPosition
                        // entry. Mirrors KognioRdfRequirementRepository#replaceTriplesForUpdate's
                        // otherLanguageLiterals capture, just read inline here (the use-case
                        // adapter has no equivalent shared helper method to call into). defaultTag
                        // additionally sweeps a stale untagged sibling whenever the field/step's
                        // written tag equals the project's default (issue #258) - see
                        // otherLanguageLiterals/otherLanguageStepTexts's own defaultTag javadoc.
                        List<Literal> preservedTitles =
                                otherLanguageLiterals(tx, subject, TITLE_PROPERTY, titleTag, defaultTag);
                        List<Literal> preservedGoals =
                                otherLanguageLiterals(tx, subject, USE_CASE_GOAL_PROPERTY, goalTag, defaultTag);
                        List<Literal> preservedScopes =
                                otherLanguageLiterals(tx, subject, DESIGN_SCOPE_PROPERTY, scopeTag, defaultTag);
                        List<Literal> preservedTriggers =
                                otherLanguageLiterals(tx, subject, TRIGGER_PROPERTY, triggerTag, defaultTag);
                        List<Literal> preservedPreconditions = otherLanguageLiterals(
                                tx, subject, PRECONDITION_PROPERTY, preconditionTag, defaultTag);
                        List<Literal> preservedPostconditions = otherLanguageLiterals(
                                tx, subject, POSTCONDITION_PROPERTY, postconditionTag, defaultTag);
                        Map<Integer, List<Literal>> preservedStepTextsByPosition = otherLanguageStepTexts(
                                tx, subject, MAIN_STEP_PROPERTY, stepTagByPosition, defaultTag, Integer.MAX_VALUE);
                        // Beyond stableExtensionPrefixLength, this call's extensions list inserted,
                        // removed or reordered items relative to what was last read - position
                        // numbering no longer identifies "the same" extension across old and new, so
                        // preserving an old position's other-language variant there would graft it
                        // onto whatever unrelated content the write now puts at that position (see
                        // UseCaseRepository#compareAndUpdate's own javadoc). Positions up to and
                        // including stableExtensionPrefixLength are untouched by the restructure and
                        // keep ordinary position-based preservation.
                        Map<Integer, List<Literal>> preservedExtensionTextsByPosition = otherLanguageStepTexts(
                                tx, subject, EXTENSION_STEP_PROPERTY, extensionTagByPosition, defaultTag,
                                stableExtensionPrefixLength);

                        tx.update(deleteExisting);
                        tx.add(graphIri, graph);

                        // Re-attach the unjoinable usesTerm/constrainedBy edges only after the gate
                        // has already run and the rewritten graph is committed - a blank-node
                        // target cannot appear in assertedContext (built from useCase.usesTerms()/
                        // constrainedBy() only, which are always IRIs by construction), so feeding
                        // it there would fail the shape's sh:class constraint and block every
                        // future update of this use case. Appending it here instead is safe
                        // precisely because nothing new is introduced - the edge already existed in
                        // the store and is carried forward untouched, keeping its identity across
                        // this delete-and-readd cycle (deleteExisting only removes triples whose
                        // subject is the use case, never the target node's own triples).
                        if (!unjoinableUsesTerms.isEmpty() || !unjoinableConstrainedBy.isEmpty()) {
                            Graph preservedEdges = rdf.createGraph();
                            for (RDFTerm termNode : unjoinableUsesTerms) {
                                preservedEdges.add(subjectIri, rdf.createIRI(USES_TERM_PROPERTY), termNode);
                            }
                            for (RDFTerm constraintNode : unjoinableConstrainedBy) {
                                preservedEdges.add(subjectIri, rdf.createIRI(CONSTRAINED_BY_PROPERTY), constraintNode);
                            }
                            tx.add(graphIri, preservedEdges);
                        }

                        // Re-attach only after the gate has already run and the rewritten graph is
                        // committed - the preserved literals are not new assertions, they already
                        // existed in the store and are carried forward untouched (mirrors the
                        // requirements adapter's usesTerm/language-variant preservation). A step's
                        // preserved text variant re-attaches to the FRESHLY minted step IRI at the
                        // same position (newStepIriByPosition/newExtensionStepIriByPosition) - the
                        // old step subject no longer exists after deleteExisting, but nothing
                        // outside this adapter ever referenced it (class-level "opaque value
                        // object" note), so moving a preserved literal to the new subject at the
                        // same position is safe.
                        if (!preservedTitles.isEmpty() || !preservedGoals.isEmpty() || !preservedScopes.isEmpty()
                                || !preservedTriggers.isEmpty() || !preservedPreconditions.isEmpty()
                                || !preservedPostconditions.isEmpty() || !preservedStepTextsByPosition.isEmpty()
                                || !preservedExtensionTextsByPosition.isEmpty()) {
                            Graph preserved = rdf.createGraph();
                            for (Literal title : preservedTitles) {
                                preserved.add(subjectIri, rdf.createIRI(TITLE_PROPERTY), title);
                            }
                            for (Literal goal : preservedGoals) {
                                preserved.add(subjectIri, rdf.createIRI(USE_CASE_GOAL_PROPERTY), goal);
                            }
                            for (Literal scope : preservedScopes) {
                                preserved.add(subjectIri, rdf.createIRI(DESIGN_SCOPE_PROPERTY), scope);
                            }
                            for (Literal trigger : preservedTriggers) {
                                preserved.add(subjectIri, rdf.createIRI(TRIGGER_PROPERTY), trigger);
                            }
                            for (Literal precondition : preservedPreconditions) {
                                preserved.add(subjectIri, rdf.createIRI(PRECONDITION_PROPERTY), precondition);
                            }
                            for (Literal postcondition : preservedPostconditions) {
                                preserved.add(subjectIri, rdf.createIRI(POSTCONDITION_PROPERTY), postcondition);
                            }
                            preservedStepTextsByPosition.forEach((position, texts) -> {
                                IRI newStepIri = newStepIriByPosition.get(position);
                                if (newStepIri != null) {
                                    for (Literal text : texts) {
                                        preserved.add(newStepIri, rdf.createIRI(STEP_TEXT_PROPERTY), text);
                                    }
                                }
                            });
                            preservedExtensionTextsByPosition.forEach((position, texts) -> {
                                IRI newStepIri = newExtensionStepIriByPosition.get(position);
                                if (newStepIri != null) {
                                    for (Literal text : texts) {
                                        preserved.add(newStepIri, rdf.createIRI(STEP_TEXT_PROPERTY), text);
                                    }
                                }
                            });
                            tx.add(graphIri, preserved);
                        }
                    });
        }
    }

    /**
     * Reads every existing literal of {@code subject} on {@code predicateIri} whose language tag
     * differs from {@code writtenTag}, inside the live write transaction, before
     * {@code deleteExisting} would otherwise wipe them along with the rest of the subject's
     * triples. Mirrors {@code KognioRdfTermRepository#deleteTriplesOfLanguage}'s scoping logic,
     * inverted into a capture-and-reattach rather than a targeted delete, because this class's
     * {@code deleteExisting} is an unconditional whole-subject-and-steps wipe, not a per-predicate
     * patch.
     *
     * @param writtenTag the tag of the literal {@code graph} is about to (re)write for this
     *                   predicate, or {@code null} for untagged - excluded here since it is not
     *                   being preserved, it is being replaced
     * @param defaultTag the target project's configured default language, canonicalized, or
     *                   {@code null} if it has none - when it equals {@code writtenTag}, an
     *                   existing <em>untagged</em> literal is excluded here too (issue #258),
     *                   swept away as a stale duplicate of the literal now being written under its
     *                   proper default tag rather than preserved as a genuine other-language
     *                   variant (mirrors {@code KognioRdfRequirementRepository#otherLanguageLiterals}'s
     *                   own {@code defaultTag} parameter)
     */
    private List<Literal> otherLanguageLiterals(
            DatasetTx tx, String subject, String predicateIri, String writtenTag, String defaultTag) {
        String query = "SELECT ?o WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
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
     * {@link LanguageTag#canonicalize(String)}, but falls back to {@code null} (untagged) instead
     * of throwing {@link InvalidLanguageTagException} - used at the places in this class that
     * handle a tag which may be a verbatim pass-through of whatever is already sitting in the store
     * ({@code UseCaseService#updateWithOptimisticRetry} reads {@code current.titleLanguage()}/
     * {@code current.goalLanguage()}/{@code current.stepTextLanguageByPosition()} straight off
     * {@link LocalizedLiteral#languageTag()}, the raw tag as read from the store, never
     * re-validated), rather than a freshly caller-supplied value already validated before it
     * reached this adapter (as {@link #create}'s {@code language} argument is - that call site
     * stays on the strict {@link LanguageTag#canonicalize(String)}).
     *
     * <p>The fallback is {@code null}, not the raw tag, deliberately: RDF4J's own literal
     * construction ({@code Literals#isValidLanguageTag}, reached from {@link #write}'s SHACL gate)
     * rejects exactly the same not-well-formed tags {@link LanguageTag#canonicalize(String)} does -
     * via the identical {@code Locale.Builder#setLanguageTag} check - so re-embedding the untouched
     * raw tag would still crash the gate a moment later with a different exception, not fix
     * anything. Falling back to {@code null} instead writes the pass-through value as a plain,
     * untagged literal - the one literal form no tag validation ever rejects - so a use case whose
     * store-first title/goal/step-text tag is irreparably malformed becomes editable
     * again (at the cost of that one field's language tag) rather than permanently blocking every
     * future correction, even one that never touches title/goal/step text at all.</p>
     *
     * <p>{@link #otherLanguageLiterals}/{@link #otherLanguageStepTexts} use the same helper for
     * their comparisons, not to fall back on write: canonicalizing the read literal's tag before
     * comparing makes the check robust against case-only drift (raw {@code "en-us"} vs. the now-
     * canonicalized {@code "en-US"} being (re)written would otherwise look like two different
     * languages, preserving the former as a spurious "other" variant and duplicating the literal) -
     * and folding an irreparably malformed existing tag to {@code null} there too keeps it
     * consistent with what {@link #write} is about to write for that same case, rather than
     * preserving it as a bogus "other" variant.</p>
     */
    private static String canonicalizeLenient(String tag) {
        try {
            return LanguageTag.canonicalize(tag);
        } catch (InvalidLanguageTagException e) {
            return null;
        }
    }

    /**
     * {@link #otherLanguageLiterals} for {@code arkreq:stepText}, keyed by {@code edgeProperty}
     * step's {@code arkreq:position} rather than by (about-to-be-deleted) step IRI: a step's own
     * subject is re-minted on every write (class-level note), so what survives an update is the
     * position's <em>other-language text</em>, re-attached to whichever new step IRI ends up at
     * that same position - not the old step IRI itself. Shared by both step flavours this adapter
     * writes - {@code edgeProperty} is {@link #MAIN_STEP_PROPERTY} for the main flow or
     * {@link #EXTENSION_STEP_PROPERTY} for extensions - since both are {@code arkreq:Step}-shaped
     * resources reached the same way, differing only in which edge connects them to the use case.
     *
     * @param edgeProperty the edge from the use-case subject to its steps whose text is being
     *                     preserved: {@link #MAIN_STEP_PROPERTY} or {@link #EXTENSION_STEP_PROPERTY}
     * @param defaultTag the target project's configured default language, canonicalized, or
     *                   {@code null} if it has none - same issue #258 sweep as
     *                   {@link #otherLanguageLiterals}'s own {@code defaultTag}, applied per
     *                   position: a position whose written tag equals {@code defaultTag} sweeps an
     *                   existing untagged step text at that position instead of preserving it
     * @param maxPreservedPosition the highest position (inclusive) preservation is safe for -
     *                   {@link Integer#MAX_VALUE} for main-flow steps, whose positions are always
     *                   stable, or {@code UseCaseRepository#compareAndUpdate}'s
     *                   {@code stableExtensionPrefixLength} for extension steps, whose positions
     *                   beyond a restructure's common prefix no longer identify "the same"
     *                   extension across old and new (see that parameter's own javadoc)
     */
    private Map<Integer, List<Literal>> otherLanguageStepTexts(
            DatasetTx tx, String subject, String edgeProperty, Map<Integer, String> stepTagByPosition,
            String defaultTag, int maxPreservedPosition) {
        String query = "SELECT ?position ?text WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + edgeProperty + "> ?step . "
                + "?step <" + POSITION_PROPERTY + "> ?position ; <" + STEP_TEXT_PROPERTY + "> ?text } }";
        Map<Integer, List<Literal>> byPosition = new LinkedHashMap<>();
        tx.select(query).forEach(row -> {
            int position = Integer.parseInt(literalOf(row, "position").getLexicalForm());
            if (position > maxPreservedPosition) {
                return;
            }
            Literal text = literalOf(row, "text");
            String writtenTag = stepTagByPosition.get(position);
            String existingTag = canonicalizeLenient(text.getLanguageTag().orElse(null));
            boolean sweepUntagged = defaultTag != null && defaultTag.equals(writtenTag) && existingTag == null;
            if (!sweepUntagged && !Objects.equals(existingTag, writtenTag)) {
                byPosition.computeIfAbsent(position, key -> new ArrayList<>()).add(text);
            }
        });
        return byPosition;
    }

    @Override
    public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        DisplayLocale effective = withRequestedOverride(displayLocale);
        String query = "SELECT ?s WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + "?s a <" + USE_CASE_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> \""
                + SparqlTerms.escape(code.value()) + "\" . FILTER(isIRI(?s)) } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Optional<BindingSet> head = handle.sparqlQuery().select(query).findFirst();
            if (head.isEmpty()) {
                return Optional.empty();
            }
            return readBySubject(handle, iriOf(head.get(), "s").getIRIString(), code, effective);
        }
    }

    /**
     * Overrides this repository's own configured {@link #displayLocale}'s {@code requested} tier
     * for one call - shared by {@link #findByCode} and {@link #findAll}, e.g. an explicit
     * {@code uc_get} {@code displayLocale} argument or a project's own default language merged in
     * by the caller ({@code uc_list} has no explicit {@code displayLocale} tool argument of its
     * own to merge against - before {@link #findAll} sees it, issue #281). Mirrors
     * {@code KognioRdfRequirementRepository#withRequestedOverride}/
     * {@code KognioRdfTermRepository#withRequestedOverride}.
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

    @Override
    public List<UseCase> findAll(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = withRequestedOverride(displayLocale);
        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + "?s a <" + USE_CASE_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "FILTER(isIRI(?s)) } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<UseCaseRow> rows = handle.sparqlQuery().select(query)
                    .map(row -> new UseCaseRow(iriOf(row, "s").getIRIString(),
                            new UseCaseCode(literalOf(row, "identifier").getLexicalForm())))
                    .toList();
            List<UseCase> result = new ArrayList<>();
            for (UseCaseRow row : rows) {
                readBySubject(handle, row.subjectIri(), row.code(), effective).ifPresent(result::add);
            }
            return List.copyOf(result);
        }
    }

    /**
     * Reads every recorded use case's {@code dcterms:identifier} straight off the type triple, with
     * no join on anything {@link #buildUseCase} can refuse to materialise on - not
     * {@code dcterms:title}, not {@code arkreq:useCaseGoal}, not the main flow. That is the whole
     * point of the method (see {@link UseCaseRepository#findAllCodes}'s own javadoc, and
     * kogn-io/arknet#360): a store-first use case those reads skip keeps its {@code UCn},
     * so the code has to stay visible to the counter even while the use case itself is not listable.
     *
     * <p>Deduplicated, since a store-first subject could carry two {@code dcterms:identifier}
     * triples - {@code rshapes:UseCase-identifier} enforces {@code sh:maxCount 1} at write time
     * only; the caller derives a maximum, so which of two equal duplicates survives makes no
     * difference.</p>
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
    public List<UseCaseCode> findAllCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + "?s a <" + USE_CASE_TYPE + "> . ?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .distinct()
                    .map(UseCaseCode::new)
                    .toList();
        }
    }

    /**
     * Reads a use case's current state together with its concurrency token (the
     * {@code arkprov:head} revision IRI recorded by the last funnel write) - the read
     * side of the read-modify-write round trip {@link #compareAndUpdate} guards the write side
     * of. Mirrors {@code KognioRdfRequirementRepository#findCurrentByCode}: reuses the same
     * subject lookup {@link #findByCode} does, then pairs the scalar/head read in one query call
     * via {@link #readCurrentBySubject} - one snapshot for the core fields and the head, exactly
     * as {@link #readBySubject} reads the core fields alone. No per-call display-language override
     * here: an internal read-modify-write round trip is not a caller-facing read, so this
     * adapter's own configured {@link #displayLocale} is used (mirrors
     * {@code KognioRdfRequirementRepository#findCurrentByCode}).
     */
    @Override
    public Optional<UseCaseRepository.CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        String query = "SELECT ?s WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + "?s a <" + USE_CASE_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> \""
                + SparqlTerms.escape(code.value()) + "\" . FILTER(isIRI(?s)) } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Optional<BindingSet> head = handle.sparqlQuery().select(query).findFirst();
            if (head.isEmpty()) {
                return Optional.empty();
            }
            return readCurrentBySubject(handle, iriOf(head.get(), "s").getIRIString(), code);
        }
    }

    private record UseCaseRow(String subjectIri, UseCaseCode code) {
    }

    // ---- reading -----------------------------------------------------------------------

    /**
     * Builds the mandatory/optional scalar triple patterns (inside {@code GRAPH <USE_CASES_GRAPH>})
     * shared by {@link #readBySubject} and {@link #readCurrentBySubject}, so both single-use-case
     * read paths query the core fields identically - drift between two near-identical read paths
     * was a real bug more than once in the sibling requirements adapter.
     *
     * <p>{@code FILTER(isIRI(?primaryActor))} mirrors {@link #readSupportingActors}/
     * {@link #readMainStepRealises}: {@code arkreq:primaryActor} carries no {@code sh:nodeKind}
     * constraint, so a store-first edge may legally target a blank node, which
     * {@link ResourceId} cannot represent. Unlike the other two properties, {@code primaryActor}
     * is part of this required (non-{@code OPTIONAL}) triple pattern, so filtering it out here
     * makes the whole scalar query yield no row for such a use case - the caller then treats it
     * as "not found", silently skipping only this one use case rather than crashing the whole
     * result list.</p>
     *
     * <p>{@code title}/{@code goal}/{@code scope}/{@code trigger}/{@code precondition}/
     * {@code postcondition} are all read separately, not joined here - each may now legally
     * carry several language-tagged literals (SKOS-S14-style {@code sh:uniqueLang}), so
     * joining them into this single-row clause would multiply a subject into a row per
     * candidate combination. {@link #readTitles}/{@link #readGoals}/{@link #readScopes}/
     * {@link #readTriggers}/{@link #readPreconditions}/{@link #readPostconditions} read them as
     * their own follow-up queries instead, mirroring {@code KognioRdfRequirementRepository}.</p>
     */
    private static String scalarWhereClause(String subject) {
        return subject + " a <" + USE_CASE_TYPE + "> ; "
                + "<" + PRIMARY_ACTOR_PROPERTY + "> ?primaryActor . "
                + "FILTER(isIRI(?primaryActor)) ";
    }

    private Optional<UseCase> readBySubject(
            DatasetHandle handle, String subjectIriString, UseCaseCode code, DisplayLocale locale) {
        if (!SparqlTerms.isValidIriReference(subjectIriString)) {
            // A syntactically impossible identifier cannot match anything in the store -
            // report "not found" instead of building a malformed SPARQL query.
            return Optional.empty();
        }
        String subject = SparqlTerms.iriRef(subjectIriString);
        String scalarQuery = "SELECT ?primaryActor "
                + "WHERE { GRAPH <" + USE_CASES_GRAPH + "> { " + scalarWhereClause(subject) + "} }";

        Optional<BindingSet> row = handle.sparqlQuery().select(scalarQuery).findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        return buildUseCase(handle, subjectIriString, code, subject, row.get(), locale);
    }

    /**
     * Reads a use case's current state together with its concurrency token: the core scalar
     * fields and the head itself come from one query call - one snapshot, the load-bearing
     * guarantee, mirroring {@code KognioRdfRequirementRepository#findCurrentByCode}.
     * {@code supportingActors}/{@code steps}/{@code extensions} still come from later, independent
     * reads inside {@link #buildUseCase} - safe precisely because a later read can only be
     * fresher, never staler, than the head: a funnel write landing in between moves the head, so
     * the subsequent {@link #compareAndUpdate} then fails its comparison and the caller re-reads
     * instead of overwriting a state it never actually saw.
     */
    private Optional<UseCaseRepository.CurrentUseCase> readCurrentBySubject(
            DatasetHandle handle, String subjectIriString, UseCaseCode code) {
        if (!SparqlTerms.isValidIriReference(subjectIriString)) {
            return Optional.empty();
        }
        String subject = SparqlTerms.iriRef(subjectIriString);
        String scalarQuery = "SELECT ?primaryActor ?head "
                + "WHERE { GRAPH <" + USE_CASES_GRAPH + "> { " + scalarWhereClause(subject) + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + subject + " <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        Optional<BindingSet> row = handle.sparqlQuery().select(scalarQuery).findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Optional<UseCase> useCase = buildUseCase(handle, subjectIriString, code, subject, row.get(), displayLocale);
        if (useCase.isEmpty()) {
            return Optional.empty();
        }
        Optional<LocalizedLiteral> title = displayLocale.select(readTitles(handle, subject));
        Optional<LocalizedLiteral> goal = displayLocale.select(readGoals(handle, subject));
        if (title.isEmpty() || goal.isEmpty()) {
            return Optional.empty();
        }
        Optional<LocalizedLiteral> scope = displayLocale.select(readScopes(handle, subject));
        Optional<LocalizedLiteral> trigger = displayLocale.select(readTriggers(handle, subject));
        Optional<LocalizedLiteral> precondition = displayLocale.select(readPreconditions(handle, subject));
        Optional<LocalizedLiteral> postcondition = displayLocale.select(readPostconditions(handle, subject));
        List<StepAssembly> stepAssemblies = readMainStepAssemblies(handle, subject);
        Map<Integer, String> stepTextLanguageByPosition = toStepLanguages(stepAssemblies, displayLocale);
        List<StepAssembly> extensionAssemblies = readExtensionStepAssemblies(handle, subject);
        Map<Integer, String> extensionTextLanguageByPosition = toStepLanguages(extensionAssemblies, displayLocale);
        RevisionToken head = row.get().getValue("head")
                .filter(IRI.class::isInstance)
                .map(value -> new RevisionToken(((IRI) value).getIRIString()))
                .orElse(null);
        return Optional.of(new UseCaseRepository.CurrentUseCase(useCase.get(), head,
                title.get().languageTag(), goal.get().languageTag(),
                scope.map(LocalizedLiteral::languageTag).orElse(null),
                trigger.map(LocalizedLiteral::languageTag).orElse(null),
                precondition.map(LocalizedLiteral::languageTag).orElse(null),
                postcondition.map(LocalizedLiteral::languageTag).orElse(null),
                stepTextLanguageByPosition, extensionTextLanguageByPosition));
    }

    /** Reads the {@code dcterms:title} candidates of one use case, tagged for {@link DisplayLocale}. */
    private List<LocalizedLiteral> readTitles(DatasetHandle handle, String subject) {
        String query = "SELECT ?o WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + TITLE_PROPERTY + "> ?o } }";
        return handle.sparqlQuery().select(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** {@link #readTitles} for {@code arkreq:useCaseGoal}. */
    private List<LocalizedLiteral> readGoals(DatasetHandle handle, String subject) {
        String query = "SELECT ?o WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + USE_CASE_GOAL_PROPERTY + "> ?o } }";
        return handle.sparqlQuery().select(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** {@link #readTitles} for {@code arkreq:designScope}. */
    private List<LocalizedLiteral> readScopes(DatasetHandle handle, String subject) {
        String query = "SELECT ?o WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + DESIGN_SCOPE_PROPERTY + "> ?o } }";
        return handle.sparqlQuery().select(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** {@link #readTitles} for {@code arkreq:trigger}. */
    private List<LocalizedLiteral> readTriggers(DatasetHandle handle, String subject) {
        String query = "SELECT ?o WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + TRIGGER_PROPERTY + "> ?o } }";
        return handle.sparqlQuery().select(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** {@link #readTitles} for {@code arkreq:useCasePrecondition}. */
    private List<LocalizedLiteral> readPreconditions(DatasetHandle handle, String subject) {
        String query = "SELECT ?o WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + PRECONDITION_PROPERTY + "> ?o } }";
        return handle.sparqlQuery().select(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** {@link #readTitles} for {@code arkreq:useCasePostcondition}. */
    private List<LocalizedLiteral> readPostconditions(DatasetHandle handle, String subject) {
        String query = "SELECT ?o WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + POSTCONDITION_PROPERTY + "> ?o } }";
        return handle.sparqlQuery().select(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /**
     * Builds a {@link UseCase} from {@code row} (the projection of {@link #scalarWhereClause})
     * plus the follow-up reads {@link #readSupportingActors}/{@link #readMainStepAssemblies}/
     * {@link #readExtensionStepAssemblies}/{@link #readTitles}/{@link #readGoals}/
     * {@link #readScopes}/{@link #readTriggers}/{@link #readPreconditions}/
     * {@link #readPostconditions} - shared by {@link #readBySubject} and
     * {@link #readCurrentBySubject} so both build a {@link UseCase} identically. {@code locale}
     * selects one {@code title}/{@code goal}/{@code scope}/{@code trigger}/{@code precondition}/
     * {@code postcondition}/each step's/extension's {@code text} candidate out of however many
     * language-tagged variants exist.
     *
     * <p>Returns {@link Optional#empty()} if this subject carries no {@code dcterms:title}/
     * {@code arkreq:useCaseGoal} literal at all - {@code UseCase-title}/{@code UseCase-goal} carry
     * {@code sh:minCount 1}, so this is unreachable via the MCP tools; a store-first use
     * case missing either is skipped here the same way a use case with zero main steps is.</p>
     */
    private Optional<UseCase> buildUseCase(DatasetHandle handle, String subjectIriString, UseCaseCode code,
            String subject, BindingSet row, DisplayLocale locale) {
        Optional<LocalizedLiteral> title = locale.select(readTitles(handle, subject));
        Optional<LocalizedLiteral> goal = locale.select(readGoals(handle, subject));
        if (title.isEmpty() || goal.isEmpty()) {
            return Optional.empty();
        }
        String scope = locale.select(readScopes(handle, subject)).map(LocalizedLiteral::value).orElse(null);
        String trigger = locale.select(readTriggers(handle, subject)).map(LocalizedLiteral::value).orElse(null);
        String precondition =
                locale.select(readPreconditions(handle, subject)).map(LocalizedLiteral::value).orElse(null);
        String postcondition =
                locale.select(readPostconditions(handle, subject)).map(LocalizedLiteral::value).orElse(null);
        List<ActorRef> supportingActors = readSupportingActors(handle, subject);
        List<StepAssembly> stepAssemblies = readMainStepAssemblies(handle, subject);
        List<Step> steps = toSteps(stepAssemblies, locale);
        if (steps.isEmpty()) {
            // arkreq:mainStep is only sh:Warning severity at sh:minCount 1 (not sh:Violation), so
            // ShaclWriteGate#enforce lets a store-first use case through with zero main
            // steps. UseCase's compact constructor rejects an empty steps list unconditionally -
            // mirror the primaryActor blank-node guard above: skip this one use case instead of
            // letting the constructor throw out of findByCode/findAll for the whole project.
            return Optional.empty();
        }
        if (!hasConsecutiveStepPositions(steps)) {
            // Nothing in SHACL forbids two arkreq:Step nodes under the same mainStep sharing an
            // arkreq:position - uniqueness is only enforced in-process by
            // UseCase.requireConsecutiveStepPositions, and store-first data never runs
            // through that. Mirror the empty-steps guard above rather than letting the
            // constructor's IllegalArgumentException propagate out of findByCode/findAll for the
            // whole project.
            return Optional.empty();
        }
        List<StepAssembly> extensionAssemblies = readExtensionStepAssemblies(handle, subject);
        List<String> extensions = toExtensionTexts(extensionAssemblies, locale);

        return Optional.of(new UseCase(
                new UseCaseId(ResourceId.of(subjectIriString)),
                code,
                title.get().value(),
                goal.get().value(),
                scope,
                trigger,
                new ActorRef(ResourceId.of(iriOf(row, "primaryActor").getIRIString())),
                supportingActors,
                precondition,
                postcondition,
                steps,
                extensions,
                readUsesTerms(handle, subject),
                readConstrainedBy(handle, subject)));
    }

    // ---- usesTerm/constrainedBy reading (issue #329) ------------------------------------

    /**
     * Reads the {@code arkreq:usesTerm} edges of one use case back as term references, ordered by
     * target IRI (RDF has no intrinsic statement order, and {@link UseCase} compares its
     * {@code usesTerms} list positionally). Excludes any edge whose target is not an IRI -
     * {@code usesTerm}'s shape carries {@code sh:nodeKind sh:IRI}, but that only guards this
     * adapter's own writes, so a store-first edge may still target a blank node, which
     * {@link ResourceId} cannot represent; such an edge never appears in
     * {@link UseCase#usesTerms()} but survives a later update via the unjoinable-edge
     * preservation in {@link #write}. Mirrors
     * {@code KognioRdfRequirementRepository#readUsesTerms}.
     */
    private List<TermRef> readUsesTerms(DatasetHandle handle, String subject) {
        String query = "SELECT ?term WHERE { "
                + "GRAPH <" + USE_CASES_GRAPH + "> { " + subject + " <" + USES_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?term)) } ORDER BY ?term";
        return handle.sparqlQuery().select(query)
                .map(row -> new TermRef(ResourceId.of(iriOf(row, "term").getIRIString())))
                .toList();
    }

    /**
     * Reads the {@code oslc_rm:constrainedBy} edges of one use case back as constraint
     * references, ordered by target IRI - mirrors {@link #readUsesTerms} exactly, including the
     * IRI-only filter: {@code constrainedBy}'s shape carries {@code sh:nodeKind sh:IRI}, but that
     * only guards this adapter's own writes, not a store-first edge, so the filter still
     * matters here.
     */
    private List<ConstraintRef> readConstrainedBy(DatasetHandle handle, String subject) {
        String query = "SELECT ?constraint WHERE { "
                + "GRAPH <" + USE_CASES_GRAPH + "> { " + subject + " <" + CONSTRAINED_BY_PROPERTY
                + "> ?constraint } FILTER(isIRI(?constraint)) } ORDER BY ?constraint";
        return handle.sparqlQuery().select(query)
                .map(row -> new ConstraintRef(ResourceId.of(iriOf(row, "constraint").getIRIString())))
                .toList();
    }

    /**
     * Reads the {@code arkreq:supportingActor} edges back as actor references.
     *
     * <p><strong>No longer a join.</strong> The edge's target IRI <em>is</em> the
     * {@link ActorRef} - no join into the sibling terms graph is needed, and none is performed
     * here. {@code FILTER(isIRI(?a))} mirrors
     * {@code KognioRdfRequirementRepository#readUsesTerms}: the property carries no
     * {@code sh:nodeKind} constraint, so a store-first edge may legally target a blank
     * node, which {@link ResourceId} cannot represent - excluded here, unreachable via the MCP
     * tools.</p>
     */
    private List<ActorRef> readSupportingActors(DatasetHandle handle, String subject) {
        String query = "SELECT ?a WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + SUPPORTING_ACTOR_PROPERTY + "> ?a } FILTER(isIRI(?a)) }";
        return handle.sparqlQuery().select(query)
                .map(row -> new ActorRef(ResourceId.of(iriOf(row, "a").getIRIString())))
                .toList();
    }

    /**
     * One step's (main-flow or extension) position, every {@code arkreq:stepText} candidate
     * collected across rows (tagged for {@link DisplayLocale}), and its {@code realises}
     * references (always empty for an extension step) - the per-step accumulator
     * {@link #readStepAssemblies} builds, since {@code arkreq:stepText} may now legally carry
     * several language-tagged literals (SKOS-S14-style {@code sh:uniqueLang}), multiplying a step
     * into one row per candidate.
     */
    private record StepAssembly(int position, List<LocalizedLiteral> textCandidates, List<RequirementRef> realises) {
    }

    /**
     * Reads every main-flow step's position, {@code stepText} candidates and {@code realises}
     * references. Delegates the query itself to {@link #readStepAssemblies}, shared with
     * {@link #readExtensionStepAssemblies} - both step flavours are {@code arkreq:Step}-shaped
     * resources reached the same way, differing only in the edge and in whether
     * {@code stepRealises} applies.
     */
    private List<StepAssembly> readMainStepAssemblies(DatasetHandle handle, String subject) {
        return readStepAssemblies(handle, subject, MAIN_STEP_PROPERTY, readMainStepRealises(handle, subject));
    }

    /**
     * {@link #readMainStepAssemblies} for extension steps ({@code arkreq:extensionStep}): no
     * {@code arkreq:stepRealises} concept applies to a free-text extension (unlike a main-flow
     * step, an extension is not itself a {@link Step} value object), so every assembly's
     * {@code realises} is empty.
     */
    private List<StepAssembly> readExtensionStepAssemblies(DatasetHandle handle, String subject) {
        return readStepAssemblies(handle, subject, EXTENSION_STEP_PROPERTY, Map.of());
    }

    /**
     * Reads every step reached from {@code subject} via {@code edgeProperty}'s position and
     * {@code stepText} candidates, grouped by step IRI then sorted by position - the position, not
     * the step's own (opaque, re-minted-on-every-write) IRI, is what a caller ({@link #toSteps}/
     * {@link #toExtensionTexts}/{@link #toStepLanguages}) actually keys on. {@code realisesByStep}
     * (keyed by step IRI, as read by {@link #readMainStepRealises}) is merged in verbatim - empty
     * for the extension-step caller, which has no such concept.
     */
    private List<StepAssembly> readStepAssemblies(DatasetHandle handle, String subject, String edgeProperty,
            Map<String, List<RequirementRef>> realisesByStep) {
        String stepsQuery = "SELECT ?step ?position ?text WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + edgeProperty + "> ?step . "
                + "?step <" + POSITION_PROPERTY + "> ?position ; <" + STEP_TEXT_PROPERTY + "> ?text } }";
        Map<String, Integer> positionByStep = new LinkedHashMap<>();
        Map<String, List<LocalizedLiteral>> textsByStep = new LinkedHashMap<>();
        handle.sparqlQuery().select(stepsQuery).forEach(row -> {
            String stepIri = iriOf(row, "step").getIRIString();
            positionByStep.putIfAbsent(stepIri, Integer.parseInt(literalOf(row, "position").getLexicalForm()));
            textsByStep.computeIfAbsent(stepIri, key -> new ArrayList<>()).add(localizedLiteralOf(row, "text"));
        });
        return positionByStep.entrySet().stream()
                .map(entry -> new StepAssembly(entry.getValue(), textsByStep.get(entry.getKey()),
                        realisesByStep.getOrDefault(entry.getKey(), List.of())))
                .sorted(Comparator.comparingInt(StepAssembly::position))
                .toList();
    }

    /**
     * Selects one {@code stepText} candidate per step via {@code locale}, building the ordered
     * main flow - mirrors {@link DisplayLocale}'s required-join guarantee: {@code arkreq:stepText}
     * carries {@code sh:minCount 1}, so a step this method sees always has at least one candidate.
     */
    private static List<Step> toSteps(List<StepAssembly> assemblies, DisplayLocale locale) {
        return assemblies.stream()
                .map(assembly -> new Step(assembly.position(),
                        locale.select(assembly.textCandidates())
                                .map(LocalizedLiteral::value)
                                .orElseThrow(() -> new IllegalStateException(
                                        "stepText is a required join, so at least one candidate must exist")),
                        assembly.realises()))
                .toList();
    }

    /**
     * {@link #toSteps} for extensions: selects one {@code stepText} candidate per extension via
     * {@code locale}, in position order, yielding the plain text {@link UseCase#extensions()}
     * carries (an extension has no {@code realises} concept, unlike a main-flow {@link Step}).
     */
    private static List<String> toExtensionTexts(List<StepAssembly> assemblies, DisplayLocale locale) {
        return assemblies.stream()
                .map(assembly -> locale.select(assembly.textCandidates())
                        .map(LocalizedLiteral::value)
                        .orElseThrow(() -> new IllegalStateException(
                                "stepText is a required join, so at least one candidate must exist")))
                .toList();
    }

    /**
     * The BCP-47 language tag each step's/extension's currently-selected {@code stepText}
     * candidate carries, keyed by position - backs both
     * {@link UseCaseRepository.CurrentUseCase#stepTextLanguageByPosition()} (main-flow steps) and
     * {@link UseCaseRepository.CurrentUseCase#extensionTextLanguageByPosition()} (extensions).
     */
    private static Map<Integer, String> toStepLanguages(List<StepAssembly> assemblies, DisplayLocale locale) {
        Map<Integer, String> stepTextLanguageByPosition = new LinkedHashMap<>();
        assemblies.forEach(assembly -> locale.select(assembly.textCandidates())
                .ifPresent(selected -> stepTextLanguageByPosition.put(assembly.position(), selected.languageTag())));
        return stepTextLanguageByPosition;
    }

    /**
     * Reads each main-flow step's {@code arkreq:stepRealises} edges back as requirement
     * references.
     *
     * <p><strong>No longer a join.</strong> The edge's target IRI <em>is</em> the
     * {@link RequirementRef} - no join into the sibling requirements graph is needed, and none is
     * performed here. {@code FILTER(isIRI(?req))} mirrors
     * {@code KognioRdfRequirementRepository#readUsesTerms}: the property carries no
     * {@code sh:nodeKind} constraint, so a store-first edge may legally target a blank
     * node, which {@link ResourceId} cannot represent - excluded here, unreachable via the MCP
     * tools.</p>
     *
     * <p><strong>Keyed by the step's own IRI, not its derived {@code arkreq:position}.</strong>
     * Nothing in SHACL forbids two distinct {@code arkreq:Step} nodes under the
     * same use case's {@code arkreq:mainStep} from sharing the same {@code arkreq:position} -
     * uniqueness is only enforced in-process by {@code UseCase.requireConsecutiveStepPositions},
     * and store-first data never runs through that. Grouping by the derived position
     * integer instead of step identity would silently merge two such steps' {@code stepRealises}
     * targets under one key, the same class of bug already fixed for
     * {@code supportingActor}/{@code stepRealises} elsewhere in this adapter.</p>
     */
    private Map<String, List<RequirementRef>> readMainStepRealises(DatasetHandle handle, String subject) {
        String query = "SELECT ?step ?req WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + MAIN_STEP_PROPERTY + "> ?step . "
                + "?step <" + STEP_REALISES_PROPERTY + "> ?req } "
                + "FILTER(isIRI(?req)) }";
        Map<String, List<RequirementRef>> byStep = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> {
            String stepIri = iriOf(row, "step").getIRIString();
            byStep.computeIfAbsent(stepIri, k -> new ArrayList<>())
                    .add(new RequirementRef(ResourceId.of(iriOf(row, "req").getIRIString())));
        });
        return byStep;
    }

    /**
     * Mirrors {@code UseCase.requireConsecutiveStepPositions} as a non-throwing predicate: the
     * step at list index {@code i} must carry position {@code i + 1}. {@code steps} is built by
     * {@link #toSteps} from {@link #readMainStepAssemblies}, itself sorted by
     * {@code arkreq:position}, so a store-first gap, duplicate or descending position is
     * detected here before it ever reaches {@link UseCase}'s constructor.
     */
    private static boolean hasConsecutiveStepPositions(List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).position() != i + 1) {
                return false;
            }
        }
        return true;
    }

    // ---- already-resolved reference conversion -----------------------------------------

    /**
     * Converts an already-resolved {@link RequirementRef} to an {@link IRI} for writing.
     * {@link ResourceId#of(String)} validates IRIREF-safety at construction, so the
     * wrapped IRI is already guaranteed safe here. Mirrors
     * {@code KognioRdfRequirementRepository#termIriFor}.
     */
    private IRI requirementIriFor(RequirementRef ref) {
        return rdf.createIRI(ref.value().value());
    }

    /**
     * Converts an already-resolved {@link ActorRef} to an {@link IRI} for writing. Mirrors
     * {@code KognioRdfRequirementRepository#termIriFor}; see {@link #requirementIriFor} for the
     * IRIREF-safety rationale.
     */
    private IRI actorIriFor(ActorRef ref) {
        return rdf.createIRI(ref.value().value());
    }

    /**
     * Converts an already-resolved {@link TermRef} to an {@link IRI} for writing (issue #329).
     * Mirrors {@link #requirementIriFor}; see there for the IRIREF-safety rationale.
     */
    private IRI termIriFor(TermRef term) {
        return rdf.createIRI(term.value().value());
    }

    /**
     * Converts an already-resolved {@link ConstraintRef} to an {@link IRI} for writing
     * (issue #329). Mirrors {@link #requirementIriFor}; see there for the IRIREF-safety rationale.
     */
    private IRI constraintIriFor(ConstraintRef constraint) {
        return rdf.createIRI(constraint.value().value());
    }

    // ---- helpers -----------------------------------------------------------------------

    /**
     * Mints an opaque IRI for a derived step resource from the same kernel scheme as the use
     * case root. A step is a value object with no stable identity - see the class-level note.
     */
    private IRI mintStepIri() {
        return rdf.createIRI(resourceIdFactory.newId().value());
    }

    /**
     * Adds {@code value} as the language-tagged (or untagged, for a {@code null} tag) literal
     * object of {@code property} - skipped entirely when {@code value} is {@code null}, since
     * scope/trigger/precondition/postcondition are optional fields.
     */
    private void addOptional(Graph graph, IRI subject, String property, String value, String tag) {
        if (value != null) {
            graph.add(subject, rdf.createIRI(property), literalOf(value, tag));
        }
    }

    /** Builds a language-tagged literal, or a plain untagged one when {@code tag} is {@code null}. */
    private Literal literalOf(String value, String tag) {
        return tag == null ? rdf.createLiteral(value) : rdf.createLiteral(value, tag);
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    /**
     * Reads a bound value as the bare, technology-neutral {@link RDFTerm} - not cast to
     * {@link IRI}, since this backs {@code selectUnjoinableUsesTerms}/
     * {@code selectUnjoinableConstrainedBy}'s reads, whose whole point is a target that is
     * <em>not</em> an IRI (a store-first blank node). Mirrors
     * {@code KognioRdfRequirementRepository#termOf}.
     */
    private static RDFTerm termOf(BindingSet row, String name) {
        return row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    /** Converts a bound literal into the technology-neutral {@link LocalizedLiteral} projection. */
    private static LocalizedLiteral localizedLiteralOf(BindingSet row, String name) {
        Literal literal = literalOf(row, name);
        return new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null));
    }
}
