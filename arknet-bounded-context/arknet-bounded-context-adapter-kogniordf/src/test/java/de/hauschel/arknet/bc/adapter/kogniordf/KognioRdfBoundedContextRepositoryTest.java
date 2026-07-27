// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

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
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

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
        WriteFunnel funnel = new WriteFunnel(datasetLifecycle, KognioRdfBoundedContextRepositoryFactory.buildGate(),
                KognioRdfBoundedContextRepositoryFactory::isWriteConflict);
        repository = new KognioRdfBoundedContextRepository(datasetLifecycle, funnel);
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

    /**
     * Blank-node regression test for {@code arknet:ubiquitousLanguageTerm} (mirrors the
     * requirements adapter's blank-node preservation test, issue #65): the predicate is not
     * range-constrained to {@code IRI} at the RDF level, so a store-first edge can legally target
     * a blank node - {@code [ a skos:Concept ]} written directly into the bounded-context graph.
     * {@link de.hauschel.arknet.kernel.ResourceId} cannot represent a blank node, so
     * {@code readUsesTerms} never surfaces it as a {@link TermRef} - but
     * {@code replaceTriples} must still capture and re-attach it across an unrelated
     * {@code update()} - here, one that links an IRI term, exactly as {@code bc_link_term} does -
     * instead of erasing it.
     */
    @Test
    void updatePreservesABlankNodeUbiquitousLanguageTermEdge() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(WORKSPACE_A, original);

        String insertBlankTerm = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <"
                + id.value().value() + "> <https://w3id.org/arknet/core#ubiquitousLanguageTerm> "
                + "[ a <http://www.w3.org/2004/02/skos/core#Concept> ] } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insertBlankTerm);
                return null;
            });
        }

        TermRef term = new TermRef(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        BoundedContext changed = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of(term));
        repository.update(WORKSPACE_A, changed);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/core#ubiquitousLanguageTerm> ?term . "
                + "?term a <http://www.w3.org/2004/02/skos/core#Concept> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask), "blank-node edge must survive the update and still "
                    + "point at its typed node - not merely at some blank node");
        }
    }

    /**
     * Blank-node regression test for {@code arknet:hasAggregate}: {@link BoundedContext} carries
     * no field for aggregates at all, so - unlike {@code ubiquitousLanguageTerm} - there is no
     * IRI-typed round-trip through the domain object to fall back on. This pins that a blank-node
     * aggregate survives an unrelated {@code update()} exactly as an IRI-target one already does
     * ({@link #updatePreservesAStoreFirstHasAggregateEdge}) - "regardless of target kind", per the
     * {@code replaceTriples} javadoc.
     */
    @Test
    void updatePreservesABlankNodeHasAggregateEdge() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(WORKSPACE_A, original);

        String insertBlankAggregate = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <"
                + id.value().value() + "> <https://w3id.org/arknet/core#hasAggregate> "
                + "[ a <https://w3id.org/arknet/core#Aggregate> ] } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insertBlankAggregate);
                return null;
            });
        }

        BoundedContext changed = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, "orders-team", List.of());
        repository.update(WORKSPACE_A, changed);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/core#hasAggregate> ?aggregate . "
                + "?aggregate a <https://w3id.org/arknet/core#Aggregate> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask), "blank-node edge must survive the update and still "
                    + "point at its typed node - not merely at some blank node");
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

    // ---- revision trail (ADR-014): one revision per write, head queryable ----------------

    /**
     * ADR-014 revision basis, exercised against a real store: every write through the funnel
     * records exactly one immutable revision, the head is queryable per resource and moves
     * with every update, and the new head chains to the superseded one.
     */
    @Test
    void everyWriteRecordsExactlyOneRevisionAndMovesTheQueryableHead() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());
        repository.create(WORKSPACE_A, bc);
        String subject = bc.id().value().value();

        List<String> afterCreate = revisionsOf(subject);
        assertEquals(1, afterCreate.size(), "create must record exactly one revision");
        assertEquals(afterCreate, headsOf(subject), "the head must point at the sole revision");

        repository.update(WORKSPACE_A, new BoundedContext(bc.id(), bc.code(), "Renamed",
                bc.domainVision(), bc.subdomain(), bc.ownedBy(), bc.usesTerms()));

        assertEquals(2, revisionsOf(subject).size(), "update must record exactly one more revision");
        List<String> heads = headsOf(subject);
        assertEquals(1, heads.size(), "the head is rewritten, never duplicated");
        String previousHead = afterCreate.get(0);
        String newHead = heads.get(0);
        assertFalse(newHead.equals(previousHead), "the head must have moved");
        assertEquals(List.of(previousHead), objectsOf(newHead, ArkprovVocabulary.WAS_REVISION_OF),
                "the new head must supersede the previous one via prov:wasRevisionOf");
    }

    /**
     * Atomicity against a real store: a write rejected inside the transaction (here the
     * duplicate-code check) rolls back as a whole and leaves no revision behind.
     */
    @Test
    void aRejectedWriteLeavesNoRevisionBehind() {
        repository.create(WORKSPACE_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));

        assertThrows(DuplicateBoundedContextCodeException.class, () -> repository.create(WORKSPACE_A,
                boundedContext(new BoundedContextCode("BC-1"), null, null, List.of())));

        String all = "SELECT ?r WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?r a <" + ArkprovVocabulary.REVISION_TYPE + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            assertEquals(1, handle.sparqlQuery().select(all).count(),
                    "the rejected write must not have recorded a revision");
        }
    }

    private List<String> revisionsOf(String subjectIri) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?v a <" + ArkprovVocabulary.REVISION_TYPE + "> ; "
                + "<" + ArkprovVocabulary.SPECIALIZATION_OF + "> <" + subjectIri + "> } }");
    }

    private List<String> headsOf(String subjectIri) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + subjectIri + "> <" + ArkprovVocabulary.HEAD + "> ?v } }");
    }

    private List<String> objectsOf(String subjectIri, String predicateIri) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + subjectIri + "> <" + predicateIri + "> ?v } }");
    }

    private List<String> selectIris(String query) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .toList();
        }
    }
}
