// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.adr.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
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
 * <p>Maps an {@link Adr} to its opaque {@link AdrId} as the subject IRI (minted once by a
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}, never derived from the business code), stored
 * in one named graph shared by all decisions: the type triple ({@code a
 * arkarch:ArchitectureDecisionRecord}), the mandatory {@code dcterms:identifier} (the business code
 * {@code ADR-1}), the generic {@code arknet:name} literal, the {@code arkarch:adrStatus} individual,
 * the {@code arkarch:adrContext}/{@code arkarch:adrDecision} literals, the optional
 * {@code adrConsequences}/{@code adrAlternatives}/{@code decisionDate} literals, plus zero or more
 * {@code addressesRequirement}, {@code affectsContext} and {@code supersedes} edges. Every predicate
 * and type IRI comes from the shared {@link ArkarchVocabulary}, the same constants
 * {@code arknet-mcp}'s traceability read path traverses - a rename cannot desync the two sides. This
 * class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) and
 * {@link SimpleRdf} - it never imports RDF4J. The backend ({@link DatasetLifecycle} implementation)
 * is supplied by the composition root.</p>
 *
 * <p><strong>Create vs. compare-and-set update.</strong> The transactional mechanics - the
 * in-transaction {@code contains} existence checks, the SHACL gate, the commit-conflict translation
 * and the head comparison - live in the shared {@link WriteFunnel} (ADR-013/ADR-014), not here.
 * {@link #create} rejects an existing subject with {@link ResourceAlreadyExistsException} and a
 * business-code collision (by {@code dcterms:identifier}) with {@link DuplicateAdrCodeException};
 * {@link #compareAndUpdate} rejects a missing subject with {@link AdrNotFoundException} and a stale
 * {@code expectedHead} with {@link AdrConcurrentlyModifiedException}, and otherwise replaces the
 * subject's triples wholesale (see {@link #replaceTriples}). There is no unconditional update: every
 * correction to an already-recorded decision goes through the compare-and-set guard, so two
 * concurrent {@code adr_supersede} calls cannot silently lose one another's edge - the retrofit
 * issue #176 had to perform on {@code bc_link_term}, here from the first commit.</p>
 *
 * <p><strong>References arrive pre-resolved.</strong> {@link RequirementRef}/
 * {@link BoundedContextRef} carry the neighbour's opaque subject {@link ResourceId} directly -
 * resolving a human-typed code (e.g. {@code FR-1}, {@code BC-1}) against the shared project store,
 * and rejecting an unknown or ambiguous one, is done once by {@link KognioRdfRequirementLookup}/
 * {@link KognioRdfBoundedContextLookup} at the moment a decision is recorded (in the application
 * service), not here on every write. {@code ashapes:ADRShape} places no {@code sh:class} constraint
 * on either predicate - it carries no property shape for them at all - so this adapter needs no
 * validation-only asserted context: the plain
 * {@link ShaclWriteGate#enforce(io.kogn.rdf.terms.ReadableGraph)} suffices.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate the candidate instance graph
 * against the architecture SHACL shapes before the write transaction opens, throw
 * {@link WriteConstraintViolationException} on a violation, persist nothing - live in the shared
 * {@link WriteFunnel} (ADR-013). {@code ashapes:ADR-consequences} and
 * {@code ashapes:ADR-alternatives} are {@code sh:Warning}, not {@code sh:Violation}: a decision
 * recorded while it is still being argued has neither yet, and that must not block the write - the
 * same reasoning issue #66 applied to a bounded context's aggregates.</p>
 *
 * <p><strong>Row multiplication (the issue #81 pattern).</strong> None of the ADR shape's literal
 * property shapes except {@code ADR-identifier} and {@code ADR-status} carries an enforced
 * {@code sh:maxCount}, so a store-first (ADR-005) decision with two {@code arknet:name} or two
 * {@code arkarch:adrContext} triples legally multiplies a subject's SPARQL rows. Every read path
 * here groups rows per subject and takes the first-seen value deterministically for each scalar
 * field, logging a single {@code WARN} when more than one distinct value was collapsed.</p>
 */
public class KognioRdfAdrRepository implements AdrRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfAdrRepository.class);

    private static final String ARKNET_NAMESPACE = "https://w3id.org/arknet/core#";
    private static final String ADR_GRAPH = "https://w3id.org/arknet/model/adr";

    private static final String ADR_TYPE = ArkarchVocabulary.ADR_TYPE;
    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String NAME_PROPERTY = ARKNET_NAMESPACE + "name";
    private static final String STATUS_PROPERTY = ArkarchVocabulary.ADR_STATUS;
    private static final String CONTEXT_PROPERTY = ArkarchVocabulary.ADR_CONTEXT;
    private static final String DECISION_PROPERTY = ArkarchVocabulary.ADR_DECISION;
    private static final String CONSEQUENCES_PROPERTY = ArkarchVocabulary.ADR_CONSEQUENCES;
    private static final String ALTERNATIVES_PROPERTY = ArkarchVocabulary.ADR_ALTERNATIVES;
    private static final String DECISION_DATE_PROPERTY = ArkarchVocabulary.DECISION_DATE;
    private static final String ADDRESSES_REQUIREMENT_PROPERTY = ArkarchVocabulary.ADDRESSES_REQUIREMENT;
    private static final String AFFECTS_CONTEXT_PROPERTY = ArkarchVocabulary.AFFECTS_CONTEXT;
    private static final String SUPERSEDES_PROPERTY = ArkarchVocabulary.SUPERSEDES;
    private static final String SUPERSEDED_BY_PROPERTY = ArkarchVocabulary.SUPERSEDED_BY;
    private static final String RELATED_TO_PROPERTY = ArkarchVocabulary.RELATED_TO;

    /**
     * Orders {@code ADR-N} code strings by their parsed running number, not by {@link String}'s
     * natural (lexicographic) order - {@code "ADR-10"} sorts before {@code "ADR-2"} under natural
     * order once a project passes ten decisions. Mirrors {@code AdrService}'s identically-named,
     * identically-behaved helper (arknet-adr-core has no dependency this adapter could reuse it
     * through).
     */
    private static final Comparator<String> CODE_BY_RUNNING_NUMBER =
            Comparator.comparingInt(KognioRdfAdrRepository::runningNumber);

    private final DatasetLifecycle lifecycle;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from - read paths only,
     *                  the write path goes through {@code funnel} (must not be {@code null})
     * @param funnel    the shared write funnel (ADR-013) running the SHACL gate, dataset acquisition
     *                  and existence/head checks for every {@link #create}/{@link #compareAndUpdate}
     *                  (must not be {@code null})
     */
    KognioRdfAdrRepository(DatasetLifecycle lifecycle, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, Adr adr) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(adr, "adr");

        // ResourceId#of validates IRIREF-safety at construction, so the wrapped IRI is already
        // guaranteed safe to embed here - no separate check needed.
        String subjectIriString = adr.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(ADR_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, adr);

        funnel.create(new DatasetId(projectId.value()), ADR_GRAPH, subjectIriString,
                adr.code().value(), graph, null,
                () -> new ResourceAlreadyExistsException(projectId, adr.id().value()),
                () -> new DuplicateAdrCodeException(projectId, adr.code()),
                tx -> replaceTriples(tx, graphIri, subjectIri, subject, graph, false));
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, String expectedHead, Adr updated) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updated, "updated");

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(ADR_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, updated);

        funnel.compareAndUpdate(new DatasetId(projectId.value()), ADR_GRAPH, subjectIriString,
                expectedHead, graph, null,
                () -> new AdrNotFoundException(projectId, updated.code()),
                () -> new AdrConcurrentlyModifiedException(projectId, updated.code()),
                tx -> replaceTriples(tx, graphIri, subjectIri, subject, graph, true));
    }

    /**
     * Builds the candidate graph for one decision's triples: type, identifier, name, status, context
     * and decision, the three optional literals, and the three reference edges to their
     * already-resolved targets. Shared by {@link #create} and {@link #compareAndUpdate} so both
     * write paths serialise an {@link Adr} identically.
     */
    private Graph buildCandidateGraph(IRI subjectIri, Adr adr) {
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(ADR_TYPE));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(adr.code().value()));
        graph.add(subjectIri, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral(adr.name()));
        graph.add(subjectIri, rdf.createIRI(STATUS_PROPERTY), rdf.createIRI(statusIriFor(adr.status())));
        graph.add(subjectIri, rdf.createIRI(CONTEXT_PROPERTY), rdf.createLiteral(adr.context()));
        graph.add(subjectIri, rdf.createIRI(DECISION_PROPERTY), rdf.createLiteral(adr.decision()));
        if (adr.consequences() != null) {
            graph.add(subjectIri, rdf.createIRI(CONSEQUENCES_PROPERTY), rdf.createLiteral(adr.consequences()));
        }
        if (adr.alternatives() != null) {
            graph.add(subjectIri, rdf.createIRI(ALTERNATIVES_PROPERTY), rdf.createLiteral(adr.alternatives()));
        }
        if (adr.decisionDate() != null) {
            graph.add(subjectIri, rdf.createIRI(DECISION_DATE_PROPERTY),
                    rdf.createLiteral(adr.decisionDate().toString(), VocabXsd.DATE));
        }
        for (RequirementRef ref : adr.addressesRequirements()) {
            graph.add(subjectIri, rdf.createIRI(ADDRESSES_REQUIREMENT_PROPERTY),
                    rdf.createIRI(ref.value().value()));
        }
        for (BoundedContextRef ref : adr.affectsContexts()) {
            graph.add(subjectIri, rdf.createIRI(AFFECTS_CONTEXT_PROPERTY), rdf.createIRI(ref.value().value()));
        }
        for (AdrId superseded : adr.supersedes()) {
            graph.add(subjectIri, rdf.createIRI(SUPERSEDES_PROPERTY), rdf.createIRI(superseded.value().value()));
        }
        return graph;
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write transaction.
     * On an update it first captures the edges {@code graph} (built from the {@link Adr} record)
     * never carries, and re-attaches them after the rewrite - so a replace-by-identity write of a
     * store-first (ADR-005) decision carries them along instead of erasing them:
     *
     * <ul>
     * <li><strong>All</strong> {@code arkarch:supersededBy} and {@code arkarch:relatedTo} edges,
     * regardless of target kind: {@link Adr} has no field for either. {@code supersededBy} is the
     * {@code owl:inverseOf} partner this codebase deliberately never asserts itself (see
     * {@code SupersedeAdr}), and {@code relatedTo} has no tool at all - both are reachable only
     * store-first, and both would otherwise be lost on the very next {@code adr_set_status} call.
     * The same reasoning the bounded-context adapter applies to {@code arkddd:hasAggregate}.</li>
     * <li>{@code addressesRequirement}/{@code affectsContext}/{@code supersedes} edges whose target
     * is not an IRI - the read paths can never surface those, since {@link ResourceId} cannot
     * represent a blank node, so a round trip through the domain object would drop them (the same
     * preservation the requirements adapter does for {@code arkreq:usesTerm}, issue #65).</li>
     * </ul>
     */
    private void replaceTriples(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            boolean exists) {
        String selectPreserved = "SELECT ?p ?o WHERE { GRAPH <" + ADR_GRAPH + "> { " + subject + " ?p ?o } "
                + "FILTER( ?p IN (<" + SUPERSEDED_BY_PROPERTY + ">, <" + RELATED_TO_PROPERTY + ">) "
                + "|| ( ?p IN (<" + ADDRESSES_REQUIREMENT_PROPERTY + ">, <" + AFFECTS_CONTEXT_PROPERTY
                + ">, <" + SUPERSEDES_PROPERTY + ">) && !isIRI(?o) ) ) }";
        String deleteExisting = "DELETE WHERE { GRAPH <" + ADR_GRAPH + "> { " + subject + " ?p ?o } }";

        List<PreservedEdge> preserved = exists
                ? tx.select(selectPreserved)
                        .map(row -> new PreservedEdge(iriOf(row, "p"), termOf(row, "o")))
                        .toList()
                : List.of();
        if (exists) {
            tx.update(deleteExisting);
        }
        tx.add(graphIri, graph);
        if (!preserved.isEmpty()) {
            Graph preservedEdges = rdf.createGraph();
            for (PreservedEdge edge : preserved) {
                preservedEdges.add(subjectIri, edge.predicate(), edge.object());
            }
            tx.add(graphIri, preservedEdges);
        }
    }

    /** One edge {@link #replaceTriples} captures before the rewrite and re-attaches afterwards. */
    private record PreservedEdge(IRI predicate, RDFTerm object) {
    }

    @Override
    public Optional<Adr> findByCode(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return readSingle(handle, code);
        }
    }

    @Override
    public Optional<CurrentAdr> findCurrentByCode(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return readSingleWithHead(handle, code);
        }
    }

    /**
     * Reads a decision's current state together with its concurrency token. The scalar-field rows
     * and the head itself come from this method's one query call (analogous to
     * {@code KognioRdfBoundedContextRepository#findCurrentByCode}) - one snapshot, which is the
     * load-bearing guarantee, not an ordering of clauses within that query. The three edge lists are
     * filled in by further, independent queries; those later reads are safe precisely because they
     * can only be fresher, never staler, than the head: a concurrent funnel write landing in between
     * moves the head, so {@link #compareAndUpdate} then fails its comparison and the caller re-reads
     * instead of silently overwriting a state it never actually saw.
     */
    private Optional<CurrentAdr> readSingleWithHead(DatasetHandle handle, AdrCode code) {
        String query = "SELECT ?s ?name ?status ?context ?decision "
                + "?consequences ?alternatives ?decisionDate ?head WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + adrWhereBody("\"" + SparqlTerms.escape(code.value()) + "\"") + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?s <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        Map<String, AdrAssembly> bySubject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> {
            String subjectIri = iriOf(row, "s").getIRIString();
            bySubject.computeIfAbsent(subjectIri, iri -> new AdrAssembly(new AdrId(ResourceId.of(iri)), code))
                    .addCandidatesFrom(row);
        });
        return bySubject.entrySet().stream()
                .findFirst()
                .map(entry -> {
                    String subject = SparqlTerms.iriRef(entry.getKey());
                    AdrAssembly assembly = entry.getValue();
                    Adr adr = assembly.toAdr(
                            readRefs(handle.sparqlQuery()::select, subject, ADDRESSES_REQUIREMENT_PROPERTY,
                                    id -> new RequirementRef(id)),
                            readRefs(handle.sparqlQuery()::select, subject, AFFECTS_CONTEXT_PROPERTY,
                                    id -> new BoundedContextRef(id)),
                            readRefs(handle.sparqlQuery()::select, subject, SUPERSEDES_PROPERTY, AdrId::new));
                    return new CurrentAdr(adr, assembly.head());
                });
    }

    @Override
    public List<Adr> findAll(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?s ?identifier ?name ?status ?context ?decision "
                + "?consequences ?alternatives ?decisionDate WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + adrWhereBody("?identifier") + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<String, List<RequirementRef>> requirements = readRefsBySubject(handle,
                    ADDRESSES_REQUIREMENT_PROPERTY, id -> new RequirementRef(id));
            Map<String, List<BoundedContextRef>> contexts = readRefsBySubject(handle,
                    AFFECTS_CONTEXT_PROPERTY, id -> new BoundedContextRef(id));
            Map<String, List<AdrId>> supersedes = readRefsBySubject(handle,
                    SUPERSEDES_PROPERTY, AdrId::new);

            Map<String, AdrAssembly> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> assemblyFor(bySubject, row).addCandidatesFrom(row));
            return bySubject.entrySet().stream()
                    .map(entry -> entry.getValue().toAdr(
                            requirements.getOrDefault(entry.getKey(), List.of()),
                            contexts.getOrDefault(entry.getKey(), List.of()),
                            supersedes.getOrDefault(entry.getKey(), List.of())))
                    .toList();
        }
    }

    @Override
    public Map<AdrId, AdrCode> findCodesByIds(ProjectId projectId, Collection<AdrId> ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return Map.of();
        }

        // ResourceId#of validates IRIREF-safety at construction, so every id here is already
        // guaranteed safe to embed - which is what keeps this method's "never rejects" contract.
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
                    // putIfAbsent, not put: dcterms:identifier carries no enforceable sh:maxCount
                    // for a store-first subject, and the first row simply wins.
                    byId.putIfAbsent(new AdrId(ResourceId.of(iriOf(row, "s").getIRIString())),
                            new AdrCode(literalOf(row, "identifier").getLexicalForm())));
            return Map.copyOf(byId);
        }
    }

    @Override
    public List<AdrCode> findSupersedingCodes(ProjectId projectId, AdrId supersededId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(supersededId, "supersededId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "?s <" + SUPERSEDES_PROPERTY + "> " + SparqlTerms.iriRef(supersededId.value().value()) + " . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            // Sorted by running number (not String's lexicographic order - "ADR-10" would otherwise
            // sort before "ADR-2") and deduplicated: RDF has no intrinsic statement order, and a
            // subject with two identifier triples would otherwise report the same successor twice.
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .collect(Collectors.toCollection(() -> new TreeSet<>(CODE_BY_RUNNING_NUMBER)))
                    .stream()
                    .map(AdrCode::new)
                    .toList();
        }
    }

    // ---- read helpers ------------------------------------------------------------------

    /**
     * Reads exactly one decision by its business code over an already-acquired handle, grouping the
     * multi-row cross product the unconstrained literal predicates can produce (the issue #81
     * pattern) and joining its three edge lists. Shared by {@link #findByCode} and
     * {@link #findCurrentByCode} so the two single-decision read paths cannot drift apart
     * field-by-field.
     */
    private Optional<Adr> readSingle(DatasetHandle handle, AdrCode code) {
        String query = "SELECT ?s ?name ?status ?context ?decision "
                + "?consequences ?alternatives ?decisionDate WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + adrWhereBody("\"" + SparqlTerms.escape(code.value()) + "\"") + "} }";

        Map<String, AdrAssembly> bySubject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> {
            String subjectIri = iriOf(row, "s").getIRIString();
            bySubject.computeIfAbsent(subjectIri, iri -> new AdrAssembly(new AdrId(ResourceId.of(iri)), code))
                    .addCandidatesFrom(row);
        });
        return bySubject.entrySet().stream()
                .findFirst()
                .map(entry -> {
                    String subject = SparqlTerms.iriRef(entry.getKey());
                    return entry.getValue().toAdr(
                            readRefs(handle.sparqlQuery()::select, subject, ADDRESSES_REQUIREMENT_PROPERTY,
                                    id -> new RequirementRef(id)),
                            readRefs(handle.sparqlQuery()::select, subject, AFFECTS_CONTEXT_PROPERTY,
                                    id -> new BoundedContextRef(id)),
                            readRefs(handle.sparqlQuery()::select, subject, SUPERSEDES_PROPERTY, AdrId::new));
                });
    }

    /**
     * The WHERE body shared by every scalar-field read: the mandatory joins (type, identifier, name,
     * status, context, decision) plus the three optional literal joins. {@code identifierPattern} is
     * either the variable {@code ?identifier} (list read) or an escaped literal scoping the read to
     * one code (single read). {@code FILTER(isIRI(?s))} guards against a store-first decision on a
     * blank-node subject: {@code ashapes:ADRShape} carries no {@code sh:nodeKind sh:IRI}, and an
     * unguarded cast would take down every other decision in the project with it (the same guard
     * issue #104 added to the glossary adapter).
     */
    private static String adrWhereBody(String identifierPattern) {
        return "?s a <" + ADR_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> " + identifierPattern + " . "
                + "?s <" + NAME_PROPERTY + "> ?name . "
                + "?s <" + STATUS_PROPERTY + "> ?status . "
                + "?s <" + CONTEXT_PROPERTY + "> ?context . "
                + "?s <" + DECISION_PROPERTY + "> ?decision . "
                + "OPTIONAL { ?s <" + CONSEQUENCES_PROPERTY + "> ?consequences } "
                + "OPTIONAL { ?s <" + ALTERNATIVES_PROPERTY + "> ?alternatives } "
                + "OPTIONAL { ?s <" + DECISION_DATE_PROPERTY + "> ?decisionDate } "
                + "FILTER(isIRI(?s)) ";
    }

    /**
     * Reads one predicate's IRI-valued edges of a single decision. Ordered by target IRI (RDF has no
     * intrinsic statement order and {@link Adr} compares its reference lists positionally). A
     * store-first blank-node target is excluded by {@code FILTER(isIRI(?target))} - it is preserved
     * across an update by {@link #replaceTriples} but cannot be materialised into a reference.
     */
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

    /** Bulk variant of {@link #readRefs}: every decision's edges on one predicate in one query. */
    private static <T> Map<String, List<T>> readRefsBySubject(DatasetHandle handle, String predicate,
            Function<ResourceId, T> wrap) {
        String query = "SELECT ?s ?target WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "?s <" + predicate + "> ?target } "
                + "FILTER(isIRI(?s) && isIRI(?target)) } ORDER BY ?s ?target";
        Map<String, List<T>> bySubject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> {
            List<T> targets = bySubject.computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>());
            T wrapped = wrap.apply(ResourceId.of(iriOf(row, "target").getIRIString()));
            if (!targets.contains(wrapped)) {
                targets.add(wrapped);
            }
        });
        return bySubject;
    }

    private static AdrAssembly assemblyFor(Map<String, AdrAssembly> bySubject, BindingSet row) {
        String subjectIri = iriOf(row, "s").getIRIString();
        return bySubject.computeIfAbsent(subjectIri, iri -> new AdrAssembly(
                new AdrId(ResourceId.of(iri)),
                new AdrCode(literalOf(row, "identifier").getLexicalForm())));
    }

    /**
     * Mutable per-subject accumulator collecting a decision's scalar-field candidates across rows
     * (the issue #81 pattern), then choosing one of each deterministically (first-seen) when the
     * decision is finally materialised, logging a {@code WARN} if more than one distinct value was
     * collected for a field.
     */
    private static final class AdrAssembly {

        private final AdrId id;
        private final AdrCode code;
        private final Map<String, List<Object>> candidates = new LinkedHashMap<>();

        private AdrAssembly(AdrId id, AdrCode code) {
            this.id = id;
            this.code = code;
        }

        private void addCandidatesFrom(BindingSet row) {
            add("name", literalOrNull(row, "name"));
            add("status", statusOf(row));
            add("context", literalOrNull(row, "context"));
            add("decision", literalOrNull(row, "decision"));
            add("consequences", literalOrNull(row, "consequences"));
            add("alternatives", literalOrNull(row, "alternatives"));
            add("decisionDate", decisionDateOf(row));
            add("head", headOf(row));
        }

        private void add(String field, Object value) {
            if (value != null) {
                candidates.computeIfAbsent(field, key -> new ArrayList<>()).add(value);
            }
        }

        private Adr toAdr(List<RequirementRef> requirements, List<BoundedContextRef> contexts,
                List<AdrId> supersedes) {
            return new Adr(id, code,
                    (String) firstDistinct("name"),
                    (AdrStatus) firstDistinct("status"),
                    (String) firstDistinct("context"),
                    (String) firstDistinct("decision"),
                    (String) firstDistinct("consequences"),
                    (String) firstDistinct("alternatives"),
                    (LocalDate) firstDistinct("decisionDate"),
                    requirements, contexts, supersedes);
        }

        /** The concurrency token collected alongside the scalar fields by {@link #readSingleWithHead}. */
        private String head() {
            return (String) firstDistinct("head");
        }

        private Object firstDistinct(String field) {
            List<Object> values = candidates.getOrDefault(field, List.of());
            if (values.isEmpty()) {
                return null;
            }
            long distinctCount = values.stream().distinct().count();
            if (distinctCount > 1) {
                LOG.warn("ADR {}: field '{}' had {} distinct values, returning the first",
                        id.value().value(), field, distinctCount);
            }
            return values.get(0);
        }
    }

    // ---- term helpers ------------------------------------------------------------------

    /** Parses the running number from a code such as {@code ADR-7} (0 if not parseable). */
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
        };
    }

    /**
     * Maps a lifecycle individual back to the Java enum, or {@code null} for one of the three
     * shipped-but-unimplemented values ({@code Rejected}/{@code Deprecated}/{@code Superseded}) and
     * for anything else. A {@code null} makes {@link Adr}'s constructor reject the row rather than
     * silently mislabel a decision - the honest outcome while {@link AdrStatus} covers a deliberate
     * subset of the ontology.
     */
    private static AdrStatus statusFromIri(String iri) {
        if (ArkarchVocabulary.PROPOSED.equals(iri)) {
            return AdrStatus.PROPOSED;
        }
        if (ArkarchVocabulary.ACCEPTED.equals(iri)) {
            return AdrStatus.ACCEPTED;
        }
        return null;
    }

    private static AdrStatus statusOf(BindingSet row) {
        return row.getValue("status")
                .filter(IRI.class::isInstance)
                .map(value -> statusFromIri(((IRI) value).getIRIString()))
                .orElse(null);
    }

    /**
     * Reads the {@code ?head} binding {@link #readSingleWithHead}'s query optionally projects. Absent
     * for every other caller of {@link AdrAssembly#addCandidatesFrom} - their queries never bind
     * {@code ?head} - so this simply returns {@code null} for them, matching the pre-existing
     * absent-head handling.
     */
    private static String headOf(BindingSet row) {
        return row.getValue("head")
                .filter(IRI.class::isInstance)
                .map(value -> ((IRI) value).getIRIString())
                .orElse(null);
    }

    /**
     * Reads {@code arkarch:decisionDate} back as a {@link LocalDate}. The shape places no constraint
     * on this predicate at all, so a store-first (ADR-005) value need not even be a date - an
     * unparseable literal is skipped with a {@code WARN} instead of taking the whole read down.
     */
    private static LocalDate decisionDateOf(BindingSet row) {
        String lexical = literalOrNull(row, "decisionDate");
        if (lexical == null) {
            return null;
        }
        try {
            return LocalDate.parse(lexical);
        } catch (DateTimeParseException e) {
            LOG.warn("ignoring unparseable arkarch:decisionDate '{}'", lexical);
            return null;
        }
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

    /**
     * Reads a binding as the bare {@link RDFTerm} it is, without narrowing it to {@link IRI} - used
     * where the binding's kind is not known in advance (a preserved edge's target may legally be a
     * blank node).
     */
    private static RDFTerm termOf(BindingSet row, String name) {
        return row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
