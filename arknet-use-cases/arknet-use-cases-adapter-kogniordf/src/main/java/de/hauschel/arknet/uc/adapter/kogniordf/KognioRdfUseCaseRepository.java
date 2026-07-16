package de.hauschel.arknet.uc.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Out-adapter: {@link UseCaseRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF dataset).
 *
 * <p>Maps a {@link UseCase} to an {@code arkreq:UseCase} whose subject IRI is the opaque
 * {@link UseCaseId} (minted once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory}, never
 * derived from the business code), stored in one named graph shared by all use cases of a
 * workspace. The {@code dcterms:identifier} triple carries the human-readable
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
 * ontology (PR #42): {@code arkreq:useCaseGoal}, {@code arkreq:designScope}, {@code arkreq:trigger},
 * {@code arkreq:useCasePrecondition}, {@code arkreq:useCasePostcondition}, {@code arkreq:primaryActor},
 * {@code arkreq:supportingActor}, {@code arkreq:mainStep}, {@code arkreq:extensionStep},
 * {@code arkreq:position}, {@code arkreq:stepText}, {@code arkreq:stepRealises} and
 * {@code oslc_rm:satisfies}.</p>
 *
 * <p><strong>Strict cross-BC reference resolution (issue #41).</strong> Use cases,
 * requirements and ubiquitous-language actors share one per-workspace store. On write every
 * label reference is resolved against that store <em>before</em> anything is written: a
 * {@link RequirementRef} label (e.g. {@code FR-5}) is looked up by {@code dcterms:identifier}
 * among requirements, an {@link ActorRef} label (e.g. {@code Customer}) by {@code skos:prefLabel}
 * among concepts carrying an actor type ({@code arkproc:HumanActor}/{@code arkproc:SystemActor}).
 * Both look-ups are schema-independent (by identifier/label, never by reconstructing a subject
 * IRI), so they are unaffected by the opaque-identity switch of either bounded context. An
 * unknown or ambiguous label aborts the write with a didactic {@link UnresolvedReferenceException};
 * no dangling reference is ever persisted. The coarse {@code UseCase oslc_rm:satisfies Requirement}
 * edge is derived as the union of the resolved {@code stepRealises} targets.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} +
 * {@code dataset}) and {@link SimpleRdf} - it never imports RDF4J or any other
 * backend-specific type. The backend ({@link DatasetLifecycle} implementation) is supplied
 * by the composition root.</p>
 *
 * <p><strong>WorkspaceId (local, single-user).</strong> Each {@link WorkspaceId} is mapped
 * 1:1 to a kognio-rdf {@link DatasetId}, so distinct workspaces are fully isolated datasets.</p>
 *
 * <p><strong>Create vs. update (opaque identity).</strong> Because identity is opaque and
 * minted once, "insert or replace by identity" is no longer one coherent operation.
 * {@link #create} and {@link #update} each check whether the subject already exists
 * <em>inside</em> the write transaction (an {@code ASK}) before writing - not via a separate
 * {@code findByCode} call beforehand, which would leave a check-then-act race between the check
 * and the write. {@link #create} rejects an existing subject with
 * {@link ResourceAlreadyExistsException} and, via a second {@code ASK} by
 * {@code dcterms:identifier}, a colliding business code with {@link DuplicateUseCaseCodeException};
 * {@link #update} rejects a missing subject with {@link UseCaseNotFoundException}. Either write
 * otherwise replaces the subject and all its derived step resources wholesale.</p>
 *
 * <p><strong>SHACL write-gate.</strong> Every write call validates the candidate instance graph
 * against the use-case SHACL shapes via {@link ShaclWriteGate} before starting the write
 * transaction; a violation throws {@link WriteConstraintViolationException} and nothing is
 * persisted.</p>
 */
public class KognioRdfUseCaseRepository implements UseCaseRepository {

    private static final String ARKREQ_NAMESPACE = "https://w3id.org/arknet/requirements#";
    private static final String ARKPROC_NAMESPACE = "https://w3id.org/arknet/process#";
    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String OSLC_RM_NAMESPACE = "http://open-services.net/ns/rm#";

    private static final String USE_CASES_GRAPH = "https://w3id.org/arknet/model/use-cases";
    // Mirrors the graph IRIs the requirements / ubiquitous-language out-adapters write into.
    // The three bounded contexts share one workspace dataset; resolving references means
    // reading across those sibling graphs (see class-level strict-resolution note).
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";

    private static final String USE_CASE_TYPE = ARKREQ_NAMESPACE + "UseCase";
    private static final String STEP_TYPE = ARKREQ_NAMESPACE + "Step";
    private static final String REQUIREMENT_TYPE = ARKREQ_NAMESPACE + "Requirement";
    private static final String ACTOR_TYPE = ARKPROC_NAMESPACE + "Actor";
    private static final String USE_CASE_GOAL_PROPERTY = ARKREQ_NAMESPACE + "useCaseGoal";
    private static final String DESIGN_SCOPE_PROPERTY = ARKREQ_NAMESPACE + "designScope";
    private static final String TRIGGER_PROPERTY = ARKREQ_NAMESPACE + "trigger";
    private static final String PRECONDITION_PROPERTY = ARKREQ_NAMESPACE + "useCasePrecondition";
    private static final String POSTCONDITION_PROPERTY = ARKREQ_NAMESPACE + "useCasePostcondition";
    private static final String PRIMARY_ACTOR_PROPERTY = ARKREQ_NAMESPACE + "primaryActor";
    private static final String SUPPORTING_ACTOR_PROPERTY = ARKREQ_NAMESPACE + "supportingActor";
    private static final String MAIN_STEP_PROPERTY = ARKREQ_NAMESPACE + "mainStep";
    private static final String EXTENSION_STEP_PROPERTY = ARKREQ_NAMESPACE + "extensionStep";
    private static final String POSITION_PROPERTY = ARKREQ_NAMESPACE + "position";
    private static final String STEP_TEXT_PROPERTY = ARKREQ_NAMESPACE + "stepText";
    private static final String STEP_REALISES_PROPERTY = ARKREQ_NAMESPACE + "stepRealises";
    private static final String SATISFIES_PROPERTY = OSLC_RM_NAMESPACE + "satisfies";
    private static final String TITLE_PROPERTY = VocabDct.NAMESPACE + "title";
    private static final String IDENTIFIER_PROPERTY = VocabDct.NAMESPACE + "identifier";
    private static final String PREF_LABEL_PROPERTY = SKOS_NAMESPACE + "prefLabel";
    private static final String HUMAN_ACTOR_TYPE = ARKPROC_NAMESPACE + "HumanActor";
    private static final String SYSTEM_ACTOR_TYPE = ARKPROC_NAMESPACE + "SystemActor";

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final ResourceIdFactory resourceIdFactory;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle         the kognio-rdf dataset lifecycle to acquire datasets from (must
     *                          not be {@code null})
     * @param gate              the SHACL write-gate validating candidate graphs before
     *                          persistence (must not be {@code null})
     * @param resourceIdFactory mints the opaque IRI of each derived step resource (must not be
     *                          {@code null}); the use-case root's own identity is minted above
     *                          the store and arrives on the {@link UseCase}
     */
    KognioRdfUseCaseRepository(DatasetLifecycle lifecycle, ShaclWriteGate gate,
            ResourceIdFactory resourceIdFactory) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
    }

    @Override
    public void create(WorkspaceId workspaceId, UseCase useCase) {
        write(workspaceId, useCase, true);
    }

    @Override
    public void update(WorkspaceId workspaceId, UseCase useCase) {
        write(workspaceId, useCase, false);
    }

    private void write(WorkspaceId workspaceId, UseCase useCase, boolean expectAbsent) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(useCase, "useCase");

        // Defense-in-depth: ResourceId's own validation is looser than SPARQL's IRIREF grammar.
        // Reject an impossible identity before it ever reaches SPARQL string concatenation.
        String subjectIriString = useCase.id().value().value();
        if (!SparqlTerms.isValidIriReference(subjectIriString)) {
            throw new IllegalArgumentException(
                    "use case id yields an invalid IRI for SPARQL: " + subjectIriString);
        }
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            // 1. Resolve every label reference strictly against the shared workspace store.
            IRI primaryActorIri = resolveActor(handle, workspaceId, useCase.primaryActor());
            List<IRI> supportingActorIris = useCase.supportingActors().stream()
                    .map(actor -> resolveActor(handle, workspaceId, actor))
                    .toList();

            // 2. Build the candidate graph.
            Graph graph = rdf.createGraph();
            graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(USE_CASE_TYPE));
            graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(useCase.code().value()));
            graph.add(subjectIri, rdf.createIRI(TITLE_PROPERTY), rdf.createLiteral(useCase.title()));
            graph.add(subjectIri, rdf.createIRI(USE_CASE_GOAL_PROPERTY), rdf.createLiteral(useCase.goal()));
            addOptional(graph, subjectIri, DESIGN_SCOPE_PROPERTY, useCase.scope());
            addOptional(graph, subjectIri, TRIGGER_PROPERTY, useCase.trigger());
            addOptional(graph, subjectIri, PRECONDITION_PROPERTY, useCase.precondition());
            addOptional(graph, subjectIri, POSTCONDITION_PROPERTY, useCase.postcondition());
            graph.add(subjectIri, rdf.createIRI(PRIMARY_ACTOR_PROPERTY), primaryActorIri);
            for (IRI supporting : supportingActorIris) {
                graph.add(subjectIri, rdf.createIRI(SUPPORTING_ACTOR_PROPERTY), supporting);
            }

            // 3. Main-flow steps (own opaque resources) + the coarse UC->Requirement satisfies edge.
            Map<String, IRI> satisfies = new LinkedHashMap<>();
            for (Step step : useCase.steps()) {
                IRI stepIri = mintStepIri();
                graph.add(subjectIri, rdf.createIRI(MAIN_STEP_PROPERTY), stepIri);
                graph.add(stepIri, VocabRdf.TYPE, rdf.createIRI(STEP_TYPE));
                graph.add(stepIri, rdf.createIRI(POSITION_PROPERTY),
                        rdf.createLiteral(Integer.toString(step.position()), VocabXsd.INTEGER));
                graph.add(stepIri, rdf.createIRI(STEP_TEXT_PROPERTY), rdf.createLiteral(step.text()));
                for (RequirementRef ref : step.realises()) {
                    IRI reqIri = resolveRequirement(handle, workspaceId, ref);
                    graph.add(stepIri, rdf.createIRI(STEP_REALISES_PROPERTY), reqIri);
                    satisfies.putIfAbsent(reqIri.getIRIString(), reqIri);
                }
            }
            for (IRI reqIri : satisfies.values()) {
                graph.add(subjectIri, rdf.createIRI(SATISFIES_PROPERTY), reqIri);
            }

            // 4. Extensions: free-text alternative/exception flows as opaque extensionStep resources.
            int extensionPosition = 1;
            for (String extension : useCase.extensions()) {
                IRI stepIri = mintStepIri();
                graph.add(subjectIri, rdf.createIRI(EXTENSION_STEP_PROPERTY), stepIri);
                graph.add(stepIri, VocabRdf.TYPE, rdf.createIRI(STEP_TYPE));
                graph.add(stepIri, rdf.createIRI(POSITION_PROPERTY),
                        rdf.createLiteral(Integer.toString(extensionPosition), VocabXsd.INTEGER));
                graph.add(stepIri, rdf.createIRI(STEP_TEXT_PROPERTY), rdf.createLiteral(extension));
                extensionPosition++;
            }

            // 5. Structural gate, then create/update (use case + all its derived steps).
            //    The shapes carry sh:class constraints on primaryActor (arkproc:Actor) and
            //    stepRealises (arkreq:Requirement). The type triples for those referenced nodes
            //    live in the sibling requirements/terms graphs, not in this candidate graph.
            //    They are handed to the gate as a validation-only asserted context (never
            //    persisted here). This is safe: the strict lookup above already proved each
            //    reference exists and is of the right kind.
            Graph assertedContext = rdf.createGraph();
            assertedContext.add(primaryActorIri, VocabRdf.TYPE, rdf.createIRI(ACTOR_TYPE));
            for (IRI supporting : supportingActorIris) {
                assertedContext.add(supporting, VocabRdf.TYPE, rdf.createIRI(ACTOR_TYPE));
            }
            for (IRI reqIri : satisfies.values()) {
                assertedContext.add(reqIri, VocabRdf.TYPE, rdf.createIRI(REQUIREMENT_TYPE));
            }
            gate.enforce(graph, assertedContext);

            // The step IRIs are opaque and not under the use-case IRI, so the old
            // "delete everything whose subject STRSTARTS the use-case IRI" no longer reaches
            // them. Delete the use-case subject's own triples and, following the
            // mainStep/extensionStep edges, each step resource's triples.
            String askExists = "ASK { GRAPH <" + USE_CASES_GRAPH + "> { " + subject + " ?p ?o } }";
            String askCodeExists = "ASK { GRAPH <" + USE_CASES_GRAPH + "> { "
                    + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(useCase.code().value())
                    + "\" } }";
            String deleteExisting = "DELETE { GRAPH <" + USE_CASES_GRAPH + "> { ?s ?p ?o } } WHERE { "
                    + "GRAPH <" + USE_CASES_GRAPH + "> { "
                    + "{ " + subject + " ?p ?o . BIND(" + subject + " AS ?s) } UNION "
                    + "{ " + subject + " (<" + MAIN_STEP_PROPERTY + ">|<" + EXTENSION_STEP_PROPERTY
                    + ">) ?s . ?s ?p ?o } } }";
            IRI graphIri = rdf.createIRI(USE_CASES_GRAPH);

            handle.transactor().inTransaction(tx -> {
                boolean exists = tx.ask(askExists);
                if (expectAbsent) {
                    if (exists) {
                        throw new ResourceAlreadyExistsException(workspaceId, useCase.id().value());
                    }
                    // Identity is opaque and unique by construction, but the human-readable code
                    // is a separate triple this ASK alone cannot rule out - check it here, inside
                    // the same write transaction, so no other create() can race in between.
                    if (tx.ask(askCodeExists)) {
                        throw new DuplicateUseCaseCodeException(workspaceId, useCase.code());
                    }
                } else if (!exists) {
                    throw new UseCaseNotFoundException(workspaceId, useCase.code());
                }
                if (exists) {
                    tx.update(deleteExisting);
                }
                tx.add(graphIri, graph);
                return null;
            });
        }
    }

    @Override
    public Optional<UseCase> findByCode(WorkspaceId workspaceId, UseCaseCode code) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");
        String query = "SELECT ?s WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + "?s a <" + USE_CASE_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> \""
                + SparqlTerms.escape(code.value()) + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            Optional<BindingSet> head = handle.sparqlQuery().select(query).findFirst();
            if (head.isEmpty()) {
                return Optional.empty();
            }
            return readBySubject(handle, iriOf(head.get(), "s").getIRIString(), code);
        }
    }

    @Override
    public List<UseCase> findAll(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + "?s a <" + USE_CASE_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> ?identifier } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            List<UseCaseRow> rows = handle.sparqlQuery().select(query)
                    .map(row -> new UseCaseRow(iriOf(row, "s").getIRIString(),
                            new UseCaseCode(literalOf(row, "identifier").getLexicalForm())))
                    .toList();
            List<UseCase> result = new ArrayList<>();
            for (UseCaseRow row : rows) {
                readBySubject(handle, row.subjectIri(), row.code()).ifPresent(result::add);
            }
            return List.copyOf(result);
        }
    }

    private record UseCaseRow(String subjectIri, UseCaseCode code) {
    }

    // ---- reading -----------------------------------------------------------------------

    private Optional<UseCase> readBySubject(DatasetHandle handle, String subjectIriString, UseCaseCode code) {
        if (!SparqlTerms.isValidIriReference(subjectIriString)) {
            // A syntactically impossible identifier cannot match anything in the store -
            // report "not found" instead of building a malformed SPARQL query.
            return Optional.empty();
        }
        String subject = SparqlTerms.iriRef(subjectIriString);
        String scalarQuery = "SELECT ?title ?goal ?scope ?trigger ?precondition ?postcondition ?primaryLabel "
                + "WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " a <" + USE_CASE_TYPE + "> ; "
                + "<" + TITLE_PROPERTY + "> ?title ; "
                + "<" + USE_CASE_GOAL_PROPERTY + "> ?goal ; "
                + "<" + PRIMARY_ACTOR_PROPERTY + "> ?primaryActor . "
                + "OPTIONAL { " + subject + " <" + DESIGN_SCOPE_PROPERTY + "> ?scope } "
                + "OPTIONAL { " + subject + " <" + TRIGGER_PROPERTY + "> ?trigger } "
                + "OPTIONAL { " + subject + " <" + PRECONDITION_PROPERTY + "> ?precondition } "
                + "OPTIONAL { " + subject + " <" + POSTCONDITION_PROPERTY + "> ?postcondition } } "
                + "GRAPH <" + TERMS_GRAPH + "> { ?primaryActor <" + PREF_LABEL_PROPERTY + "> ?primaryLabel } }";

        Optional<BindingSet> head = handle.sparqlQuery().select(scalarQuery).findFirst();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        BindingSet row = head.get();

        List<ActorRef> supportingActors = readSupportingActors(handle, subject);
        List<Step> steps = readMainSteps(handle, subject);
        List<String> extensions = readExtensions(handle, subject);

        return Optional.of(new UseCase(
                new UseCaseId(ResourceId.of(subjectIriString)),
                code,
                literalOf(row, "title").getLexicalForm(),
                literalOf(row, "goal").getLexicalForm(),
                optionalLiteral(row, "scope"),
                optionalLiteral(row, "trigger"),
                new ActorRef(literalOf(row, "primaryLabel").getLexicalForm()),
                supportingActors,
                optionalLiteral(row, "precondition"),
                optionalLiteral(row, "postcondition"),
                steps,
                extensions));
    }

    private List<ActorRef> readSupportingActors(DatasetHandle handle, String subject) {
        String query = "SELECT ?label WHERE { "
                + "GRAPH <" + USE_CASES_GRAPH + "> { " + subject + " <" + SUPPORTING_ACTOR_PROPERTY + "> ?a } "
                + "GRAPH <" + TERMS_GRAPH + "> { ?a <" + PREF_LABEL_PROPERTY + "> ?label } }";
        return handle.sparqlQuery().select(query)
                .map(row -> new ActorRef(literalOf(row, "label").getLexicalForm()))
                .toList();
    }

    private List<Step> readMainSteps(DatasetHandle handle, String subject) {
        String stepsQuery = "SELECT ?position ?text WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + MAIN_STEP_PROPERTY + "> ?step . "
                + "?step <" + POSITION_PROPERTY + "> ?position ; <" + STEP_TEXT_PROPERTY + "> ?text } } "
                + "ORDER BY ?position";
        Map<Integer, List<RequirementRef>> realisesByPosition = readMainStepRealises(handle, subject);
        return handle.sparqlQuery().select(stepsQuery)
                .map(row -> {
                    int position = Integer.parseInt(literalOf(row, "position").getLexicalForm());
                    return new Step(position, literalOf(row, "text").getLexicalForm(),
                            realisesByPosition.getOrDefault(position, List.of()));
                })
                .toList();
    }

    private Map<Integer, List<RequirementRef>> readMainStepRealises(DatasetHandle handle, String subject) {
        String query = "SELECT ?position ?reqId WHERE { "
                + "GRAPH <" + USE_CASES_GRAPH + "> { " + subject + " <" + MAIN_STEP_PROPERTY + "> ?step . "
                + "?step <" + POSITION_PROPERTY + "> ?position ; <" + STEP_REALISES_PROPERTY + "> ?req } "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { ?req <" + IDENTIFIER_PROPERTY + "> ?reqId } } "
                + "ORDER BY ?position";
        Map<Integer, List<RequirementRef>> byPosition = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> {
            int position = Integer.parseInt(literalOf(row, "position").getLexicalForm());
            byPosition.computeIfAbsent(position, k -> new ArrayList<>())
                    .add(new RequirementRef(literalOf(row, "reqId").getLexicalForm()));
        });
        return byPosition;
    }

    private List<String> readExtensions(DatasetHandle handle, String subject) {
        String query = "SELECT ?text WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + EXTENSION_STEP_PROPERTY + "> ?step . "
                + "?step <" + POSITION_PROPERTY + "> ?position ; <" + STEP_TEXT_PROPERTY + "> ?text } } "
                + "ORDER BY ?position";
        return handle.sparqlQuery().select(query)
                .map(row -> literalOf(row, "text").getLexicalForm())
                .toList();
    }

    // ---- strict reference resolution ---------------------------------------------------

    private IRI resolveRequirement(DatasetHandle handle, WorkspaceId workspaceId, RequirementRef ref) {
        String query = "SELECT ?req WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "?req <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(ref.label()) + "\" } }";
        List<IRI> matches = handle.sparqlQuery().select(query)
                .map(row -> iriOf(row, "req"))
                .distinct()
                .toList();
        if (matches.isEmpty()) {
            throw new UnresolvedReferenceException("Requirement '" + ref.label()
                    + "' does not exist in workspace '" + workspaceId.value()
                    + "'. Create it first with req_add before a use-case step realises it.");
        }
        if (matches.size() > 1) {
            throw new UnresolvedReferenceException("Requirement label '" + ref.label()
                    + "' is ambiguous in workspace '" + workspaceId.value() + "' (" + matches.size()
                    + " matches). Reference a requirement by its unique dcterms:identifier.");
        }
        return matches.get(0);
    }

    private IRI resolveActor(DatasetHandle handle, WorkspaceId workspaceId, ActorRef actor) {
        String query = "SELECT ?actor WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "?actor <" + PREF_LABEL_PROPERTY + "> \"" + SparqlTerms.escape(actor.label()) + "\" . "
                + "{ ?actor a <" + HUMAN_ACTOR_TYPE + "> } UNION { ?actor a <" + SYSTEM_ACTOR_TYPE + "> } } }";
        List<IRI> matches = handle.sparqlQuery().select(query)
                .map(row -> iriOf(row, "actor"))
                .distinct()
                .toList();
        if (matches.isEmpty()) {
            throw new UnresolvedReferenceException("Actor '" + actor.label()
                    + "' does not exist in workspace '" + workspaceId.value()
                    + "'. Create it first with term_add (actorKind human|system) before a use case references it.");
        }
        if (matches.size() > 1) {
            throw new UnresolvedReferenceException("Actor label '" + actor.label()
                    + "' is ambiguous in workspace '" + workspaceId.value() + "' (" + matches.size()
                    + " matches). Give the actor term a unique skos:prefLabel.");
        }
        return matches.get(0);
    }

    // ---- helpers -----------------------------------------------------------------------

    /**
     * Mints an opaque IRI for a derived step resource from the same kernel scheme as the use
     * case root. A step is a value object with no stable identity - see the class-level note.
     */
    private IRI mintStepIri() {
        return rdf.createIRI(resourceIdFactory.newId().value());
    }

    private void addOptional(Graph graph, IRI subject, String property, String value) {
        if (value != null) {
            graph.add(subject, rdf.createIRI(property), rdf.createLiteral(value));
        }
    }

    private static String optionalLiteral(BindingSet row, String name) {
        return row.getValue(name).map(value -> ((Literal) value).getLexicalForm()).orElse(null);
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
