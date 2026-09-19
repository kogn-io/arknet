// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipId;
import de.hauschel.arknet.bc.domain.ContextRelationshipNotFoundException;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Out-adapter: {@link ContextRelationshipRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF store).
 *
 * <p>Maps a {@link ContextRelationship} to its opaque
 * {@link de.hauschel.arknet.bc.domain.ContextRelationshipId} as the subject IRI (minted once by
 * the application service, never by this adapter), stored in the same named graph
 * {@code KognioRdfBoundedContextRepository} writes {@code arkddd:BoundedContext} into: the type
 * triple ({@code a arkddd:ContextRelationship}), the mandatory {@code arkddd:upstream}/
 * {@code arkddd:downstream} edges (both pointing at an already-persisted bounded context's own
 * subject IRI) and the mandatory {@code arkddd:relationshipType} edge to one of the eight
 * {@code arkddd:RelationshipType} individuals. This class depends only on the neutral kognio-rdf
 * ports ({@code terms} + {@code dataset}) and {@link SimpleRdf} - it never imports RDF4J.</p>
 *
 * <p><strong>Idempotent create over the (upstream, downstream, relationshipType) triple, not pure
 * create (issue #565).</strong> {@link #createIfAbsent} first looks for a relationship already
 * carrying the exact same triple - inside the same write transaction {@link WriteFunnel#createIfAbsent}
 * runs its identity/code guards in, closing the check-then-act race a separate read beforehand would
 * leave open (see {@link ContextRelationshipRepository}'s class javadoc). {@link ContextRelationship}
 * carries no human-readable business code of its own (unlike {@code BoundedContextCode}), so there is
 * no second uniqueness rule beyond the triple check and the identity collision - {@code code} is
 * passed as {@code null}, skipping that guard entirely, the same honest shape
 * {@code WriteFunnel#delete}'s code-retention overload already uses for a caller with nothing to
 * check there.</p>
 *
 * <p><strong>Relationship-type IRI mapping mirrors {@code Subdomain}'s.</strong> The eight private
 * {@code String} constants below are named exactly like the {@link RelationshipType} enum
 * constants they map to/from, and {@link #relationshipTypeIriFor} switches on the enum the same
 * way {@code KognioRdfBoundedContextRepository#subdomainIriFor} switches on
 * {@code Subdomain} - same idiom, same reasoning: a compiler-checked, exhaustive mapping with no
 * risk of an enum constant silently mapping to nothing.</p>
 *
 * <p><strong>Every {@code arkddd:} IRI here comes from {@link ArkdddVocabulary}
 * (kogn-io/arknet#148).</strong> {@code arkddd:upstream}/{@code downstream} were the first to move
 * there (issue #293), the same shared source {@code arknet-mcp}'s
 * {@code de.hauschel.arknet.mcp.trace.TraceabilityGraph} traverses them from for
 * {@code impact_analysis}. {@code arkddd:ContextRelationship}, {@code arkddd:BoundedContext}
 * (this class's own validation-only assertion target, below), {@code relationshipType} and the
 * eight {@code arkddd:RelationshipType} individual IRIs followed with #148, once
 * {@code arkddd:ContextRelationship} turned out to already be a private duplicate of this class's
 * own copy in {@code KognioRdfContextRelationshipRepositoryFactory}, and
 * {@code arkddd:BoundedContext} a private duplicate of the very type
 * {@code KognioRdfBoundedContextRepository} writes and {@code KognioRdfAdrRepository}/
 * {@code KognioRdfBoundedContextLookup} separately re-declared.</p>
 *
 * <p><strong>Validation-only asserted context, same reasoning as requirements'
 * {@code arkreq:usesTerm}.</strong> {@code shapes:ContextRelationship-upstream}/
 * {@code -downstream} carry an {@code sh:class arkddd:BoundedContext} constraint, but the type
 * triple of the referenced bounded context lives in its own already-committed write, not in this
 * candidate graph. {@link #createIfAbsent} therefore hands the gate a validation-only
 * {@code assertedContext} graph asserting {@code <upstreamIri> a arkddd:BoundedContext} and
 * {@code <downstreamIri> a arkddd:BoundedContext} - never persisted, exactly the pattern
 * {@code KognioRdfRequirementRepository#create} uses for {@code arkreq:usesTerm}'s
 * {@code skos:Concept} constraint. This is safe because {@code BoundedContextCode} resolution in
 * the application service (via {@code BoundedContextRepository#findByCode}) already proved both
 * referenced identities exist and are bounded contexts before {@link #createIfAbsent} is ever
 * called - the resolution, not the shape, is what keeps the edge non-dangling. That very type
 * assertion is also why {@link KognioRdfContextRelationshipRepositoryFactory} loads a
 * <em>filtered</em> shapes graph rather than reusing {@code KognioRdfBoundedContextRepositoryFactory}'s
 * gate unfiltered - see that factory's class javadoc.</p>
 *
 * <p><strong>Read paths (issue #565).</strong> {@link #findByContext}/{@link #findAll} back
 * {@code bc_get}/{@code bc_list}'s new relationship display - plain reads, no transaction, no
 * SHACL gate. {@link #deleteByEdge} backs {@code bc_unlink_context}: it first resolves the subject
 * IRI matching the given triple with a plain read, then removes it via {@link WriteFunnel#delete},
 * whose own existence re-check inside the delete transaction is what actually closes the race
 * against a concurrent delete of the same relationship - the same two-step shape
 * {@code KognioRdfActorRepository#delete} already uses to resolve a business code to a subject IRI
 * before deleting it.</p>
 */
public class KognioRdfContextRelationshipRepository implements ContextRelationshipRepository {

    /** Shares {@code KognioRdfBoundedContextRepository}'s named graph, not a private one of its own. */
    private static final String BOUNDED_CONTEXT_GRAPH = "https://w3id.org/arknet/model/bounded-context";

    private static final String CONTEXT_RELATIONSHIP_TYPE = ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE;
    /** {@code shapes:ContextRelationship-upstream}/{@code -downstream}'s {@code sh:class} target. */
    private static final String BOUNDED_CONTEXT_TYPE = ArkdddVocabulary.BOUNDED_CONTEXT_TYPE;
    private static final String UPSTREAM_PROPERTY = ArkdddVocabulary.UPSTREAM;
    private static final String DOWNSTREAM_PROPERTY = ArkdddVocabulary.DOWNSTREAM;
    private static final String RELATIONSHIP_TYPE_PROPERTY = ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY;

    private static final String PARTNERSHIP = ArkdddVocabulary.PARTNERSHIP;
    private static final String SHARED_KERNEL = ArkdddVocabulary.SHARED_KERNEL;
    private static final String CUSTOMER_SUPPLIER = ArkdddVocabulary.CUSTOMER_SUPPLIER;
    private static final String CONFORMIST = ArkdddVocabulary.CONFORMIST;
    private static final String ANTICORRUPTION_LAYER = ArkdddVocabulary.ANTICORRUPTION_LAYER;
    private static final String OPEN_HOST_SERVICE = ArkdddVocabulary.OPEN_HOST_SERVICE;
    private static final String PUBLISHED_LANGUAGE = ArkdddVocabulary.PUBLISHED_LANGUAGE;
    private static final String SEPARATE_WAYS = ArkdddVocabulary.SEPARATE_WAYS;

    private final DatasetLifecycle lifecycle;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from - read paths only
     *                  ({@link #findByContext}/{@link #findAll}, and {@link #deleteByEdge}'s
     *                  subject-resolution read); the write path goes through {@code funnel} (must
     *                  not be {@code null})
     * @param funnel    the shared write funnel running the SHACL gate, dataset acquisition and the
     *                  existence checks for every {@link #createIfAbsent}/{@link #deleteByEdge}
     *                  (must not be {@code null})
     */
    KognioRdfContextRelationshipRepository(DatasetLifecycle lifecycle, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public ContextRelationship createIfAbsent(ProjectId projectId, ContextRelationship relationship) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(relationship, "relationship");

        // ResourceId#of validates IRIREF-safety at construction, so the wrapped IRI is already
        // guaranteed safe to embed here - no separate check needed.
        String subjectIriString = relationship.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        IRI upstreamIri = rdf.createIRI(relationship.upstream().value().value());
        IRI downstreamIri = rdf.createIRI(relationship.downstream().value().value());
        IRI graphIri = rdf.createIRI(BOUNDED_CONTEXT_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, upstreamIri, downstreamIri, relationship);

        // See the class javadoc's "validation-only asserted context" note for why the referenced
        // bounded contexts' own type triples are handed to the gate here rather than embedded in
        // the candidate graph.
        Graph assertedContext = rdf.createGraph();
        assertedContext.add(upstreamIri, VocabRdf.TYPE, rdf.createIRI(BOUNDED_CONTEXT_TYPE));
        assertedContext.add(downstreamIri, VocabRdf.TYPE, rdf.createIRI(BOUNDED_CONTEXT_TYPE));

        String edgeAsk = "SELECT ?s WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s a <" + CONTEXT_RELATIONSHIP_TYPE + "> ; "
                + "<" + UPSTREAM_PROPERTY + "> <" + relationship.upstream().value().value() + "> ; "
                + "<" + DOWNSTREAM_PROPERTY + "> <" + relationship.downstream().value().value() + "> ; "
                + "<" + RELATIONSHIP_TYPE_PROPERTY + "> <"
                + relationshipTypeIriFor(relationship.relationshipType()) + "> } }";

        // A relationship carries no business code of its own (unlike BoundedContextCode) - code =
        // null skips that guard entirely, the same honest shape WriteFunnel#delete already uses for
        // a caller with nothing to check there.
        String resultIri = funnel.createIfAbsent(new DatasetId(projectId.value()), BOUNDED_CONTEXT_GRAPH,
                subjectIriString, null, graph, assertedContext,
                tx -> tx.select(edgeAsk).map(row -> iriOf(row, "s").getIRIString()).findFirst(),
                () -> new ResourceAlreadyExistsException(projectId, relationship.id().value()),
                () -> new ResourceAlreadyExistsException(projectId, relationship.id().value()),
                tx -> tx.add(graphIri, graph));

        if (resultIri.equals(subjectIriString)) {
            return relationship;
        }
        return new ContextRelationship(new ContextRelationshipId(ResourceId.of(resultIri)),
                relationship.upstream(), relationship.downstream(), relationship.relationshipType());
    }

    @Override
    public void deleteByEdge(ProjectId projectId, BoundedContextId upstream, BoundedContextId downstream,
            RelationshipType relationshipType) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(downstream, "downstream");
        Objects.requireNonNull(relationshipType, "relationshipType");

        DatasetId dataset = new DatasetId(projectId.value());
        List<String> subjectIris;
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            String query = edgeSelect(upstream, downstream, relationshipType);
            subjectIris = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "s").getIRIString())
                    .toList();
        }
        if (subjectIris.isEmpty()) {
            throw new ContextRelationshipNotFoundException(projectId, null, null, relationshipType);
        }

        // Every resource carrying this exact triple is removed, not just the first found: issue
        // #438's pre-#565 pure create left the live store able to hold more than one relationship
        // for the same triple (tracked for cleanup as #573), and leaving a duplicate behind would
        // make bc_unlink_context report success while bc_get/impact_analysis still show the edge.
        // No business code to retain per subject (issue #350's scheme has no reader here - a
        // relationship carries no BoundedContextCode-style label) - the five-parameter overload
        // forwards code = null.
        for (String subjectIriString : subjectIris) {
            String subject = SparqlTerms.iriRef(subjectIriString);
            funnel.delete(dataset, BOUNDED_CONTEXT_GRAPH, subjectIriString,
                    () -> new ContextRelationshipNotFoundException(projectId, null, null, relationshipType),
                    tx -> tx.update(
                            "DELETE WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { " + subject + " ?p ?o } }"));
        }
    }

    @Override
    public List<ContextRelationship> findByContext(ProjectId projectId, BoundedContextId context) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(context, "context");

        String contextIri = context.value().value();
        String query = "SELECT ?s ?upstream ?downstream ?type WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s a <" + CONTEXT_RELATIONSHIP_TYPE + "> ; "
                + "<" + UPSTREAM_PROPERTY + "> ?upstream ; "
                + "<" + DOWNSTREAM_PROPERTY + "> ?downstream ; "
                + "<" + RELATIONSHIP_TYPE_PROPERTY + "> ?type . "
                + "FILTER(?upstream = <" + contextIri + "> || ?downstream = <" + contextIri + ">) } } "
                + "ORDER BY ?s";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return readRelationships(handle.sparqlQuery().select(query));
        }
    }

    @Override
    public List<ContextRelationship> findAll(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?s ?upstream ?downstream ?type WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s a <" + CONTEXT_RELATIONSHIP_TYPE + "> ; "
                + "<" + UPSTREAM_PROPERTY + "> ?upstream ; "
                + "<" + DOWNSTREAM_PROPERTY + "> ?downstream ; "
                + "<" + RELATIONSHIP_TYPE_PROPERTY + "> ?type } } "
                + "ORDER BY ?s";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return readRelationships(handle.sparqlQuery().select(query));
        }
    }

    private static List<ContextRelationship> readRelationships(Stream<BindingSet> rows) {
        List<ContextRelationship> relationships = new ArrayList<>();
        rows.forEach(row -> relationships.add(new ContextRelationship(
                new ContextRelationshipId(ResourceId.of(iriOf(row, "s").getIRIString())),
                new BoundedContextId(ResourceId.of(iriOf(row, "upstream").getIRIString())),
                new BoundedContextId(ResourceId.of(iriOf(row, "downstream").getIRIString())),
                relationshipTypeFor(iriOf(row, "type").getIRIString()))));
        return relationships;
    }

    /** The {@code SELECT ?s} query {@link #deleteByEdge} resolves an edge's subject IRI with. */
    private static String edgeSelect(BoundedContextId upstream, BoundedContextId downstream,
            RelationshipType relationshipType) {
        return "SELECT ?s WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s a <" + CONTEXT_RELATIONSHIP_TYPE + "> ; "
                + "<" + UPSTREAM_PROPERTY + "> <" + upstream.value().value() + "> ; "
                + "<" + DOWNSTREAM_PROPERTY + "> <" + downstream.value().value() + "> ; "
                + "<" + RELATIONSHIP_TYPE_PROPERTY + "> <" + relationshipTypeIriFor(relationshipType) + "> } }";
    }

    private Graph buildCandidateGraph(IRI subjectIri, IRI upstreamIri, IRI downstreamIri,
            ContextRelationship relationship) {
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(CONTEXT_RELATIONSHIP_TYPE));
        graph.add(subjectIri, rdf.createIRI(UPSTREAM_PROPERTY), upstreamIri);
        graph.add(subjectIri, rdf.createIRI(DOWNSTREAM_PROPERTY), downstreamIri);
        graph.add(subjectIri, rdf.createIRI(RELATIONSHIP_TYPE_PROPERTY),
                rdf.createIRI(relationshipTypeIriFor(relationship.relationshipType())));
        return graph;
    }

    private static String relationshipTypeIriFor(RelationshipType relationshipType) {
        return switch (relationshipType) {
            case PARTNERSHIP -> PARTNERSHIP;
            case SHARED_KERNEL -> SHARED_KERNEL;
            case CUSTOMER_SUPPLIER -> CUSTOMER_SUPPLIER;
            case CONFORMIST -> CONFORMIST;
            case ANTICORRUPTION_LAYER -> ANTICORRUPTION_LAYER;
            case OPEN_HOST_SERVICE -> OPEN_HOST_SERVICE;
            case PUBLISHED_LANGUAGE -> PUBLISHED_LANGUAGE;
            case SEPARATE_WAYS -> SEPARATE_WAYS;
        };
    }

    /** The read-side inverse of {@link #relationshipTypeIriFor}, for {@link #readRelationships}. */
    private static RelationshipType relationshipTypeFor(String iri) {
        if (iri.equals(PARTNERSHIP)) {
            return RelationshipType.PARTNERSHIP;
        }
        if (iri.equals(SHARED_KERNEL)) {
            return RelationshipType.SHARED_KERNEL;
        }
        if (iri.equals(CUSTOMER_SUPPLIER)) {
            return RelationshipType.CUSTOMER_SUPPLIER;
        }
        if (iri.equals(CONFORMIST)) {
            return RelationshipType.CONFORMIST;
        }
        if (iri.equals(ANTICORRUPTION_LAYER)) {
            return RelationshipType.ANTICORRUPTION_LAYER;
        }
        if (iri.equals(OPEN_HOST_SERVICE)) {
            return RelationshipType.OPEN_HOST_SERVICE;
        }
        if (iri.equals(PUBLISHED_LANGUAGE)) {
            return RelationshipType.PUBLISHED_LANGUAGE;
        }
        if (iri.equals(SEPARATE_WAYS)) {
            return RelationshipType.SEPARATE_WAYS;
        }
        throw new IllegalStateException("unknown arkddd:RelationshipType individual: " + iri);
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
