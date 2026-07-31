// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.RevisionToken;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextConcurrentlyModifiedException;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Out-adapter: {@link BoundedContextRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF store).
 *
 * <p>Maps a {@link BoundedContext} to its opaque {@link BoundedContextId} as the subject IRI
 * (minted once by a {@link ResourceIdFactory}, never derived from the business code), stored in
 * one named graph shared by all bounded contexts: the type triple ({@code a
 * arkddd:BoundedContext}), the mandatory {@code dcterms:identifier} (the business code
 * {@code BC-1}), the generic {@code arknet:name} literal and the {@code arkddd:domainVision}
 * literal, an optional {@code arkddd:ownedBy} literal, plus zero or more
 * {@code arkddd:ubiquitousLanguageTerm} edges. This class depends only on the neutral kognio-rdf
 * ports ({@code terms} + {@code dataset}) and {@link SimpleRdf} - it never imports RDF4J. The
 * backend ({@link DatasetLifecycle} implementation) is supplied by the composition root.</p>
 *
 * <p><strong>Subdomain classification is a derived {@code arkddd:Subdomain} resource, not a flat
 * property (issue #189).</strong> {@link BoundedContext#subdomain()} is only the strategic
 * classification enum ({@link Subdomain#CORE_DOMAIN}/{@link Subdomain#SUPPORTING_DOMAIN}/
 * {@link Subdomain#GENERIC_DOMAIN}), but the DDD ontology models it as
 * {@code BoundedContext arkddd:partOf Subdomain ; Subdomain arkddd:subdomainType
 * arkddd:CoreDomain|SupportingDomain|GenericDomain} - a class-typed node in between, matching
 * {@code arkddd:Domain}/{@code arkddd:Subdomain}'s class-based modelling. When present, this
 * adapter mints that node's opaque IRI afresh on every write via {@link ResourceIdFactory} - the
 * same "derived value object, minted by the adapter, no stable identity of its own" pattern
 * {@code KognioRdfUseCaseRepository} uses for a use case's steps. {@link #replaceTriples} follows
 * the {@code arkddd:partOf} edge to delete the superseded node's triples on update, exactly as
 * the use-case adapter follows {@code mainStep}/{@code extensionStep} - a plain subject-only
 * delete would otherwise leave a fresh, disconnected {@code arkddd:Subdomain} node behind on
 * every update that touches the classification.</p>
 *
 * <p><strong>Create vs. compare-and-set update (opaque identity, issue #176).</strong> The
 * transactional mechanics - the in-transaction {@code contains} existence checks, the SHACL gate,
 * the commit-conflict translation, and the head comparison - live in the shared
 * {@link WriteFunnel} (ADR-013/ADR-014), not here. {@link #create} rejects an existing subject
 * with {@link ResourceAlreadyExistsException} and a business-code collision (by
 * {@code dcterms:identifier}) with {@link DuplicateBoundedContextCodeException};
 * {@link #compareAndUpdate} rejects a missing subject with
 * {@link BoundedContextNotFoundException} and a stale {@code expectedHead} with
 * {@link BoundedContextConcurrentlyModifiedException}, and otherwise replaces the subject's
 * triples wholesale (see {@link #replaceTriples}). There is no unconditional update: every
 * correction to an already-created bounded context goes through the compare-and-set guard, so two
 * concurrent {@code bc_link_term} calls can no longer silently lose one another's edge.</p>
 *
 * <p><strong>The second interleaving (issue #144).</strong> {@link WriteFunnel#create} translates
 * a lost {@code SERIALIZABLE} write conflict on {@link #create} into the same
 * {@link DuplicateBoundedContextCodeException} its synchronous code check throws - so
 * {@code CodeAssignment}'s retry (see {@code arknet-shared-kernel}) catches both interleavings the
 * same way. {@link WriteFunnel#compareAndUpdate} translates the same commit-time rejection into
 * its {@code headMismatch} signal instead - on that path a lost conflict is not a code collision
 * but a stale read, which the application service's retry loop absorbs exactly like a synchronous
 * head mismatch.</p>
 *
 * <p><strong>Term references arrive pre-resolved (issue #62/#66).</strong> {@link TermRef}
 * carries the term's opaque subject {@link ResourceId} directly - resolving a human-typed term
 * code (e.g. {@code TERM-1}) against the shared workspace store, and rejecting an unknown or
 * ambiguous code, is done once by {@link KognioRdfTermLookup} at the moment a term is linked (in
 * the application service), not here on every write. Unlike the requirements adapter's
 * {@code arkreq:usesTerm}, the {@code shapes:BoundedContextShape} places no {@code sh:class}
 * constraint on {@code arkddd:ubiquitousLanguageTerm}, so this adapter needs no validation-only
 * asserted context for it - the plain {@link ShaclWriteGate#enforce(io.kogn.rdf.terms.ReadableGraph)}
 * suffices.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate the candidate instance graph
 * against the DDD SHACL shapes before the write transaction opens, throw
 * {@link WriteConstraintViolationException} on a violation, persist nothing - live in the shared
 * {@link WriteFunnel} (ADR-013). {@code shapes:BoundedContext-hasAggregate} is {@code sh:Warning},
 * not {@code sh:Violation} (issue #66): a store-first bounded context minted during analysis,
 * before tactical design, has no aggregates yet, and that must not block the write.</p>
 *
 * <p><strong>Row multiplication (issue #81).</strong> {@code arkddd:partOf}'s
 * {@code sh:maxCount 1} is {@code sh:Warning}-severity only and {@code arkddd:ownedBy} carries no
 * {@code sh:maxCount} at all, so a store-first (ADR-005) bounded context with two triples on
 * either predicate legally multiplies {@link #findAll}'s SPARQL rows for one subject.
 * {@link #findAll} groups rows per subject and takes the first-seen value deterministically for
 * each, logging a single {@code WARN} when more than one distinct value was collapsed.</p>
 */
public class KognioRdfBoundedContextRepository implements BoundedContextRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfBoundedContextRepository.class);

    private static final String ARKNET_NAMESPACE = "https://w3id.org/arknet/core#";
    private static final String ARKDDD_NAMESPACE = "https://w3id.org/arknet/ddd#";
    private static final String BOUNDED_CONTEXT_GRAPH = "https://w3id.org/arknet/model/bounded-context";

    private static final String BOUNDED_CONTEXT_TYPE = ARKDDD_NAMESPACE + "BoundedContext";
    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String NAME_PROPERTY = ARKNET_NAMESPACE + "name";
    private static final String DOMAIN_VISION_PROPERTY = ArkdddVocabulary.DOMAIN_VISION;
    private static final String PART_OF_PROPERTY = ARKDDD_NAMESPACE + "partOf";
    private static final String SUBDOMAIN_TYPE_PROPERTY = ARKDDD_NAMESPACE + "subdomainType";
    private static final String SUBDOMAIN_CLASS = ARKDDD_NAMESPACE + "Subdomain";
    private static final String OWNED_BY_PROPERTY = ARKDDD_NAMESPACE + "ownedBy";
    private static final String UBIQUITOUS_LANGUAGE_TERM_PROPERTY = ARKDDD_NAMESPACE + "ubiquitousLanguageTerm";
    private static final String HAS_AGGREGATE_PROPERTY = ARKDDD_NAMESPACE + "hasAggregate";

    private static final String CORE_DOMAIN = ARKDDD_NAMESPACE + "CoreDomain";
    private static final String SUPPORTING_DOMAIN = ARKDDD_NAMESPACE + "SupportingDomain";
    private static final String GENERIC_DOMAIN = ARKDDD_NAMESPACE + "GenericDomain";

    private final DatasetLifecycle lifecycle;
    private final ResourceIdFactory resourceIdFactory;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle         the kognio-rdf dataset lifecycle to acquire datasets from - read
     *                          paths only, the write path goes through {@code funnel} (must not
     *                          be {@code null})
     * @param resourceIdFactory mints the opaque IRI of the derived {@code arkddd:Subdomain} node
     *                          when a bounded context carries a subdomain classification (must
     *                          not be {@code null}); the bounded context's own identity is minted
     *                          store-neutrally above the store
     * @param funnel            the shared write funnel (ADR-013) running the SHACL gate, dataset
     *                          acquisition and existence/head checks for every
     *                          {@link #create}/{@link #compareAndUpdate}
     *                          (must not be {@code null})
     */
    KognioRdfBoundedContextRepository(
            DatasetLifecycle lifecycle, ResourceIdFactory resourceIdFactory, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, BoundedContext boundedContext) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(boundedContext, "boundedContext");

        // ResourceId#of validates IRIREF-safety at construction, so the wrapped IRI is already
        // guaranteed safe to embed here - no separate check needed.
        String subjectIriString = boundedContext.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(BOUNDED_CONTEXT_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, boundedContext);

        funnel.create(new DatasetId(projectId.value()), BOUNDED_CONTEXT_GRAPH, subjectIriString,
                boundedContext.code().value(), graph, null,
                () -> new ResourceAlreadyExistsException(projectId, boundedContext.id().value()),
                () -> new DuplicateBoundedContextCodeException(projectId, boundedContext.code()),
                tx -> replaceTriples(tx, graphIri, subjectIri, subject, graph, false));
    }

    /**
     * Compare-and-set update (issue #176, the guard requirements got in issues #108/#167):
     * replaces the bounded context's triples only if its {@code arkprov:head} still equals
     * {@code expectedHead} at the moment the shared {@link WriteFunnel} checks it inside the write
     * transaction - closing the lost-update window a plain read (via {@link #findCurrentByCode})
     * followed by an unconditional replace would otherwise leave open between the read and the
     * write.
     */
    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, BoundedContext updated) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updated, "updated");

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(BOUNDED_CONTEXT_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, updated);

        funnel.compareAndUpdate(new DatasetId(projectId.value()), BOUNDED_CONTEXT_GRAPH, subjectIriString,
                expectedHead == null ? null : expectedHead.value(), graph, null,
                () -> new BoundedContextNotFoundException(projectId, updated.code()),
                () -> new BoundedContextConcurrentlyModifiedException(projectId, updated.code()),
                tx -> replaceTriples(tx, graphIri, subjectIri, subject, graph, true));
    }

    /**
     * Builds the candidate graph for one bounded context's triples: type, identifier, name,
     * domainVision, an optional derived {@code arkddd:Subdomain} node (see the class-level note
     * on issue #189) and an optional ownedBy literal, and zero or more
     * {@code arkddd:ubiquitousLanguageTerm} edges to the bounded context's already-resolved term
     * references. Shared by {@link #create} and {@link #compareAndUpdate} so both write paths
     * serialise a {@link BoundedContext} identically.
     */
    private Graph buildCandidateGraph(IRI subjectIri, BoundedContext boundedContext) {
        List<IRI> termIris = boundedContext.usesTerms().stream()
                .map(this::termIriFor)
                .toList();
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(BOUNDED_CONTEXT_TYPE));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(boundedContext.code().value()));
        graph.add(subjectIri, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral(boundedContext.name()));
        graph.add(subjectIri, rdf.createIRI(DOMAIN_VISION_PROPERTY),
                rdf.createLiteral(boundedContext.domainVision()));
        if (boundedContext.subdomain() != null) {
            IRI subdomainIri = mintSubdomainIri();
            graph.add(subdomainIri, VocabRdf.TYPE, rdf.createIRI(SUBDOMAIN_CLASS));
            graph.add(subdomainIri, rdf.createIRI(SUBDOMAIN_TYPE_PROPERTY),
                    rdf.createIRI(subdomainIriFor(boundedContext.subdomain())));
            graph.add(subjectIri, rdf.createIRI(PART_OF_PROPERTY), subdomainIri);
        }
        if (boundedContext.ownedBy() != null) {
            graph.add(subjectIri, rdf.createIRI(OWNED_BY_PROPERTY), rdf.createLiteral(boundedContext.ownedBy()));
        }
        for (IRI termIri : termIris) {
            graph.add(subjectIri, rdf.createIRI(UBIQUITOUS_LANGUAGE_TERM_PROPERTY), termIri);
        }
        return graph;
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write
     * transaction. On an update it first captures two kinds of edges that {@code graph} (built
     * from the {@link BoundedContext} record) never carries, and re-attaches both after the
     * rewrite - so a replace-by-identity write of a store-first (ADR-005) bounded context carries
     * them along instead of erasing them:
     *
     * <ul>
     * <li>{@code arkddd:ubiquitousLanguageTerm} edges whose target is not an IRI
     * ({@link #readUsesTerms} can never read those, since {@link ResourceId} cannot represent a
     * blank node) - the same preservation the requirements adapter does for
     * {@code arkreq:usesTerm}, issue #65.</li>
     * <li><strong>All</strong> {@code arkddd:hasAggregate} edges, regardless of target kind:
     * {@link BoundedContext} has no field for its aggregates at all (issue #66 lowered the shape
     * to {@code sh:Warning} precisely so a bounded context minted before tactical design has none
     * yet), so unlike {@code ubiquitousLanguageTerm} there is no IRI-typed round-trip through the
     * domain object to fall back on - every edge, IRI or blank node, would otherwise be lost on
     * the very next {@code bc_link_term} call.</li>
     * </ul>
     *
     * <p>{@code deleteExisting} also follows the {@code arkddd:partOf} edge, mirroring
     * {@code KognioRdfUseCaseRepository}'s step-following delete: the derived
     * {@code arkddd:Subdomain} node {@link #buildCandidateGraph} mints is reachable only from the
     * subject, so a subject-only delete would leave the superseded node's triples behind as
     * disconnected, ever-accumulating garbage on every update that touches the classification
     * (issue #189).</p>
     */
    private void replaceTriples(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            boolean exists) {
        String selectUnjoinableTerms = "SELECT ?term WHERE { "
                + "GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { " + subject + " <"
                + UBIQUITOUS_LANGUAGE_TERM_PROPERTY + "> ?term } FILTER(!isIRI(?term)) }";
        String selectAggregates = "SELECT ?aggregate WHERE { "
                + "GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { " + subject + " <"
                + HAS_AGGREGATE_PROPERTY + "> ?aggregate } }";
        String deleteExisting = "DELETE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { ?s ?p ?o } } WHERE { "
                + "GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "{ " + subject + " ?p ?o . BIND(" + subject + " AS ?s) } UNION "
                + "{ " + subject + " <" + PART_OF_PROPERTY + "> ?s . ?s ?p ?o } } }";

        List<RDFTerm> unjoinableTerms = exists
                ? tx.select(selectUnjoinableTerms).map(row -> termOf(row, "term")).toList()
                : List.of();
        List<RDFTerm> aggregates = exists
                ? tx.select(selectAggregates).map(row -> termOf(row, "aggregate")).toList()
                : List.of();
        if (exists) {
            tx.update(deleteExisting);
        }
        tx.add(graphIri, graph);
        if (!unjoinableTerms.isEmpty() || !aggregates.isEmpty()) {
            Graph preservedEdges = rdf.createGraph();
            for (RDFTerm termNode : unjoinableTerms) {
                preservedEdges.add(subjectIri, rdf.createIRI(UBIQUITOUS_LANGUAGE_TERM_PROPERTY), termNode);
            }
            for (RDFTerm aggregateNode : aggregates) {
                preservedEdges.add(subjectIri, rdf.createIRI(HAS_AGGREGATE_PROPERTY), aggregateNode);
            }
            tx.add(graphIri, preservedEdges);
        }
    }

    @Override
    public Optional<BoundedContext> findByCode(ProjectId projectId, BoundedContextCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?name ?domainVision ?subdomain ?ownedBy WHERE { GRAPH <"
                + BOUNDED_CONTEXT_GRAPH + "> { "
                + boundedContextByCodeWhereClause(code)
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Optional<BindingSet> found = handle.sparqlQuery().select(query).findFirst();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(boundedContextOf(found.get(), code, handle));
        }
    }

    /**
     * Reads a bounded context's current state together with its concurrency token. The row built
     * from {@link #boundedContextByCodeWhereClause} (the core fields) plus the head itself come
     * from this method's one query call (issue #176) - one snapshot, which is the load-bearing
     * guarantee, not an ordering of clauses within that query. {@link #boundedContextOf} then
     * issues one further, independent query, via {@link #readUsesTerms}, to fill in
     * {@code usesTerms}; that later read is safe precisely because it can only be fresher, never
     * staler, than the head: a concurrent funnel write landing in between moves the head, so
     * {@link BoundedContextRepository#compareAndUpdate} then fails its comparison and the caller
     * re-reads instead of silently overwriting a state it never actually saw. Builds the
     * {@link BoundedContext} the same way {@link #findByCode} does - both call
     * {@link #boundedContextOf} on their row, so the two read paths cannot drift apart
     * field-by-field.
     */
    @Override
    public Optional<BoundedContextRepository.CurrentBoundedContext> findCurrentByCode(
            ProjectId projectId, BoundedContextCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?name ?domainVision ?subdomain ?ownedBy ?head WHERE { GRAPH <"
                + BOUNDED_CONTEXT_GRAPH + "> { "
                + boundedContextByCodeWhereClause(code)
                + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?s <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Optional<BindingSet> found = handle.sparqlQuery().select(query).findFirst();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            BindingSet row = found.get();
            BoundedContext boundedContext = boundedContextOf(row, code, handle);
            RevisionToken head = row.getValue("head")
                    .filter(IRI.class::isInstance)
                    .map(value -> new RevisionToken(((IRI) value).getIRIString()))
                    .orElse(null);
            return Optional.of(new BoundedContextRepository.CurrentBoundedContext(boundedContext, head));
        }
    }

    /**
     * The WHERE body shared by {@link #findByCode} and {@link #findCurrentByCode}: the mandatory
     * joins (type, identifier, name, domainVision) plus the two optional joins (subdomain,
     * ownedBy) that scope a single-bounded-context read to one {@code code}. The subdomain join
     * follows the derived {@code arkddd:Subdomain} node's {@code arkddd:partOf}/
     * {@code arkddd:subdomainType} hop (issue #189) but still projects a single {@code ?subdomain}
     * binding - the {@code arkddd:CoreDomain}/{@code SupportingDomain}/{@code GenericDomain}
     * individual - so {@link #subdomainOf} reads it exactly as it did the old flat property.
     * Extracted because both callers build a {@link BoundedContext} from the same row shape via
     * {@link #boundedContextOf} - drift between two near-identical read paths is what issues
     * #80/#81 cost the requirements adapter, so this text lives in one place. The caller supplies
     * the surrounding {@code SELECT}/{@code GRAPH}/{@code WHERE} wrapping and, in
     * {@link #findCurrentByCode}'s case, the additional provenance-graph join.
     */
    private static String boundedContextByCodeWhereClause(BoundedContextCode code) {
        return "?s a <" + BOUNDED_CONTEXT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . "
                + "?s <" + NAME_PROPERTY + "> ?name . "
                + "?s <" + DOMAIN_VISION_PROPERTY + "> ?domainVision . "
                + "OPTIONAL { ?s <" + PART_OF_PROPERTY + "> ?subdomainNode . "
                + "?subdomainNode <" + SUBDOMAIN_TYPE_PROPERTY + "> ?subdomain } "
                + "OPTIONAL { ?s <" + OWNED_BY_PROPERTY + "> ?ownedBy } ";
    }

    /**
     * Builds one {@link BoundedContext} from a row of {@link #boundedContextByCodeWhereClause}'s
     * projection ({@code ?s ?name ?domainVision ?subdomain ?ownedBy}), including the follow-up
     * read {@link #readUsesTerms} (via {@code handle}). Shared by {@link #findByCode} and
     * {@link #findCurrentByCode} so both single-bounded-context read paths build the aggregate the
     * same way.
     */
    private BoundedContext boundedContextOf(BindingSet row, BoundedContextCode code, DatasetHandle handle) {
        String subjectIriString = iriOf(row, "s").getIRIString();
        return new BoundedContext(
                new BoundedContextId(ResourceId.of(subjectIriString)),
                code,
                literalOf(row, "name").getLexicalForm(),
                literalOf(row, "domainVision").getLexicalForm(),
                subdomainOf(row),
                ownedByOf(row),
                readUsesTerms(handle.sparqlQuery()::select, SparqlTerms.iriRef(subjectIriString)));
    }

    @Override
    public List<BoundedContext> findAll(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?s ?identifier ?name ?domainVision ?subdomain ?ownedBy WHERE { GRAPH <"
                + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s a <" + BOUNDED_CONTEXT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "?s <" + NAME_PROPERTY + "> ?name . "
                + "?s <" + DOMAIN_VISION_PROPERTY + "> ?domainVision . "
                + "OPTIONAL { ?s <" + PART_OF_PROPERTY + "> ?subdomainNode . "
                + "?subdomainNode <" + SUBDOMAIN_TYPE_PROPERTY + "> ?subdomain } "
                + "OPTIONAL { ?s <" + OWNED_BY_PROPERTY + "> ?ownedBy } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<String, List<TermRef>> termsBySubject = readUsesTermsBySubject(handle);
            // Grouped by subject (issue #81): subdomain/ownedBy are OPTIONAL joins without an
            // enforced sh:maxCount, so a store-first bounded context with two triples on either
            // predicate binds a cross-product of rows for the same subject. Mapping each row
            // straight to a BoundedContext would surface that subject more than once.
            Map<String, BoundedContextAssembly> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                BoundedContextAssembly assembly = assemblyFor(bySubject, row);
                assembly.addSubdomainCandidate(subdomainOf(row));
                assembly.addOwnedByCandidate(ownedByOf(row));
            });
            return bySubject.entrySet().stream()
                    .map(entry -> entry.getValue().toBoundedContext(
                            termsBySubject.getOrDefault(entry.getKey(), List.of())))
                    .toList();
        }
    }

    /**
     * Batch identity-to-code resolution backing the {@code ResolveBoundedContexts} in-port: one
     * {@code VALUES}-bound query, never one per id.
     *
     * <p>Joins only {@code dcterms:identifier} - not {@code name}/{@code domainVision} - so a
     * store-first (ADR-005) context that carries an identity and a code but misses one of the
     * otherwise-mandatory fields still resolves, exactly as {@code KognioRdfTermRepository#findByIds}
     * decided for the glossary. Rows are grouped per subject rather than mapped 1:1 (the #81
     * pattern): {@code dcterms:identifier} carries no enforceable {@code sh:maxCount}, so a
     * store-first context with two identifier triples would otherwise report the same identity
     * twice - the very contract violation {@code ResolveBoundedContexts} ("the contexts", not "one
     * row per predicate combination") exists to rule out. No {@code FILTER(isIRI(?s))} is needed
     * here: the subjects come from a {@code VALUES} clause bound to caller-supplied
     * {@link ResourceId}s, which can never denote a blank node.</p>
     */
    @Override
    public List<ResolveBoundedContexts.ResolvedBoundedContext> findByIds(
            ProjectId projectId, List<ResourceId> ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }

        // ResourceId#of validates IRIREF-safety at construction, so every id here is already
        // guaranteed safe to embed - which is what keeps ResolveBoundedContexts' "never rejects"
        // contract intact.
        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value()))
                .distinct()
                .collect(Collectors.joining(" "));

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s a <" + BOUNDED_CONTEXT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<String, ResolveBoundedContexts.ResolvedBoundedContext> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                String subjectIri = iriOf(row, "s").getIRIString();
                // putIfAbsent, not put: the first row wins if a subject has several identifiers.
                bySubject.putIfAbsent(subjectIri, new ResolveBoundedContexts.ResolvedBoundedContext(
                        ResourceId.of(subjectIri),
                        new BoundedContextCode(literalOf(row, "identifier").getLexicalForm())));
            });
            return List.copyOf(bySubject.values());
        }
    }

    private static BoundedContextAssembly assemblyFor(Map<String, BoundedContextAssembly> bySubject, BindingSet row) {
        String subjectIri = iriOf(row, "s").getIRIString();
        return bySubject.computeIfAbsent(subjectIri, iri -> new BoundedContextAssembly(
                new BoundedContextId(ResourceId.of(iri)),
                new BoundedContextCode(literalOf(row, "identifier").getLexicalForm()),
                literalOf(row, "name").getLexicalForm(),
                literalOf(row, "domainVision").getLexicalForm()));
    }

    /**
     * Mutable per-subject accumulator collecting a bounded context's {@code subdomain} and
     * {@code ownedBy} candidates across rows (issue #81), then choosing one of each
     * deterministically (first-seen) when the bounded context is finally materialised, logging a
     * {@code WARN} if more than one distinct value was collected for a field.
     */
    private static final class BoundedContextAssembly {

        private final BoundedContextId id;
        private final BoundedContextCode code;
        private final String name;
        private final String domainVision;
        private final List<Subdomain> subdomains = new ArrayList<>();
        private final List<String> ownedBys = new ArrayList<>();

        private BoundedContextAssembly(BoundedContextId id, BoundedContextCode code, String name,
                String domainVision) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.domainVision = domainVision;
        }

        private void addSubdomainCandidate(Subdomain subdomain) {
            if (subdomain != null) {
                subdomains.add(subdomain);
            }
        }

        private void addOwnedByCandidate(String ownedBy) {
            if (ownedBy != null) {
                ownedBys.add(ownedBy);
            }
        }

        private BoundedContext toBoundedContext(List<TermRef> usesTerms) {
            return new BoundedContext(id, code, name, domainVision,
                    firstDistinct(subdomains, "subdomain"), firstDistinct(ownedBys, "ownedBy"), usesTerms);
        }

        private <T> T firstDistinct(List<T> candidates, String fieldName) {
            if (candidates.isEmpty()) {
                return null;
            }
            long distinctCount = candidates.stream().distinct().count();
            if (distinctCount > 1) {
                LOG.warn("BoundedContext {}: field '{}' had {} distinct values, returning the first",
                        id.value().value(), fieldName, distinctCount);
            }
            return candidates.get(0);
        }
    }

    // ---- ubiquitousLanguageTerm reading ------------------------------------------------

    /**
     * Reads the {@code arkddd:ubiquitousLanguageTerm} edges of one bounded context back as term
     * references. Ordered by target IRI (RDF has no intrinsic statement order and
     * {@link BoundedContext} compares its {@code usesTerms} list positionally). A store-first
     * blank-node target is excluded by {@code FILTER(isIRI(?term))} - it is preserved across an
     * update by {@link #replaceTriples} but cannot be materialised into a {@link TermRef}.
     */
    private List<TermRef> readUsesTerms(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?term WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + subject + " <" + UBIQUITOUS_LANGUAGE_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?term)) } ORDER BY ?term";
        return selectFn.apply(query)
                .map(row -> new TermRef(ResourceId.of(iriOf(row, "term").getIRIString())))
                .toList();
    }

    /** Bulk variant of {@link #readUsesTerms}: all bounded contexts' term references in one query. */
    private Map<String, List<TermRef>> readUsesTermsBySubject(DatasetHandle handle) {
        String query = "SELECT ?s ?term WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s <" + UBIQUITOUS_LANGUAGE_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?term)) } ORDER BY ?s ?term";
        Map<String, List<TermRef>> bySubject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(new TermRef(ResourceId.of(iriOf(row, "term").getIRIString()))));
        return bySubject;
    }

    /**
     * Converts an already-resolved {@link TermRef} to an {@link IRI} for writing.
     * {@link ResourceId#of(String)} validates IRIREF-safety at construction, so the wrapped IRI
     * is already guaranteed safe here.
     */
    private IRI termIriFor(TermRef term) {
        return rdf.createIRI(term.value().value());
    }

    /**
     * Mints an opaque IRI for the derived {@code arkddd:Subdomain} node from the same kernel
     * scheme as the bounded context root, mirroring
     * {@code KognioRdfUseCaseRepository#mintStepIri}: the node is a value object with no stable
     * identity of its own (issue #189).
     */
    private IRI mintSubdomainIri() {
        return rdf.createIRI(resourceIdFactory.newId().value());
    }

    // ---- helpers -----------------------------------------------------------------------

    private static String subdomainIriFor(Subdomain subdomain) {
        return switch (subdomain) {
            case CORE_DOMAIN -> CORE_DOMAIN;
            case SUPPORTING_DOMAIN -> SUPPORTING_DOMAIN;
            case GENERIC_DOMAIN -> GENERIC_DOMAIN;
        };
    }

    private static Subdomain subdomainFromIri(String iri) {
        if (CORE_DOMAIN.equals(iri)) {
            return Subdomain.CORE_DOMAIN;
        }
        if (SUPPORTING_DOMAIN.equals(iri)) {
            return Subdomain.SUPPORTING_DOMAIN;
        }
        if (GENERIC_DOMAIN.equals(iri)) {
            return Subdomain.GENERIC_DOMAIN;
        }
        throw new IllegalStateException("unexpected subdomain " + iri);
    }

    private static Subdomain subdomainOf(BindingSet row) {
        return row.getValue("subdomain")
                .map(value -> subdomainFromIri(((IRI) value).getIRIString()))
                .orElse(null);
    }

    private static String ownedByOf(BindingSet row) {
        return row.getValue("ownedBy")
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
     * Reads a binding as the bare {@link RDFTerm} it is, without narrowing it to {@link IRI} -
     * used where the binding's kind is not known in advance (an
     * {@code arkddd:ubiquitousLanguageTerm} target may legally be a blank node).
     */
    private static RDFTerm termOf(BindingSet row, String name) {
        return row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
