// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
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
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.uc.domain.Step;
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
 * <p><strong>Requirement/actor references arrive pre-resolved (issue #41, identity-carrying
 * since #89).</strong> {@link RequirementRef} and {@link ActorRef} carry the referenced
 * resource's opaque subject {@link ResourceId} directly - resolving a human-typed requirement
 * code (e.g. {@code FR-5}) or actor name (e.g. {@code Customer}) against the shared workspace
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
 * (ADR-005) edge may legally target a blank node, which {@link ResourceId} cannot represent. For
 * {@code supportingActor}/{@code stepRealises} such an edge is simply absent from the
 * corresponding list. {@code primaryActor} is a required (non-{@code OPTIONAL}) triple pattern
 * in the scalar read, so filtering it out there instead makes the whole use case unreadable -
 * {@link #readBySubject} returns {@link Optional#empty()}, and {@link #findAll}/
 * {@link #findByCode} treat it as "not found", silently skipping only that one use case rather
 * than failing the whole result. This is unreachable via the MCP tools, which always resolve to
 * a subject IRI before writing.</p>
 *
 * <p><strong>A use case with zero main steps is handled the same way (issue #102).</strong>
 * {@code arkreq:mainStep} is only {@code sh:Warning} severity at {@code sh:minCount 1} (not
 * {@code sh:Violation}), so {@link ShaclWriteGate#enforce} lets a store-first (ADR-005) use case
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
 * the SHACL gate, the commit-conflict translation (issue #144) - live in the shared
 * {@link WriteFunnel} (ADR-013), not here: {@link #create} only builds the candidate graph and
 * rejects an existing subject with {@link ResourceAlreadyExistsException} or a colliding business
 * code with {@link DuplicateUseCaseCodeException}; {@link #compareAndUpdate} rejects a missing
 * subject with {@link UseCaseNotFoundException} and a stale {@code expectedHead} with
 * {@link UseCaseConcurrentlyModifiedException} (issue #165, mirroring
 * {@code KognioRdfRequirementRepository}). There is no unconditional update: every correction to
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

    private static final String USE_CASE_TYPE = ARKREQ_NAMESPACE + "UseCase";
    private static final String STEP_TYPE = ArkreqVocabulary.STEP_TYPE;
    private static final String REQUIREMENT_TYPE = ARKREQ_NAMESPACE + "Requirement";
    private static final String ACTOR_TYPE = ARKPROC_NAMESPACE + "Actor";
    private static final String USE_CASE_GOAL_PROPERTY = ARKREQ_NAMESPACE + "useCaseGoal";
    private static final String DESIGN_SCOPE_PROPERTY = ARKREQ_NAMESPACE + "designScope";
    private static final String TRIGGER_PROPERTY = ARKREQ_NAMESPACE + "trigger";
    private static final String PRECONDITION_PROPERTY = ARKREQ_NAMESPACE + "useCasePrecondition";
    private static final String POSTCONDITION_PROPERTY = ARKREQ_NAMESPACE + "useCasePostcondition";
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

    private final DatasetLifecycle lifecycle;
    private final ResourceIdFactory resourceIdFactory;
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
     * @param funnel            the shared write funnel (ADR-013) both {@link #create} and
     *                          {@link #compareAndUpdate} run through - SHACL gate, dataset
     *                          acquisition, the in-transaction existence/head checks and the
     *                          commit-conflict translation (must not be {@code null})
     */
    KognioRdfUseCaseRepository(DatasetLifecycle lifecycle, ResourceIdFactory resourceIdFactory, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, UseCase useCase) {
        write(projectId, useCase, true, null);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, UseCase updated) {
        write(projectId, updated, false, expectedHead);
    }

    private void write(ProjectId projectId, UseCase useCase, boolean expectAbsent, RevisionToken expectedHead) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(useCase, "useCase");

        // ResourceId#of (issue #83) validates IRIREF-safety at construction, so useCase.id()'s
        // wrapped IRI is already guaranteed safe to embed here - no separate check needed.
        String subjectIriString = useCase.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);

        // 1. Every actor/requirement reference already carries its resolved identity (see
        //    class-level note), guaranteed IRIREF-safe by ResourceId#of (issue #83) same as
        //    the subject above.
        IRI primaryActorIri = actorIriFor(useCase.primaryActor());
        List<IRI> supportingActorIris = useCase.supportingActors().stream()
                .map(this::actorIriFor)
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
                IRI reqIri = requirementIriFor(ref);
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

        // 5. The shapes carry sh:class constraints on primaryActor (arkproc:Actor) and
        //    stepRealises (arkreq:Requirement). The type triples for those referenced nodes
        //    live in the sibling requirements/terms graphs, not in this candidate graph.
        //    They are handed to the funnel's gate as a validation-only asserted context (never
        //    persisted here). This is safe: the reference was already proven to exist and be
        //    of the right kind at the moment it was resolved (KognioRdfRequirementLookup /
        //    KognioRdfActorLookup, called once from the application service) - the lookup,
        //    not the shape, is what keeps the edge non-dangling; this adapter no longer
        //    re-verifies it.
        Graph assertedContext = rdf.createGraph();
        assertedContext.add(primaryActorIri, VocabRdf.TYPE, rdf.createIRI(ACTOR_TYPE));
        for (IRI supporting : supportingActorIris) {
            assertedContext.add(supporting, VocabRdf.TYPE, rdf.createIRI(ACTOR_TYPE));
        }
        for (IRI reqIri : satisfies.values()) {
            assertedContext.add(reqIri, VocabRdf.TYPE, rdf.createIRI(REQUIREMENT_TYPE));
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
                        tx.update(deleteExisting);
                        tx.add(graphIri, graph);
                    });
        }
    }

    @Override
    public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        String query = "SELECT ?s WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + "?s a <" + USE_CASE_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> \""
                + SparqlTerms.escape(code.value()) + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Optional<BindingSet> head = handle.sparqlQuery().select(query).findFirst();
            if (head.isEmpty()) {
                return Optional.empty();
            }
            return readBySubject(handle, iriOf(head.get(), "s").getIRIString(), code);
        }
    }

    @Override
    public List<UseCase> findAll(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + "?s a <" + USE_CASE_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> ?identifier } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
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

    /**
     * Reads a use case's current state together with its concurrency token (the
     * {@code arkprov:head} revision IRI recorded by the last funnel write, ADR-014) - the read
     * side of the read-modify-write round trip {@link #compareAndUpdate} guards the write side
     * of. Mirrors {@code KognioRdfRequirementRepository#findCurrentByCode}: reuses the same
     * subject lookup {@link #findByCode} does, then pairs the scalar/head read in one query call
     * via {@link #readCurrentBySubject} - one snapshot for the core fields and the head, exactly
     * as {@link #readBySubject} reads the core fields alone.
     */
    @Override
    public Optional<UseCaseRepository.CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        String query = "SELECT ?s WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + "?s a <" + USE_CASE_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> \""
                + SparqlTerms.escape(code.value()) + "\" } }";
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
     * was a real bug more than once in the sibling requirements adapter (issues #80/#81).
     *
     * <p>{@code FILTER(isIRI(?primaryActor))} mirrors {@link #readSupportingActors}/
     * {@link #readMainStepRealises}: {@code arkreq:primaryActor} carries no {@code sh:nodeKind}
     * constraint, so a store-first (ADR-005) edge may legally target a blank node, which
     * {@link ResourceId} cannot represent. Unlike the other two properties, {@code primaryActor}
     * is part of this required (non-{@code OPTIONAL}) triple pattern, so filtering it out here
     * makes the whole scalar query yield no row for such a use case - the caller then treats it
     * as "not found", silently skipping only this one use case rather than crashing the whole
     * result list.</p>
     */
    private static String scalarWhereClause(String subject) {
        return subject + " a <" + USE_CASE_TYPE + "> ; "
                + "<" + TITLE_PROPERTY + "> ?title ; "
                + "<" + USE_CASE_GOAL_PROPERTY + "> ?goal ; "
                + "<" + PRIMARY_ACTOR_PROPERTY + "> ?primaryActor . "
                + "FILTER(isIRI(?primaryActor)) "
                + "OPTIONAL { " + subject + " <" + DESIGN_SCOPE_PROPERTY + "> ?scope } "
                + "OPTIONAL { " + subject + " <" + TRIGGER_PROPERTY + "> ?trigger } "
                + "OPTIONAL { " + subject + " <" + PRECONDITION_PROPERTY + "> ?precondition } "
                + "OPTIONAL { " + subject + " <" + POSTCONDITION_PROPERTY + "> ?postcondition } ";
    }

    private Optional<UseCase> readBySubject(DatasetHandle handle, String subjectIriString, UseCaseCode code) {
        if (!SparqlTerms.isValidIriReference(subjectIriString)) {
            // A syntactically impossible identifier cannot match anything in the store -
            // report "not found" instead of building a malformed SPARQL query.
            return Optional.empty();
        }
        String subject = SparqlTerms.iriRef(subjectIriString);
        String scalarQuery = "SELECT ?title ?goal ?scope ?trigger ?precondition ?postcondition ?primaryActor "
                + "WHERE { GRAPH <" + USE_CASES_GRAPH + "> { " + scalarWhereClause(subject) + "} }";

        Optional<BindingSet> row = handle.sparqlQuery().select(scalarQuery).findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        return buildUseCase(handle, subjectIriString, code, subject, row.get());
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
        String scalarQuery = "SELECT ?title ?goal ?scope ?trigger ?precondition ?postcondition ?primaryActor ?head "
                + "WHERE { GRAPH <" + USE_CASES_GRAPH + "> { " + scalarWhereClause(subject) + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + subject + " <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        Optional<BindingSet> row = handle.sparqlQuery().select(scalarQuery).findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Optional<UseCase> useCase = buildUseCase(handle, subjectIriString, code, subject, row.get());
        if (useCase.isEmpty()) {
            return Optional.empty();
        }
        RevisionToken head = row.get().getValue("head")
                .filter(IRI.class::isInstance)
                .map(value -> new RevisionToken(((IRI) value).getIRIString()))
                .orElse(null);
        return Optional.of(new UseCaseRepository.CurrentUseCase(useCase.get(), head));
    }

    /**
     * Builds a {@link UseCase} from {@code row} (the projection of {@link #scalarWhereClause})
     * plus the follow-up reads {@link #readSupportingActors}/{@link #readMainSteps}/
     * {@link #readExtensions} - shared by {@link #readBySubject} and
     * {@link #readCurrentBySubject} so both build a {@link UseCase} identically.
     */
    private Optional<UseCase> buildUseCase(
            DatasetHandle handle, String subjectIriString, UseCaseCode code, String subject, BindingSet row) {
        List<ActorRef> supportingActors = readSupportingActors(handle, subject);
        List<Step> steps = readMainSteps(handle, subject);
        if (steps.isEmpty()) {
            // arkreq:mainStep is only sh:Warning severity at sh:minCount 1 (not sh:Violation), so
            // ShaclWriteGate#enforce lets a store-first (ADR-005) use case through with zero main
            // steps. UseCase's compact constructor rejects an empty steps list unconditionally -
            // mirror the primaryActor blank-node guard above: skip this one use case instead of
            // letting the constructor throw out of findByCode/findAll for the whole project
            // (issue #102).
            return Optional.empty();
        }
        if (!hasConsecutiveStepPositions(steps)) {
            // Nothing in SHACL forbids two arkreq:Step nodes under the same mainStep sharing an
            // arkreq:position - uniqueness is only enforced in-process by
            // UseCase.requireConsecutiveStepPositions, and store-first data (ADR-005) never runs
            // through that. Mirror the empty-steps guard above rather than letting the
            // constructor's IllegalArgumentException propagate out of findByCode/findAll for the
            // whole project (issue #102).
            return Optional.empty();
        }
        List<String> extensions = readExtensions(handle, subject);

        return Optional.of(new UseCase(
                new UseCaseId(ResourceId.of(subjectIriString)),
                code,
                literalOf(row, "title").getLexicalForm(),
                literalOf(row, "goal").getLexicalForm(),
                optionalLiteral(row, "scope"),
                optionalLiteral(row, "trigger"),
                new ActorRef(ResourceId.of(iriOf(row, "primaryActor").getIRIString())),
                supportingActors,
                optionalLiteral(row, "precondition"),
                optionalLiteral(row, "postcondition"),
                steps,
                extensions));
    }

    /**
     * Reads the {@code arkreq:supportingActor} edges back as actor references.
     *
     * <p><strong>No longer a join (issue #89).</strong> The edge's target IRI <em>is</em> the
     * {@link ActorRef} - no join into the sibling terms graph is needed, and none is performed
     * here. {@code FILTER(isIRI(?a))} mirrors
     * {@code KognioRdfRequirementRepository#readUsesTerms}: the property carries no
     * {@code sh:nodeKind} constraint, so a store-first (ADR-005) edge may legally target a blank
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

    private List<Step> readMainSteps(DatasetHandle handle, String subject) {
        String stepsQuery = "SELECT ?step ?position ?text WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                + subject + " <" + MAIN_STEP_PROPERTY + "> ?step . "
                + "?step <" + POSITION_PROPERTY + "> ?position ; <" + STEP_TEXT_PROPERTY + "> ?text } } "
                + "ORDER BY ?position";
        Map<String, List<RequirementRef>> realisesByStep = readMainStepRealises(handle, subject);
        return handle.sparqlQuery().select(stepsQuery)
                .map(row -> {
                    int position = Integer.parseInt(literalOf(row, "position").getLexicalForm());
                    String stepIri = iriOf(row, "step").getIRIString();
                    return new Step(position, literalOf(row, "text").getLexicalForm(),
                            realisesByStep.getOrDefault(stepIri, List.of()));
                })
                .toList();
    }

    /**
     * Reads each main-flow step's {@code arkreq:stepRealises} edges back as requirement
     * references.
     *
     * <p><strong>No longer a join (issue #89).</strong> The edge's target IRI <em>is</em> the
     * {@link RequirementRef} - no join into the sibling requirements graph is needed, and none is
     * performed here. {@code FILTER(isIRI(?req))} mirrors
     * {@code KognioRdfRequirementRepository#readUsesTerms}: the property carries no
     * {@code sh:nodeKind} constraint, so a store-first (ADR-005) edge may legally target a blank
     * node, which {@link ResourceId} cannot represent - excluded here, unreachable via the MCP
     * tools.</p>
     *
     * <p><strong>Keyed by the step's own IRI, not its derived {@code arkreq:position} (issue
     * #102).</strong> Nothing in SHACL forbids two distinct {@code arkreq:Step} nodes under the
     * same use case's {@code arkreq:mainStep} from sharing the same {@code arkreq:position} -
     * uniqueness is only enforced in-process by {@code UseCase.requireConsecutiveStepPositions},
     * and store-first data (ADR-005) never runs through that. Grouping by the derived position
     * integer instead of step identity would silently merge two such steps' {@code stepRealises}
     * targets under one key, the same class of bug issue #89 already fixed for
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
     * step at list index {@code i} must carry position {@code i + 1}. {@code steps} is read
     * ordered by {@code arkreq:position} (see {@link #readMainSteps}), so a store-first (ADR-005)
     * gap, duplicate or descending position is detected here before it ever reaches
     * {@link UseCase}'s constructor (issue #102).
     */
    private static boolean hasConsecutiveStepPositions(List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).position() != i + 1) {
                return false;
            }
        }
        return true;
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

    // ---- already-resolved reference conversion -----------------------------------------

    /**
     * Converts an already-resolved {@link RequirementRef} to an {@link IRI} for writing.
     * {@link ResourceId#of(String)} validates IRIREF-safety at construction (issue #83), so the
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
