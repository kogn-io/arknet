// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import java.util.Objects;

import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
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
 * <p><strong>Pure create - no CAS, no dedup (decision 4 of issue #125).</strong> Unlike
 * {@code KognioRdfBoundedContextRepository}, this adapter has no {@code compareAndUpdate} and
 * performs no existing-triple check beyond the identity collision {@link WriteFunnel#create}
 * always runs: every {@link #create} call persists a brand-new resource. {@link ContextRelationship}
 * carries no human-readable business code of its own (unlike {@code BoundedContextCode}), so
 * there is no second uniqueness rule to check either - the {@code code} parameter
 * {@link WriteFunnel#create} still requires is passed as the relationship's own freshly minted
 * subject IRI, a value this adapter never writes as a {@code dcterms:identifier} triple. That
 * check can therefore structurally never match an existing triple, and every failure mode
 * {@link WriteFunnel#create} can report - the identity guard, the (dead) code guard, and a lost
 * {@code SERIALIZABLE} commit conflict alike - collapses onto the single, honest
 * {@link ResourceAlreadyExistsException} signal: "something else already claimed this identity".
 * </p>
 *
 * <p><strong>Relationship-type IRI mapping mirrors {@code Subdomain}'s.</strong> The eight private
 * {@code String} constants below are named exactly like the {@link RelationshipType} enum
 * constants they map to/from, and {@link #relationshipTypeIriFor} switches on the enum the same
 * way {@code KognioRdfBoundedContextRepository#subdomainIriFor} switches on
 * {@code Subdomain} - same idiom, same reasoning: a compiler-checked, exhaustive mapping with no
 * risk of an enum constant silently mapping to nothing.</p>
 *
 * <p><strong>{@code upstream}/{@code downstream} are shared (issue #293), the rest stays
 * local.</strong> {@code arkddd:upstream}/{@code downstream} now come from {@link ArkdddVocabulary},
 * the same shared source {@code arknet-mcp}'s {@code de.hauschel.arknet.mcp.trace.TraceabilityGraph}
 * traverses them from for {@code impact_analysis} - before issue #293 that traversal simply did not
 * know about {@code ContextRelationship} at all, so the two sides never risked disagreeing on the
 * IRI, but a future rename now cannot silently desync them either. {@code arkddd:ContextRelationship}/
 * {@code relationshipType} and the eight {@code arkddd:RelationshipType} individual IRIs remain
 * private local constants here, exactly like {@code KognioRdfBoundedContextRepository} already
 * keeps {@code PART_OF_PROPERTY}/{@code SUBDOMAIN_TYPE_PROPERTY}: nothing outside this adapter
 * reads them.</p>
 *
 * <p><strong>No read method (decision 5).</strong> This port and adapter expose no
 * {@code findAll}/{@code findByCode}: inspecting a created relationship goes through the generic
 * store-wide read path ({@code store_overview}/{@code resource_get}), not a dedicated
 * {@code bc_get_context}/{@code bc_list_context} tool.</p>
 *
 * <p><strong>Validation-only asserted context, same reasoning as requirements'
 * {@code arkreq:usesTerm}.</strong> {@code shapes:ContextRelationship-upstream}/
 * {@code -downstream} carry an {@code sh:class arkddd:BoundedContext} constraint, but the type
 * triple of the referenced bounded context lives in its own already-committed write, not in this
 * candidate graph. {@link #create} therefore hands the gate a validation-only
 * {@code assertedContext} graph asserting {@code <upstreamIri> a arkddd:BoundedContext} and
 * {@code <downstreamIri> a arkddd:BoundedContext} - never persisted, exactly the pattern
 * {@code KognioRdfRequirementRepository#create} uses for {@code arkreq:usesTerm}'s
 * {@code skos:Concept} constraint. This is safe because {@code BoundedContextCode} resolution in
 * the application service (via {@code BoundedContextRepository#findByCode}) already proved both
 * referenced identities exist and are bounded contexts before {@link #create} is ever called -
 * the resolution, not the shape, is what keeps the edge non-dangling. That very type assertion is
 * also why {@link KognioRdfContextRelationshipRepositoryFactory} loads a <em>filtered</em> shapes
 * graph rather than reusing {@code KognioRdfBoundedContextRepositoryFactory}'s gate unfiltered -
 * see that factory's class javadoc.</p>
 */
public class KognioRdfContextRelationshipRepository implements ContextRelationshipRepository {

    private static final String ARKDDD_NAMESPACE = "https://w3id.org/arknet/ddd#";

    /** Shares {@code KognioRdfBoundedContextRepository}'s named graph, not a private one of its own. */
    private static final String BOUNDED_CONTEXT_GRAPH = "https://w3id.org/arknet/model/bounded-context";

    private static final String CONTEXT_RELATIONSHIP_TYPE = ARKDDD_NAMESPACE + "ContextRelationship";
    /** {@code shapes:ContextRelationship-upstream}/{@code -downstream}'s {@code sh:class} target. */
    private static final String BOUNDED_CONTEXT_TYPE = ARKDDD_NAMESPACE + "BoundedContext";
    private static final String UPSTREAM_PROPERTY = ArkdddVocabulary.UPSTREAM;
    private static final String DOWNSTREAM_PROPERTY = ArkdddVocabulary.DOWNSTREAM;
    private static final String RELATIONSHIP_TYPE_PROPERTY = ARKDDD_NAMESPACE + "relationshipType";

    private static final String PARTNERSHIP = ARKDDD_NAMESPACE + "Partnership";
    private static final String SHARED_KERNEL = ARKDDD_NAMESPACE + "SharedKernel";
    private static final String CUSTOMER_SUPPLIER = ARKDDD_NAMESPACE + "CustomerSupplier";
    private static final String CONFORMIST = ARKDDD_NAMESPACE + "Conformist";
    private static final String ANTICORRUPTION_LAYER = ARKDDD_NAMESPACE + "AnticorruptionLayer";
    private static final String OPEN_HOST_SERVICE = ARKDDD_NAMESPACE + "OpenHostService";
    private static final String PUBLISHED_LANGUAGE = ARKDDD_NAMESPACE + "PublishedLanguage";
    private static final String SEPARATE_WAYS = ARKDDD_NAMESPACE + "SeparateWays";

    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param funnel the shared write funnel running the SHACL gate, dataset acquisition
     *               and the identity-existence check for every {@link #create} (must not be
     *               {@code null})
     */
    KognioRdfContextRelationshipRepository(WriteFunnel funnel) {
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public ContextRelationship create(ProjectId projectId, ContextRelationship relationship) {
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

        // See the class javadoc's "pure create" note for why the freshly minted subject IRI
        // itself doubles as the funnel's "code" parameter, and why every one of the funnel's three
        // failure signals collapses onto the same ResourceAlreadyExistsException here.
        funnel.create(new DatasetId(projectId.value()), BOUNDED_CONTEXT_GRAPH, subjectIriString, subjectIriString,
                graph, assertedContext,
                () -> new ResourceAlreadyExistsException(projectId, relationship.id().value()),
                () -> new ResourceAlreadyExistsException(projectId, relationship.id().value()),
                conflict -> new ResourceAlreadyExistsException(projectId, relationship.id().value()),
                tx -> tx.add(graphIri, graph));
        return relationship;
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
}
