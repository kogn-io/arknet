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
import de.hauschel.arknet.adr.domain.AdrNotDeletableException;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrReferencedException;
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
 * {@code addressesRequirement}, {@code affectsContext}, {@code supersededBy} and {@code relatedTo}
 * edges. Every predicate
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
 * other bounded contexts had to perform later on their own write paths, here from the first commit.</p>
 *
 * <p><strong>Delete.</strong> {@link #delete} runs through the funnel's own guarded delete: the
 * reference check first, inside that transaction, then the whole-subject removal. The tombstone it
 * leaves behind (the last revision invalidated, the head pointer gone, the chain intact) is the
 * funnel's, but the deleted decision's business code is not - that one this adapter hangs on the
 * revision itself so {@link #findRetainedCodes} can keep the number out of circulation. See
 * {@link #retainCode} for why nothing else could carry it.</p>
 *
 * <p><strong>References arrive pre-resolved.</strong> {@link RequirementRef}/
 * {@link BoundedContextRef} carry the neighbour's opaque subject {@link ResourceId} directly -
 * resolving a human-typed code (e.g. {@code FR-1}, {@code BC-1}) against the shared project store,
 * and rejecting an unknown or ambiguous one, is done once by {@link KognioRdfRequirementLookup}/
 * {@link KognioRdfBoundedContextLookup} at the moment a decision is recorded (in the application
 * service), not here on every write. {@code ashapes:ADRShape} places no {@code sh:class} constraint
 * on either predicate - it carries no property shape for them at all - so neither needs a
 * validation-only asserted context.</p>
 *
 * <p><strong>Validation-only asserted context for {@code relatedTo} and {@code supersededBy}.</strong>
 * Both reference predicates that carry a {@code sh:class} constraint - {@code ashapes:ADR-relatedTo}
 * and {@code ashapes:ADR-supersededBy} (kogn-io/arknet#357), each {@code sh:nodeKind sh:IRI} plus
 * {@code sh:class arkarch:ArchitectureDecisionRecord} - point at a peer/successor decision whose own
 * triples live in its own already-committed write, not in this candidate graph.
 * {@link #crossReferenceAssertedContext} therefore hands the gate a validation-only copy of each such
 * target's mandatory fields - never persisted, the pattern {@code KognioRdfRequirementRepository}
 * uses for {@code oslc_rm:constrainedBy}. A bare type triple would not do here: {@code ashapes:ADRShape}
 * targets {@code arkarch:ArchitectureDecisionRecord} and lives in this very shapes file, so
 * asserting the target's type alone would make the gate validate a decision stripped of its name,
 * status, context and decision and refuse the write. Only the fields those five {@code sh:Violation}
 * shapes constrain are copied: pulling the target's own reference edges across would demand its
 * peers be typed too, and so on outwards.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate the candidate instance graph
 * against the architecture SHACL shapes before the write transaction opens, throw
 * {@link WriteConstraintViolationException} on a violation, persist nothing - live in the shared
 * {@link WriteFunnel} (ADR-013). {@code ashapes:ADR-consequences} and
 * {@code ashapes:ADR-alternatives} are {@code sh:Warning}, not {@code sh:Violation}: a decision
 * recorded while it is still being argued has neither yet, and that must not block the write - the
 * same reasoning applied to a bounded context's aggregates.</p>
 *
 * <p><strong>Row multiplication.</strong> None of the ADR shape's literal
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

    /**
     * The prefix every code this hexagon mints carries. Used only by {@link #findRetainedCodes} to
     * tell an ADR code kept on a tombstoned revision from any other business code a future writer
     * might retain the same way in the shared provenance graph (see that method).
     */
    private static final String CODE_PREFIX = "ADR-";

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
     * order once a project passes ten decisions. Falls back to natural string order when the running
     * number ties, which every well-formed {@code ADR-N} code only ever does with itself - the
     * fallback exists for two distinct, non-conforming store-first (ADR-005) codes that both parse to
     * 0 (see {@link #runningNumber}): without it this comparator returns 0 for two different codes,
     * which is inconsistent with {@link Object#equals} and silently collapses both into one entry in
     * a {@link TreeSet} (see {@link #findSupersedingCodes}). Mirrors {@code AdrService}'s
     * identically-named, identically-behaved helper (arknet-adr-core has no dependency this adapter
     * could reuse it through).
     */
    private static final Comparator<String> CODE_BY_RUNNING_NUMBER =
            Comparator.<String>comparingInt(KognioRdfAdrRepository::runningNumber)
                    .thenComparing(Comparator.naturalOrder());

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
                adr.code().value(), graph, crossReferenceAssertedContext(projectId, adr),
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
                expectedHead, graph, crossReferenceAssertedContext(projectId, updated),
                () -> new AdrNotFoundException(projectId, updated.code()),
                () -> new AdrConcurrentlyModifiedException(projectId, updated.code()),
                tx -> replaceTriples(tx, graphIri, subjectIri, subject, graph, true));
    }

    /**
     * Builds the candidate graph for one decision's triples: type, identifier, name, status, context
     * and decision, the three optional literals, and the four reference edges to their
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
        if (adr.supersededBy() != null) {
            graph.add(subjectIri, rdf.createIRI(SUPERSEDED_BY_PROPERTY),
                    rdf.createIRI(adr.supersededBy().value().value()));
        }
        // Forward direction only, although arkarch:relatedTo is an owl:SymmetricProperty: nothing
        // here reasons over symmetry, and the peer's mirror triple would be a second hand-maintained
        // assertion of one fact. The reader's merged view comes from AdrService, not from the store.
        for (AdrId peer : adr.relatedTo()) {
            graph.add(subjectIri, rdf.createIRI(RELATED_TO_PROPERTY), rdf.createIRI(peer.value().value()));
        }
        return graph;
    }

    /**
     * Collects the validation-only triples both {@code ashapes:ADR-relatedTo} and
     * {@code ashapes:ADR-supersededBy}'s {@code sh:class arkarch:ArchitectureDecisionRecord}
     * constraints need to be satisfiable: for each {@code relatedTo} peer and the
     * {@code supersededBy} target (kogn-io/arknet#357), its type plus the five fields
     * {@code ashapes:ADRShape}'s {@code sh:Violation} property shapes require (identifier, name,
     * status, context, decision). Deliberately nothing else - see the class javadoc for why a bare
     * type triple is too little and the peer's whole subject too much. Never persisted; the gate
     * merges it into the validated data for one call.
     */
    private Graph crossReferenceAssertedContext(ProjectId projectId, Adr adr) {
        Graph assertedContext = rdf.createGraph();
        List<AdrId> peers = adr.supersededBy() == null
                ? adr.relatedTo()
                : Stream.concat(adr.relatedTo().stream(), Stream.of(adr.supersededBy())).distinct().toList();
        if (peers.isEmpty()) {
            return assertedContext;
        }
        // ResourceId#of validated IRIREF-safety at construction, so every peer IRI is safe to embed.
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
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write transaction.
     * On an update it first captures the edges {@code graph} (built from the {@link Adr} record)
     * never carries, and re-attaches them after the rewrite - so a replace-by-identity write of a
     * store-first (ADR-005) decision carries them along instead of erasing them:
     *
     * <ul>
     * <li><strong>All</strong> {@code arkarch:supersedes} edges, regardless of target kind
     * (kogn-io/arknet#357): {@link Adr} carries no field for this pre-#357 predicate any more - the
     * written edge moved onto {@link Adr#supersededBy()}, on the <em>superseded</em> decision - so a
     * store-first record still asserting the old forward-only {@code arkarch:supersedes} shape would
     * otherwise lose it on its very next write through this adapter.
     * {@code arkarch:supersededBy} used to be preserved this way and deliberately no longer is: it is
     * a field of {@link Adr} now, so the candidate graph carries every edge that should survive -
     * preserving it on top would make {@code adr_supersede}'s own write (and any correction of it)
     * impossible to carry out, the same reasoning that dropped {@code arkarch:relatedTo} from this
     * list once it became a field.</li>
     * <li>{@code addressesRequirement}/{@code affectsContext} edges whose target is not an IRI - the
     * read paths can never surface those, since {@link ResourceId} cannot represent a blank node, so
     * a round trip through the domain object would drop them (the same preservation the requirements
     * adapter does for {@code arkreq:usesTerm}). {@code relatedTo}/{@code supersededBy} are
     * deliberately left out of this list: {@code ashapes:ADR-relatedTo}/{@code ashapes:ADR-supersededBy}
     * both shape their target {@code sh:nodeKind sh:IRI} with {@code sh:Violation}, so a blank-node
     * target is exactly what the shape forbids - re-attaching one past the gate would write back the
     * very state a full-store validation flags as broken.</li>
     * </ul>
     */
    private void replaceTriples(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            boolean exists) {
        String selectPreserved = "SELECT ?p ?o WHERE { GRAPH <" + ADR_GRAPH + "> { " + subject + " ?p ?o } "
                + "FILTER( ?p = <" + SUPERSEDES_PROPERTY + "> "
                + "|| ( ?p IN (<" + ADDRESSES_REQUIREMENT_PROPERTY + ">, <" + AFFECTS_CONTEXT_PROPERTY
                + ">) && !isIRI(?o) ) ) }";
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

    /**
     * Deletes the decision identified by {@code code}, and every triple it carries in
     * {@link #ADR_GRAPH}, from the project. Resolves the subject by code outside any transaction
     * (mirroring {@link #findByCode}'s own read), then hands the whole check-and-delete to
     * {@link WriteFunnel#delete}: {@link #rejectIfNotProposed} and {@link #rejectIfReferenced} run
     * first, inside the funnel's own write transaction, so both checks and the removal share one
     * atomic snapshot and a status change or a {@code relatedTo} edge written in between cannot slip
     * through. Only once the decision is still {@code PROPOSED} and nothing points at it does the
     * body remove the subject's triples wholesale.
     *
     * <p><strong>The business code outlives the resource.</strong> The funnel's tombstone
     * (ADR-013/ADR-014) keeps the revision chain but records no code of its own - it writes
     * {@code prov:specializationOf} against the <em>opaque</em> subject IRI, and the readable
     * {@code ADR-7} lives solely on the model triple this delete removes. {@link #retainCode}
     * therefore hangs that code on the revision about to be tombstoned, which is what lets
     * {@link #findRetainedCodes} keep the number out of circulation afterwards. Deliberately local to
     * this adapter rather than folded into the shared funnel: no other bounded context needs it yet,
     * and whether the mechanism should be shared is a question of its own.</p>
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

        funnel.delete(dataset, ADR_GRAPH, subjectIriString,
                () -> new AdrNotFoundException(projectId, code),
                tx -> {
                    rejectIfNotProposed(tx, code, subjectIriString);
                    rejectIfReferenced(tx, projectId, code, subjectIriString);
                    retainCode(tx, subjectIriString, code);
                    tx.update("DELETE WHERE { GRAPH <" + ADR_GRAPH + "> { " + subject + " ?p ?o } }");
                });
    }

    /**
     * Rejects the delete, without touching a single triple, unless {@code subjectIri} is still
     * {@link AdrStatus#PROPOSED}. Runs inside the live write transaction {@link WriteFunnel#delete}
     * hands its {@code body}, so this - unlike the application service's own didactic pre-check in
     * {@code AdrService#delete} - is the race-free one: a status transition committed between that
     * pre-check and this transaction (e.g. a concurrent {@code adr_set_status ACCEPTED}) is read here
     * and rejected instead of silently deleting a decision that has since been made.
     */
    private void rejectIfNotProposed(DatasetTx tx, AdrCode code, String subjectIri) {
        String subject = SparqlTerms.iriRef(subjectIri);
        String query = "SELECT ?status WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + subject + " <" + STATUS_PROPERTY + "> ?status } }";
        AdrStatus status = tx.select(query).findFirst().map(KognioRdfAdrRepository::statusOf).orElse(null);
        if (status != AdrStatus.PROPOSED) {
            throw new AdrNotDeletableException(code, status);
        }
    }

    /**
     * Rejects the delete, without touching a single triple, while another decision still points at
     * {@code subjectIri} via {@code arkarch:supersedes} (pre-#357 legacy), {@code arkarch:supersededBy}
     * (kogn-io/arknet#357's current shape - {@code subjectIri} is that decision's own successor) or
     * {@code arkarch:relatedTo}, naming every referrer by its business code. Runs inside the live
     * write transaction {@link WriteFunnel#delete} hands its {@code body}, so this - unlike the
     * application service's own didactic pre-check ({@link de.hauschel.arknet.adr.application.AdrService
     * #rejectIfReferenced}, backed by {@link #findSupersessionReferrers}) - is the race-free one.
     *
     * <p>Scoped to {@link #ADR_GRAPH} rather than searched across every named graph the way the
     * glossary's and the actor register's checks are: all three predicates run decision-to-decision,
     * are written by this adapter alone and are read back from this one graph by
     * {@link #findSupersessionReferrers}/{@link #findRelatedCodes}. Widening the search here and
     * nowhere else would refuse a delete over an edge no read path of this hexagon ever surfaces.</p>
     */
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

    /**
     * Collects the codes of every decision pointing at {@code target} with one predicate, sorted by
     * running number and deduplicated for the same two reasons {@link #findSupersessionReferrers}
     * sorts and deduplicates: RDF has no intrinsic statement order, and a referrer carrying two
     * identifier triples would otherwise be named twice in one rejection message.
     */
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
     * Hangs the deleted decision's business code on the revision {@link WriteFunnel#delete} is about
     * to tombstone, as a {@code dcterms:identifier} in the provenance graph - the only place a code
     * can survive its resource, and what {@link #findRetainedCodes} reads back. The head pointer is
     * still in place while this runs: the funnel reads it before the body and removes it only
     * afterwards, so the revision this attaches to is exactly the one that gets
     * {@code prov:invalidatedAtTime}.
     *
     * <p><strong>Known gap, not repaired here.</strong> A decision written before revisions were
     * recorded at all has no head, hence no revision to hang the code on - its number can be handed
     * out a second time. Logged as a {@code WARN} rather than fabricated: a revision minted at delete
     * time would claim a write that never happened.</p>
     */
    private void retainCode(DatasetTx tx, String subjectIri, AdrCode code) {
        String subject = SparqlTerms.iriRef(subjectIri);
        Optional<IRI> head = tx.select("SELECT ?head WHERE { GRAPH <"
                        + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                        + subject + " <" + ArkprovVocabulary.HEAD + "> ?head } }")
                .map(row -> row.getValue("head").orElse(null))
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .findFirst();
        if (head.isEmpty()) {
            LOG.warn("ADR {} has no recorded revision, so its code {} cannot be kept out of "
                    + "circulation and may be assigned again", subjectIri, code.value());
            return;
        }
        Graph retained = rdf.createGraph();
        retained.add(head.get(), VocabDct.IDENTIFIER, rdf.createLiteral(code.value()));
        tx.add(rdf.createIRI(ArkprovVocabulary.PROVENANCE_GRAPH), retained);
    }

    /**
     * Reads back the codes {@link #retainCode} kept: the {@code dcterms:identifier} of every
     * tombstoned revision in the project's provenance graph.
     *
     * <p>The provenance graph is shared by every bounded context, so the read is narrowed twice: to
     * revisions that actually carry {@code prov:invalidatedAtTime} (a live resource's revisions never
     * do), and to identifiers bearing this hexagon's {@link #CODE_PREFIX}. The second filter is what
     * keeps a neighbour that later retains its own codes the same way from inflating this
     * hexagon's numbering - the deleted resource's type triple is gone by then, so its own code is
     * the only thing left to tell the two apart.</p>
     */
    @Override
    public List<AdrCode> findRetainedCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?revision <" + ArkprovVocabulary.INVALIDATED_AT_TIME + "> ?invalidatedAt . "
                + "?revision <" + IDENTIFIER_PROPERTY + "> ?identifier } "
                + "FILTER(STRSTARTS(STR(?identifier), \"" + CODE_PREFIX + "\")) }";

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
                            firstRefOrNull(handle.sparqlQuery()::select, subject, SUPERSEDED_BY_PROPERTY,
                                    entry.getKey()),
                            readRefs(handle.sparqlQuery()::select, subject, RELATED_TO_PROPERTY, AdrId::new));
                    // toAdr returns null when adrStatus could not be decoded (see #toAdr) - null
                    // here, rather than Optional.map wrapping a CurrentAdr around a null Adr.
                    return adr == null ? null : new CurrentAdr(adr, assembly.head());
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
            Map<String, List<AdrId>> supersededBy = readRefsBySubject(handle,
                    SUPERSEDED_BY_PROPERTY, AdrId::new);
            Map<String, List<AdrId>> relatedTo = readRefsBySubject(handle,
                    RELATED_TO_PROPERTY, AdrId::new);

            Map<String, AdrAssembly> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> assemblyFor(bySubject, row).addCandidatesFrom(row));
            return bySubject.entrySet().stream()
                    .map(entry -> entry.getValue().toAdr(
                            requirements.getOrDefault(entry.getKey(), List.of()),
                            contexts.getOrDefault(entry.getKey(), List.of()),
                            firstOrNull(entry.getKey(), SUPERSEDED_BY_PROPERTY,
                                    supersededBy.getOrDefault(entry.getKey(), List.of())),
                            relatedTo.getOrDefault(entry.getKey(), List.of())))
                    // toAdr returns null when adrStatus could not be decoded (see #toAdr) - that one
                    // decision is skipped rather than taking the whole listing down with it.
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    /**
     * Reads every recorded decision's business code straight off {@code dcterms:identifier}, without
     * joining a single one of the optional or scalar fields {@link #adrWhereBody} requires and
     * {@link AdrAssembly#toAdr} can therefore reject - the whole point (kogn-io/arknet#359, see
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

        String subject = SparqlTerms.iriRef(supersededId.value().value());
        // Two sources, unioned (kogn-io/arknet#357): supersededId's own current-model
        // arkarch:supersededBy field (a forward read on the decision itself), and a reverse read of
        // the pre-#357 arkarch:supersedes triple a store-first record may still carry, written on
        // the superseding decision back when that direction was the one asserted.
        String query = "SELECT ?identifier WHERE { GRAPH <" + ADR_GRAPH + "> { "
                + "{ " + subject + " <" + SUPERSEDED_BY_PROPERTY + "> ?successor . "
                + "?successor <" + IDENTIFIER_PROPERTY + "> ?identifier } "
                + "UNION "
                + "{ ?predecessor <" + SUPERSEDES_PROPERTY + "> " + subject + " . "
                + "?predecessor <" + IDENTIFIER_PROPERTY + "> ?identifier } } }";

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

    @Override
    public List<AdrCode> findSupersededCodes(ProjectId projectId, AdrId supersedingId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(supersedingId, "supersedingId");

        String subject = SparqlTerms.iriRef(supersedingId.value().value());
        // The mirror of findSupersedingCodes: a reverse read of every decision naming
        // supersedingId in its own current-model arkarch:supersededBy field, unioned with
        // supersedingId's own pre-#357 arkarch:supersedes triple, should a store-first record still
        // carry one.
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
        // The two external reverse edges only: a decision naming target as what it (legacy-)
        // supersedes, or a decision naming target as its own (current-model) successor. target's own
        // outgoing edges (its own supersededBy field, or a legacy supersedes triple it might still
        // carry) are deliberately excluded - see the port javadoc for why those are not a
        // dangling-reference risk.
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
            // Sorted and deduplicated exactly as findSupersedingCodes is, and for the same two
            // reasons: RDF has no intrinsic statement order, and a peer with two identifier triples
            // would otherwise be reported twice.
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
     * multi-row cross product the unconstrained literal predicates can produce
     * and joining its three edge lists. Shared by {@link #findByCode} and
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
                            firstRefOrNull(handle.sparqlQuery()::select, subject, SUPERSEDED_BY_PROPERTY,
                                    entry.getKey()),
                            readRefs(handle.sparqlQuery()::select, subject, RELATED_TO_PROPERTY, AdrId::new));
                });
    }

    /**
     * The WHERE body shared by every scalar-field read: the mandatory joins (type, identifier, name,
     * status, context, decision) plus the three optional literal joins. {@code identifierPattern} is
     * either the variable {@code ?identifier} (list read) or an escaped literal scoping the read to
     * one code (single read). {@code FILTER(isIRI(?s))} guards against a store-first decision on a
     * blank-node subject: {@code ashapes:ADRShape} carries no {@code sh:nodeKind sh:IRI}, and an
     * unguarded cast would take down every other decision in the project with it (the same guard
     * added to the glossary adapter).
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

    /**
     * Reads {@code arkarch:supersededBy} as the single, nullable value it is (kogn-io/arknet#357):
     * {@code null} if the subject carries none, the sole value if it carries one. A store-first
     * subject carrying more than one - {@code ashapes:ADR-supersededBy}'s {@code sh:maxCount 1} is
     * enforceable only at write time, never against data the gate never saw - is collapsed to the
     * first (ordered by target IRI, same as {@link #readRefs}) with a {@code WARN}, the same
     * first-seen-wins deterministic choice {@link AdrAssembly#firstDistinct} makes for a
     * multi-valued literal field.
     */
    private static AdrId firstRefOrNull(Function<String, Stream<BindingSet>> selectFn, String subject,
            String predicate, String subjectIri) {
        return firstOrNull(subjectIri, predicate, readRefs(selectFn, subject, predicate, AdrId::new));
    }

    /**
     * Collapses a predicate's collected values to the first (or {@code null} if none), logging a
     * {@code WARN} when more than one distinct value was found - shared by {@link #firstRefOrNull}
     * (single-decision reads) and {@link #findAll} (which already has its bulk-read values in hand).
     */
    private static AdrId firstOrNull(String subjectIri, String predicate, List<AdrId> values) {
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            LOG.warn("ADR {}: predicate '{}' had {} distinct values, returning the first",
                    subjectIri, predicate, values.size());
        }
        return values.get(0);
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
     * Mutable per-subject accumulator collecting a decision's scalar-field candidates across rows,
     * then choosing one of each deterministically (first-seen) when the
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
            addStatus(row);
            add("context", literalOrNull(row, "context"));
            add("decision", literalOrNull(row, "decision"));
            add("consequences", literalOrNull(row, "consequences"));
            add("alternatives", literalOrNull(row, "alternatives"));
            add("decisionDate", decisionDateOf(row));
            add("head", headOf(row));
        }

        /**
         * Collects the {@code status} candidate, logging a {@code WARN} and contributing nothing
         * when the row's {@code arkarch:adrStatus} value is not one of the four decoded lifecycle
         * individuals (see {@link #statusFromIri}) - never letting an unrecognised IRI reach
         * {@link Adr}'s constructor as a {@code null} status, which would throw and take the whole
         * read down with it. {@link #toAdr} treats "no status candidate at all" as the signal to
         * skip this one decision instead.
         */
        private void addStatus(BindingSet row) {
            row.getValue("status").filter(IRI.class::isInstance).ifPresent(value -> {
                String iri = ((IRI) value).getIRIString();
                AdrStatus status = statusFromIri(iri);
                if (status == null) {
                    LOG.warn("ADR {}: unrecognised arkarch:adrStatus '{}', skipping this decision",
                            id.value().value(), iri);
                    return;
                }
                add("status", status);
            });
        }

        private void add(String field, Object value) {
            if (value != null) {
                candidates.computeIfAbsent(field, key -> new ArrayList<>()).add(value);
            }
        }

        /**
         * Materialises the collected candidates into an {@link Adr}, or {@code null} if either half
         * of the store-first read cannot satisfy {@link Adr}'s own invariants - the same "skip this
         * decision" outcome, for the same reason, in both cases: a store-first (ADR-005) anomaly the
         * SHACL gate only ever guarded at write time must not take an entire read down with it.
         *
         * <ul>
         * <li>{@code arkarch:adrStatus} never resolved to a decoded value (see {@link #addStatus}) -
         * the same gap {@link Adr}'s {@code Objects.requireNonNull(status, "status")} would otherwise
         * throw on.</li>
         * <li>{@code status}/{@code supersededBy} disagree with the bi-implication
         * (kogn-io/arknet#357) {@link Adr}'s compact constructor enforces - a store-first record can
         * set {@code arkarch:adrStatus Superseded} without the edge, or the edge without that status.
         * The gate never retroactively catches either half for data it never validated, and since
         * kogn-io/arknet#359 it does not even try to catch the "{@code Superseded} without the edge"
         * half going forward: {@code ashapes:ADR-supersededByRequiresSupersededStatus} checks only
         * the other direction, on purpose (see that shape's own comment), so this read-time check is
         * the sole backstop for that half, not a second line of defence behind the gate.</li>
         * </ul>
         */
        private Adr toAdr(List<RequirementRef> requirements, List<BoundedContextRef> contexts,
                AdrId supersededBy, List<AdrId> relatedTo) {
            AdrStatus status = (AdrStatus) firstDistinct("status");
            if (status == null) {
                return null;
            }
            if ((status == AdrStatus.SUPERSEDED) != (supersededBy != null)) {
                LOG.warn("ADR {}: status {} is inconsistent with its supersededBy edge ({}), "
                                + "skipping this decision",
                        id.value().value(), status, supersededBy == null ? "absent" : "present");
                return null;
            }
            return new Adr(id, code,
                    (String) firstDistinct("name"),
                    status,
                    (String) firstDistinct("context"),
                    (String) firstDistinct("decision"),
                    (String) firstDistinct("consequences"),
                    (String) firstDistinct("alternatives"),
                    (LocalDate) firstDistinct("decisionDate"),
                    requirements, contexts, supersededBy, relatedTo);
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
            case REJECTED -> ArkarchVocabulary.REJECTED;
            case DEPRECATED -> ArkarchVocabulary.DEPRECATED;
            case SUPERSEDED -> ArkarchVocabulary.SUPERSEDED;
        };
    }

    /**
     * Maps a lifecycle individual back to the Java enum, or {@code null} for anything unrecognised.
     * A {@code null} here is no longer reached by {@link Adr}'s constructor at all
     * (kogn-io/arknet#357): {@link AdrAssembly#addStatus} intercepts it first, logs a {@code WARN}
     * and skips the one decision rather than letting an unresolvable status take an entire read
     * down with it - the same store-first-tolerant shape {@link #decisionDateOf} already gives an
     * unparseable {@code arkarch:decisionDate}.
     */
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
