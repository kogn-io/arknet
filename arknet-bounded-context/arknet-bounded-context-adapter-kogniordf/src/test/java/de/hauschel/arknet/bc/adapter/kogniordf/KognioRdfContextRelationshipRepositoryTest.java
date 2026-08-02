// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipId;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Integration test for {@link KognioRdfContextRelationshipRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 *
 * <p>Self-relationship rejection is not exercised here: that guard lives entirely in the
 * {@link ContextRelationship} domain record's compact constructor (decision 6 of issue #125),
 * which {@code BoundedContextServiceTest#linkContextRejectsASelfRelationship} already covers -
 * this adapter never receives a candidate relationship that violates it.</p>
 */
class KognioRdfContextRelationshipRepositoryTest {

    private static final ProjectId WORKSPACE_A = new ProjectId("a");
    private static final String CONTEXT_RELATIONSHIP_TYPE = "https://w3id.org/arknet/ddd#ContextRelationship";
    private static final String BOUNDED_CONTEXT_GRAPH = "https://w3id.org/arknet/model/bounded-context";

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private ContextRelationshipRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = KognioRdfContextRelationshipRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static ContextRelationshipId freshId() {
        return new ContextRelationshipId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static BoundedContextId freshBoundedContextId() {
        return new BoundedContextId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    @Test
    void createWritesTheTypeAndAllThreeEdgesAndReturnsTheRelationship() {
        BoundedContextId upstream = freshBoundedContextId();
        BoundedContextId downstream = freshBoundedContextId();
        ContextRelationship relationship = new ContextRelationship(
                freshId(), upstream, downstream, RelationshipType.CUSTOMER_SUPPLIER);

        ContextRelationship created = repository.create(WORKSPACE_A, relationship);

        assertSame(relationship, created);
        String subject = relationship.id().value().value();
        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "<" + subject + "> a <" + CONTEXT_RELATIONSHIP_TYPE + "> ; "
                + "<https://w3id.org/arknet/ddd#upstream> <" + upstream.value().value() + "> ; "
                + "<https://w3id.org/arknet/ddd#downstream> <" + downstream.value().value() + "> ; "
                + "<https://w3id.org/arknet/ddd#relationshipType> <https://w3id.org/arknet/ddd#CustomerSupplier> "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    @Test
    void createWritesEveryRelationshipTypeAsItsOwnIndividualIri() {
        for (RelationshipType type : RelationshipType.values()) {
            BoundedContextId upstream = freshBoundedContextId();
            BoundedContextId downstream = freshBoundedContextId();
            ContextRelationship relationship = new ContextRelationship(freshId(), upstream, downstream, type);

            repository.create(WORKSPACE_A, relationship);

            String subject = relationship.id().value().value();
            String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + subject
                    + "> <https://w3id.org/arknet/ddd#relationshipType> ?type . "
                    + "FILTER(STRSTARTS(STR(?type), \"https://w3id.org/arknet/ddd#\")) } }";
            try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
                assertTrue(handle.sparqlQuery().ask(ask), "missing relationshipType edge for " + type);
            }
        }
    }

    @Test
    void createRejectsAnAlreadyExistingIdentity() {
        ContextRelationshipId id = freshId();
        ContextRelationship first = new ContextRelationship(
                id, freshBoundedContextId(), freshBoundedContextId(), RelationshipType.PARTNERSHIP);
        repository.create(WORKSPACE_A, first);

        ContextRelationship sameIdentity = new ContextRelationship(
                id, freshBoundedContextId(), freshBoundedContextId(), RelationshipType.SHARED_KERNEL);

        assertThrows(ResourceAlreadyExistsException.class, () -> repository.create(WORKSPACE_A, sameIdentity));
    }

    @Test
    void writeRejectsAMissingRelationshipTypeViaTheShaclGate() {
        // A ContextRelationship without arkddd:relationshipType violates
        // shapes:ContextRelationship-relationshipType (sh:minCount 1, sh:Violation). The domain
        // record cannot construct such a candidate (relationshipType is @NonNull), so this
        // candidate is built directly, mirroring
        // KognioRdfBoundedContextRepositoryTest#writeRejectsABlankNameViaTheShaclGate.
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(CONTEXT_RELATIONSHIP_TYPE));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/ddd#upstream"),
                rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/ddd#downstream"),
                rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID()));

        ShaclWriteGate gate = KognioRdfContextRelationshipRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    @Test
    void writeRejectsAnInvalidRelationshipTypeViaTheShaclGate() {
        // shapes:ContextRelationship-relationshipType constrains arkddd:relationshipType to the
        // eight known arkddd:RelationshipType individuals (sh:in), so a candidate pointing
        // elsewhere - not constructible through the RelationshipType enum - must still be rejected.
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(CONTEXT_RELATIONSHIP_TYPE));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/ddd#upstream"),
                rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/ddd#downstream"),
                rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/ddd#relationshipType"),
                rdf.createIRI("https://w3id.org/arknet/ddd#NotARelationshipType"));

        ShaclWriteGate gate = KognioRdfContextRelationshipRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    @Test
    void createIntoOneProjectIsInvisibleFromAnother() {
        ProjectId workspaceB = new ProjectId("b");
        ContextRelationship relationship = new ContextRelationship(
                freshId(), freshBoundedContextId(), freshBoundedContextId(), RelationshipType.OPEN_HOST_SERVICE);
        repository.create(WORKSPACE_A, relationship);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + relationship.id().value().value()
                + "> a <" + CONTEXT_RELATIONSHIP_TYPE + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceB.value()))) {
            assertEquals(false, handle.sparqlQuery().ask(ask));
        }
    }
}
