package de.hauschel.arknet.bc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;

/**
 * Integration test for {@link KognioRdfBoundedContextRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 */
class KognioRdfBoundedContextRepositoryTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("a");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("b");
    private static final String BOUNDED_CONTEXT_TYPE = "https://w3id.org/arknet/core#BoundedContext";
    private static final String BOUNDED_CONTEXT_GRAPH = "https://w3id.org/arknet/model/bounded-context";

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfBoundedContextRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-bc-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = new KognioRdfBoundedContextRepository(
                datasetLifecycle, KognioRdfBoundedContextRepositoryFactory.buildGate());
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    /** Fresh, valid opaque identity - every test picks its own so ids never collide. */
    private static BoundedContextId freshId() {
        return new BoundedContextId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static BoundedContext boundedContext(BoundedContextCode code, Subdomain subdomain, String ownedBy,
            List<TermRef> usesTerms) {
        return new BoundedContext(freshId(), code, "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                subdomain, ownedBy, usesTerms);
    }

    @Test
    void createsAndFindsBoundedContextByCode() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"),
                Subdomain.CORE_DOMAIN, "orders-team", List.of());

        repository.create(WORKSPACE_A, bc);
        Optional<BoundedContext> found = repository.findByCode(WORKSPACE_A, new BoundedContextCode("BC-1"));

        assertEquals(Optional.of(bc), found);
        assertEquals("OrderManagement", found.orElseThrow().name());
        assertEquals(Subdomain.CORE_DOMAIN, found.orElseThrow().subdomain());
        assertEquals("orders-team", found.orElseThrow().ownedBy());
    }

    @Test
    void createsAndReadsBackWithoutOptionalFields() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());

        repository.create(WORKSPACE_A, bc);
        BoundedContext found = repository.findByCode(WORKSPACE_A, new BoundedContextCode("BC-1")).orElseThrow();

        assertNull(found.subdomain());
        assertNull(found.ownedBy());
    }

    @Test
    void findAllReturnsEveryStoredBoundedContext() {
        repository.create(WORKSPACE_A, boundedContext(new BoundedContextCode("BC-1"),
                Subdomain.CORE_DOMAIN, null, List.of()));
        repository.create(WORKSPACE_A, boundedContext(new BoundedContextCode("BC-2"),
                Subdomain.SUPPORTING_DOMAIN, null, List.of()));

        List<BoundedContext> all = repository.findAll(WORKSPACE_A);

        assertEquals(2, all.size());
    }

    @Test
    void findByCodeIsEmptyForUnknownCode() {
        assertTrue(repository.findByCode(WORKSPACE_A, new BoundedContextCode("BC-99")).isEmpty());
    }

    @Test
    void createRejectsAnAlreadyExistingIdentity() {
        BoundedContextId id = freshId();
        BoundedContext first = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(WORKSPACE_A, first);

        BoundedContext sameIdentity = new BoundedContext(id, new BoundedContextCode("BC-2"), "Inventory",
                "Tracks the stock levels of every sellable product across warehouses.", null, null, List.of());

        assertThrows(ResourceAlreadyExistsException.class, () -> repository.create(WORKSPACE_A, sameIdentity));
    }

    @Test
    void createRejectsADuplicateBusinessCodeOnADifferentIdentity() {
        repository.create(WORKSPACE_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));

        BoundedContext sameCode = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());

        assertThrows(DuplicateBoundedContextCodeException.class,
                () -> repository.create(WORKSPACE_A, sameCode));
    }

    @Test
    void updateReplacesAnExistingBoundedContext() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, "orders-team", List.of());
        repository.create(WORKSPACE_A, original);

        BoundedContext changed = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.SUPPORTING_DOMAIN, "platform-team", List.of());
        repository.update(WORKSPACE_A, changed);

        BoundedContext found = repository.findByCode(WORKSPACE_A, new BoundedContextCode("BC-1")).orElseThrow();
        assertEquals(Subdomain.SUPPORTING_DOMAIN, found.subdomain());
        assertEquals("platform-team", found.ownedBy());
    }

    @Test
    void updateRejectsAMissingIdentity() {
        BoundedContext missing = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());

        assertThrows(BoundedContextNotFoundException.class, () -> repository.update(WORKSPACE_A, missing));
    }

    @Test
    void writeRejectsABlankNameViaTheShaclGate() {
        // A blank name violates shapes:BoundedContext-name (sh:minLength 2, sh:Violation). The
        // domain record forbids a blank name too, so this candidate is built directly rather than
        // through the BoundedContext constructor.
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(BOUNDED_CONTEXT_TYPE));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/core#name"), rdf.createLiteral("x"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/core#domainVision"),
                rdf.createLiteral("A vision long enough to satisfy the ten-character minimum."));

        ShaclWriteGate gate = KognioRdfBoundedContextRepositoryFactory.buildGate();
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    @Test
    void aBoundedContextWithoutAggregatesPassesTheGate() {
        // shapes:BoundedContext-hasAggregate was lowered to sh:Warning (issue #66): a store-first
        // bounded context minted during analysis has no aggregates yet, and that must not block
        // the write.
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"),
                Subdomain.CORE_DOMAIN, "orders-team", List.of());

        repository.create(WORKSPACE_A, bc);

        assertTrue(repository.findByCode(WORKSPACE_A, new BoundedContextCode("BC-1")).isPresent());
    }

    @Test
    void writePersistsUbiquitousLanguageTermEdgesAndReadsThemBack() {
        TermRef term1 = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));
        TermRef term2 = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-2"));
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"),
                Subdomain.CORE_DOMAIN, "orders-team", List.of(term1, term2));

        repository.create(WORKSPACE_A, bc);
        BoundedContext found = repository.findByCode(WORKSPACE_A, new BoundedContextCode("BC-1")).orElseThrow();

        assertEquals(List.of(term1, term2), found.usesTerms());
    }

    /**
     * Replace-by-identity regression: an {@code update()} that keeps the same term edges must not
     * drop them, and one that adds an edge must carry the earlier one along. This mirrors the edge
     * preservation the application service's {@code linkTerm} relies on.
     */
    @Test
    void updatePreservesAndExtendsUbiquitousLanguageTermEdges() {
        BoundedContextId id = freshId();
        TermRef term1 = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null,
                List.of(term1));
        repository.create(WORKSPACE_A, original);

        TermRef term2 = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-2"));
        BoundedContext extended = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null,
                List.of(term1, term2));
        repository.update(WORKSPACE_A, extended);

        BoundedContext found = repository.findByCode(WORKSPACE_A, new BoundedContextCode("BC-1")).orElseThrow();
        assertEquals(List.of(term1, term2), found.usesTerms());
    }

    /**
     * Replace-by-identity regression for the field {@link BoundedContext} does not carry at all:
     * a store-first {@code arknet:hasAggregate} edge (set directly against the store, since
     * {@code bc_add}/{@code bc_link_term} never write one) must survive an unrelated
     * {@code update()} - e.g. the one {@code bc_link_term} performs - instead of being silently
     * dropped by the replace-by-identity rewrite.
     */
    @Test
    void updatePreservesAStoreFirstHasAggregateEdge() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(WORKSPACE_A, original);

        String aggregateIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String insertAggregate = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <"
                + id.value().value() + "> <https://w3id.org/arknet/core#hasAggregate> <" + aggregateIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insertAggregate);
                return null;
            });
        }

        BoundedContext changed = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, "orders-team", List.of());
        repository.update(WORKSPACE_A, changed);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/core#hasAggregate> <" + aggregateIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    @Test
    void workspacesAreIsolated() {
        repository.create(WORKSPACE_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));

        assertFalse(repository.findByCode(WORKSPACE_B, new BoundedContextCode("BC-1")).isPresent());
        assertTrue(repository.findAll(WORKSPACE_B).isEmpty());
    }

    /** A store-first bounded context is what actually lands in the shared workspace dataset. */
    @Test
    void writesIntoTheBoundedContextNamedGraph() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());
        repository.create(WORKSPACE_A, bc);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + bc.id().value().value()
                + "> a <" + BOUNDED_CONTEXT_TYPE + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }
}
