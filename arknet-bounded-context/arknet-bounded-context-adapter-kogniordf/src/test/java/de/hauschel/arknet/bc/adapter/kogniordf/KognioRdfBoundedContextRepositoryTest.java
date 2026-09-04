// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.Level;
import ch.qos.logback.core.read.ListAppender;

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
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Integration test for {@link KognioRdfBoundedContextRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 */
class KognioRdfBoundedContextRepositoryTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String BOUNDED_CONTEXT_TYPE = "https://w3id.org/arknet/ddd#BoundedContext";
    private static final String BOUNDED_CONTEXT_GRAPH = "https://w3id.org/arknet/model/bounded-context";

    /**
     * The store's on-disk home, managed by JUnit rather than {@code Files.createTempDirectory},
     * which left its directories behind - harmless while the store is {@code IN_MEMORY}, but
     * still an inode left in {@code /tmp} for every test run. Deleted after {@link #tearDown()}
     * has shut the store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfBoundedContextRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        ShaclWriteGate gate = KognioRdfBoundedContextRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        WriteFunnel funnel = new WriteFunnel(datasetLifecycle, gate, WriteFunnel.DEFAULT_WRITE_CONFLICT);
        repository = new KognioRdfBoundedContextRepository(datasetLifecycle, new UuidResourceIdFactory(), funnel);
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

        repository.create(PROJECT_A, bc);
        Optional<BoundedContext> found = repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1"));

        assertEquals(Optional.of(bc), found);
        assertEquals("OrderManagement", found.orElseThrow().name());
        assertEquals(Subdomain.CORE_DOMAIN, found.orElseThrow().subdomain());
        assertEquals("orders-team", found.orElseThrow().ownedBy());
    }

    @Test
    void createsAndReadsBackWithoutOptionalFields() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());

        repository.create(PROJECT_A, bc);
        BoundedContext found = repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1")).orElseThrow();

        assertNull(found.subdomain());
        assertNull(found.ownedBy());
    }

    @Test
    void findAllReturnsEveryStoredBoundedContext() {
        repository.create(PROJECT_A, boundedContext(new BoundedContextCode("BC-1"),
                Subdomain.CORE_DOMAIN, null, List.of()));
        repository.create(PROJECT_A, boundedContext(new BoundedContextCode("BC-2"),
                Subdomain.SUPPORTING_DOMAIN, null, List.of()));

        List<BoundedContext> all = repository.findAll(PROJECT_A);

        assertEquals(2, all.size());
    }

    /**
     * What {@link BoundedContextRepository#findAllCodes} exists for (kogn-io/arknet#360), pinned
     * against the real store: the query joins the type triple and {@code dcterms:identifier} and
     * nothing else, so a code stays taken even by a subject {@code findAll} cannot materialise at
     * all. Deliberately the barest such subject there is - no {@code arknet:name}, no
     * {@code arkddd:domainVision}, the two joins {@code findAll} makes mandatory. Any predicate
     * joined into {@code findAllCodes} later (the tempting "surely the code alone is not enough")
     * fails here rather than silently handing {@code BC-2} out a second time.
     */
    @Test
    void findAllCodesKeepsTheCodeOfASubjectFindAllCannotMaterialiseAtAll() {
        repository.create(PROJECT_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));
        String bare = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        insertTriple(bare, VocabRdf.TYPE.getIRIString(), "<" + BOUNDED_CONTEXT_TYPE + ">");
        insertTriple(bare, "http://purl.org/dc/terms/identifier", "\"BC-2\"");

        List<BoundedContext> all = repository.findAll(PROJECT_A);

        assertEquals(1, all.size());
        assertEquals(new BoundedContextCode("BC-1"), all.get(0).code());
        assertTrue(repository.findByCode(PROJECT_A, new BoundedContextCode("BC-2")).isEmpty());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new BoundedContextCode("BC-2")));
    }

    /**
     * What the missing {@code FILTER(isIRI(?s))} buys (kogn-io/arknet#360): a {@code BC-2} carried
     * by an anonymous subject is counted just like one on an IRI. The write path sets that
     * standard - {@code WriteFunnel#create} checks the code with
     * {@code tx.contains(graph, null, dcterms:identifier, code)} and a wildcard subject, so it
     * would already have refused a {@code bc_add} for {@code BC-2}. Were the counter narrower than
     * the guard, every attempt would compute the same refused number and the bounded context could
     * add nothing further. The filter is easy to re-add out of symmetry with {@code findAll}; this
     * test is what stops that.
     */
    @Test
    void findAllCodesCountsACodeHeldByABlankNodeSubject() {
        repository.create(PROJECT_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));
        String insertBlankNodeCode = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { [] a <"
                + BOUNDED_CONTEXT_TYPE + "> ; <http://purl.org/dc/terms/identifier> \"BC-2\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insertBlankNodeCode);
                return null;
            });
        }

        List<BoundedContext> all = repository.findAll(PROJECT_A);

        assertEquals(1, all.size());
        assertEquals(new BoundedContextCode("BC-1"), all.get(0).code());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new BoundedContextCode("BC-2")),
                repository.findAllCodes(PROJECT_A).toString());
    }

    /**
     * The node-kind case of the <em>listing</em> read (kogn-io/arknet#401). The bare blank node
     * above misses {@code arknet:name} and {@code arkddd:domainVision}, both mandatory joins, and
     * so never reaches {@code findAll}'s {@link IRI} cast; a blank-node subject carrying them - a
     * complete bounded context in everything but its node kind, which only a store-first write can
     * produce - does. Before the guard that cast threw a {@code ClassCastException} out of the
     * whole call, so one anonymous subject cost the project its entire {@code bc_list} rather than
     * the one row it cannot address. The {@code arkddd:ubiquitousLanguageTerm} edge is part of the
     * fixture on purpose: the bulk term read behind the listing joins that predicate alone, with
     * no type join in front of it, and casts {@code ?s} of its own.
     *
     * <p>{@code findByCode} shares the clause and is asserted with it: a {@code BC-2} nobody can
     * name by IRI reads as absent, not as an exception.</p>
     */
    @Test
    void findAllSkipsAFullyPopulatedBlankNodeSubjectInsteadOfCrashingTheWholeListing() {
        repository.create(PROJECT_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));
        String insertBlankNodeContext = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "[] a <" + BOUNDED_CONTEXT_TYPE + "> ; "
                + "<http://purl.org/dc/terms/identifier> \"BC-2\" ; "
                + "<https://w3id.org/arknet/core#name> \"Anonymous\" ; "
                + "<https://w3id.org/arknet/ddd#domainVision> \"No identity.\" ; "
                + "<https://w3id.org/arknet/ddd#ubiquitousLanguageTerm> "
                + "<https://w3id.org/arknet/id/some-term> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insertBlankNodeContext);
                return null;
            });
        }

        List<BoundedContext> all = repository.findAll(PROJECT_A);

        assertEquals(1, all.size());
        assertEquals(new BoundedContextCode("BC-1"), all.get(0).code());
        assertTrue(repository.findByCode(PROJECT_A, new BoundedContextCode("BC-2")).isEmpty());
    }

    @Test
    void findByCodeIsEmptyForUnknownCode() {
        assertTrue(repository.findByCode(PROJECT_A, new BoundedContextCode("BC-99")).isEmpty());
    }

    @Test
    void createRejectsAnAlreadyExistingIdentity() {
        BoundedContextId id = freshId();
        BoundedContext first = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(PROJECT_A, first);

        BoundedContext sameIdentity = new BoundedContext(id, new BoundedContextCode("BC-2"), "Inventory",
                "Tracks the stock levels of every sellable product across warehouses.", null, null, List.of());

        assertThrows(ResourceAlreadyExistsException.class, () -> repository.create(PROJECT_A, sameIdentity));
    }

    @Test
    void createRejectsADuplicateBusinessCodeOnADifferentIdentity() {
        repository.create(PROJECT_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));

        BoundedContext sameCode = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());

        assertThrows(DuplicateBoundedContextCodeException.class,
                () -> repository.create(PROJECT_A, sameCode));
    }

    @Test
    void updateReplacesAnExistingBoundedContext() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, "orders-team", List.of());
        repository.create(PROJECT_A, original);

        BoundedContext changed = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.SUPPORTING_DOMAIN, "platform-team", List.of());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(changed.code()), changed);

        BoundedContext found = repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1")).orElseThrow();
        assertEquals(Subdomain.SUPPORTING_DOMAIN, found.subdomain());
        assertEquals("platform-team", found.ownedBy());
    }

    @Test
    void updateRejectsAMissingIdentity() {
        BoundedContext missing = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());

        assertThrows(BoundedContextNotFoundException.class,
                () -> repository.compareAndUpdate(PROJECT_A, null, missing));
    }

    /**
     * Issue #164: {@code compareAndUpdate} must enforce the same business-code uniqueness
     * {@code create} already does. Changing a bounded context's code to one already held by a
     * <em>different</em> identity must be rejected, not silently committed.
     */
    @Test
    void compareAndUpdateRejectsACodeChangedToCollideWithAnotherBoundedContext() {
        repository.create(PROJECT_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));
        BoundedContextId id = freshId();
        BoundedContext second = new BoundedContext(id, new BoundedContextCode("BC-2"), "Inventory",
                "Tracks the stock levels of every sellable product across warehouses.", null, null, List.of());
        repository.create(PROJECT_A, second);
        RevisionToken head = currentHeadOf(second.code());

        BoundedContext recodedToCollide = new BoundedContext(id, new BoundedContextCode("BC-1"), second.name(),
                second.domainVision(), second.subdomain(), second.ownedBy(), second.usesTerms());

        assertThrows(DuplicateBoundedContextCodeException.class,
                () -> repository.compareAndUpdate(PROJECT_A, head, recodedToCollide));
        assertEquals("Inventory", repository.findByCode(PROJECT_A, new BoundedContextCode("BC-2"))
                .orElseThrow().name(), "the rejected write must not have changed anything");
        assertEquals(1, revisionsOf(id.value().value()).size(),
                "the rejected write must not have recorded a revision");
    }

    /**
     * The unchanged-code path every real caller today ({@code linkTerm}) exercises must keep
     * working: a {@code compareAndUpdate} that resubmits the identity's own current code is not a
     * collision with itself.
     */
    @Test
    void compareAndUpdateAcceptsAnUnchangedCode() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(PROJECT_A, original);

        BoundedContext renamedOnly = new BoundedContext(id, new BoundedContextCode("BC-1"), "Renamed",
                original.domainVision(), original.subdomain(), original.ownedBy(), original.usesTerms());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), renamedOnly);

        assertEquals("Renamed", repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1"))
                .orElseThrow().name());
    }

    /**
     * A code change to a code nobody else holds must go through, distinguishing "collides with
     * another identity" from "differs from what it used to be".
     */
    @Test
    void compareAndUpdateAcceptsACodeChangedToAFreeCode() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(PROJECT_A, original);

        BoundedContext recoded = new BoundedContext(id, new BoundedContextCode("BC-9"), original.name(),
                original.domainVision(), original.subdomain(), original.ownedBy(), original.usesTerms());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), recoded);

        assertTrue(repository.findByCode(PROJECT_A, new BoundedContextCode("BC-9")).isPresent());
        assertTrue(repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1")).isEmpty());
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
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/ddd#domainVision"),
                rdf.createLiteral("A vision long enough to satisfy the ten-character minimum."));

        ShaclWriteGate gate = KognioRdfBoundedContextRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    @Test
    void aBoundedContextWithoutAggregatesPassesTheGate() {
        // shapes:BoundedContext-hasAggregate was lowered to sh:Warning: a store-first
        // bounded context minted during analysis has no aggregates yet, and that must not block
        // the write.
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"),
                Subdomain.CORE_DOMAIN, "orders-team", List.of());

        repository.create(PROJECT_A, bc);

        assertTrue(repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1")).isPresent());
    }

    @Test
    void writePersistsUbiquitousLanguageTermEdgesAndReadsThemBack() {
        TermRef term1 = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));
        TermRef term2 = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-2"));
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"),
                Subdomain.CORE_DOMAIN, "orders-team", List.of(term1, term2));

        repository.create(PROJECT_A, bc);
        BoundedContext found = repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1")).orElseThrow();

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
        repository.create(PROJECT_A, original);

        TermRef term2 = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-2"));
        BoundedContext extended = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null,
                List.of(term1, term2));
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(extended.code()), extended);

        BoundedContext found = repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1")).orElseThrow();
        assertEquals(List.of(term1, term2), found.usesTerms());
    }

    /**
     * Replace-by-identity regression for the field {@link BoundedContext} does not carry at all:
     * a store-first {@code arkddd:hasAggregate} edge (set directly against the store, since
     * {@code bc_add}/{@code bc_link_term} never write one) must survive an unrelated
     * {@code update()} - e.g. the one {@code bc_link_term} performs - instead of being silently
     * dropped by the replace-by-identity rewrite.
     */
    @Test
    void updatePreservesAStoreFirstHasAggregateEdge() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(PROJECT_A, original);

        String aggregateIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String insertAggregate = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <"
                + id.value().value() + "> <https://w3id.org/arknet/ddd#hasAggregate> <" + aggregateIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insertAggregate);
                return null;
            });
        }

        BoundedContext changed = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, "orders-team", List.of());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(changed.code()), changed);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/ddd#hasAggregate> <" + aggregateIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    /**
     * Blank-node regression test for {@code arkddd:ubiquitousLanguageTerm} (mirrors the
     * requirements adapter's blank-node preservation test): the predicate is not
     * range-constrained to {@code IRI} at the RDF level, so a store-first edge can legally target
     * a blank node - {@code [ a skos:Concept ]} written directly into the bounded-context graph.
     * {@link de.hauschel.arknet.kernel.ResourceId} cannot represent a blank node, so
     * {@code readUsesTerms} never surfaces it as a {@link TermRef} - but
     * {@code replaceExistingTriples} must still capture and re-attach it across an unrelated
     * {@code update()} - here, one that links an IRI term, exactly as {@code bc_link_term} does -
     * instead of erasing it.
     */
    @Test
    void updatePreservesABlankNodeUbiquitousLanguageTermEdge() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(PROJECT_A, original);

        String insertBlankTerm = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <"
                + id.value().value() + "> <https://w3id.org/arknet/ddd#ubiquitousLanguageTerm> "
                + "[ a <http://www.w3.org/2004/02/skos/core#Concept> ] } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insertBlankTerm);
                return null;
            });
        }

        TermRef term = new TermRef(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        BoundedContext changed = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of(term));
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(changed.code()), changed);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/ddd#ubiquitousLanguageTerm> ?term . "
                + "?term a <http://www.w3.org/2004/02/skos/core#Concept> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask), "blank-node edge must survive the update and still "
                    + "point at its typed node - not merely at some blank node");
        }
    }

    /**
     * Blank-node regression test for {@code arkddd:hasAggregate}: {@link BoundedContext} carries
     * no field for aggregates at all, so - unlike {@code ubiquitousLanguageTerm} - there is no
     * IRI-typed round-trip through the domain object to fall back on. This pins that a blank-node
     * aggregate survives an unrelated {@code update()} exactly as an IRI-target one already does
     * ({@link #updatePreservesAStoreFirstHasAggregateEdge}) - "regardless of target kind", per the
     * {@code replaceExistingTriples} javadoc.
     */
    @Test
    void updatePreservesABlankNodeHasAggregateEdge() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(PROJECT_A, original);

        String insertBlankAggregate = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <"
                + id.value().value() + "> <https://w3id.org/arknet/ddd#hasAggregate> "
                + "[ a <https://w3id.org/arknet/core#Aggregate> ] } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insertBlankAggregate);
                return null;
            });
        }

        BoundedContext changed = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, "orders-team", List.of());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(changed.code()), changed);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/ddd#hasAggregate> ?aggregate . "
                + "?aggregate a <https://w3id.org/arknet/core#Aggregate> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask), "blank-node edge must survive the update and still "
                    + "point at its typed node - not merely at some blank node");
        }
    }

    @Test
    void projectsAreIsolated() {
        repository.create(PROJECT_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));

        assertFalse(repository.findByCode(PROJECT_B, new BoundedContextCode("BC-1")).isPresent());
        assertTrue(repository.findAll(PROJECT_B).isEmpty());
    }

    /** A store-first bounded context is what actually lands in the shared project dataset. */
    @Test
    void writesIntoTheBoundedContextNamedGraph() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());
        repository.create(PROJECT_A, bc);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + bc.id().value().value()
                + "> a <" + BOUNDED_CONTEXT_TYPE + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    /**
     * Pins the on-disk shape of a subdomain classification: a bounded context with
     * {@link Subdomain#CORE_DOMAIN} does not carry {@code arkddd:subdomainType} directly - it
     * carries {@code arkddd:partOf} to a freshly minted, distinct node typed
     * {@code arkddd:Subdomain}, and that node carries {@code arkddd:subdomainType arkddd:CoreDomain}.
     */
    @Test
    void writesSubdomainAsADerivedPartOfNode() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"), Subdomain.CORE_DOMAIN, null, List.of());
        repository.create(PROJECT_A, bc);

        String query = "SELECT ?subdomainNode WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <"
                + bc.id().value().value() + "> <https://w3id.org/arknet/ddd#partOf> ?subdomainNode } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            String subdomainNode = handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("subdomainNode").orElseThrow()).getIRIString())
                    .findFirst().orElseThrow();
            assertFalse(subdomainNode.equals(bc.id().value().value()),
                    "the subdomain classification must live on its own node, not the bounded context itself");

            String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + subdomainNode
                    + "> a <https://w3id.org/arknet/ddd#Subdomain> ; "
                    + "<https://w3id.org/arknet/ddd#subdomainType> <https://w3id.org/arknet/ddd#CoreDomain> } }";
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    /**
     * Orphan-cleanup regression: an update that changes the subdomain classification
     * must not leave the superseded {@code arkddd:Subdomain} node's triples behind as disconnected
     * garbage - {@link KognioRdfBoundedContextRepository#replaceExistingTriples} follows the
     * {@code arkddd:partOf} edge to delete it, mirroring the use-case adapter's step-following
     * delete.
     */
    @Test
    void updateDeletesTheSupersededSubdomainNode() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, null, List.of());
        repository.create(PROJECT_A, original);

        String subdomainNodeQuery = "SELECT ?subdomainNode WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <"
                + id.value().value() + "> <https://w3id.org/arknet/ddd#partOf> ?subdomainNode } }";
        String originalSubdomainNode;
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            originalSubdomainNode = handle.sparqlQuery().select(subdomainNodeQuery)
                    .map(row -> ((IRI) row.getValue("subdomainNode").orElseThrow()).getIRIString())
                    .findFirst().orElseThrow();
        }

        BoundedContext changed = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.SUPPORTING_DOMAIN, null, List.of());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(changed.code()), changed);

        String ask = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + originalSubdomainNode + "> ?p ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask(ask),
                    "the superseded subdomain node must not survive the update as orphaned garbage");
        }
    }

    // ---- row multiplication (issue #158): ownedBy/subdomain carry no enforceable sh:maxCount ----

    /**
     * {@code arkddd:ownedBy} carries no {@code sh:maxCount} at all (only {@code sh:Warning}), so a
     * store-first bounded context can legally carry two of them for the same subject.
     * {@link KognioRdfBoundedContextRepository#findByCode} used to run a single query joining
     * {@code subdomain} and {@code ownedBy} as two independent {@code OPTIONAL}s and take whatever
     * row {@code .findFirst()} happened to return - an unlogged, non-deterministic pick. This pins
     * the fix: every row is now consumed and reduced the same way {@code findAll} already did,
     * logging a {@code WARN} naming the field when more than one distinct value was collapsed.
     */
    @Test
    void findByCodeGroupsARowMultipliedOwnedByAndLogsAWarning() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"), null, "team-a", List.of());
        repository.create(PROJECT_A, bc);
        insertTriple(bc.id().value().value(), "https://w3id.org/arknet/ddd#ownedBy", "\"team-b\"");

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            BoundedContext found = repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1")).orElseThrow();

            assertTrue(List.of("team-a", "team-b").contains(found.ownedBy()),
                    "must return one of the two legally co-existing values, not throw or return null");
            assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("ownedBy")
                            && event.getFormattedMessage().contains("2 distinct values")),
                    "the collapsed second ownedBy value must be logged, exactly as findAll already does");
        } finally {
            detachLogAppender(logs);
        }
    }

    /**
     * Same defect, the other affected predicate: {@code arkddd:partOf}'s {@code sh:maxCount 1} is
     * {@code sh:Warning}-severity only, so a store-first bounded context can carry two subdomain
     * classifications. Exercised through {@code findCurrentByCode} (the path {@code bc_link_term}'s
     * read-modify-write actually uses, see {@link #compareAndUpdateWritesBackTheOwnedByValueThatWasActuallyRead}).
     */
    @Test
    void findCurrentByCodeGroupsARowMultipliedSubdomainAndLogsAWarning() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"), Subdomain.CORE_DOMAIN, null, List.of());
        repository.create(PROJECT_A, bc);
        String secondSubdomainNode = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        insertTriple(secondSubdomainNode, "https://w3id.org/1999/02/22-rdf-syntax-ns#type",
                "<https://w3id.org/arknet/ddd#Subdomain>");
        insertTriple(secondSubdomainNode, "https://w3id.org/arknet/ddd#subdomainType",
                "<https://w3id.org/arknet/ddd#SupportingDomain>");
        insertTriple(bc.id().value().value(), "https://w3id.org/arknet/ddd#partOf", "<" + secondSubdomainNode + ">");

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            BoundedContextRepository.CurrentBoundedContext found =
                    repository.findCurrentByCode(PROJECT_A, new BoundedContextCode("BC-1")).orElseThrow();

            assertTrue(List.of(Subdomain.CORE_DOMAIN, Subdomain.SUPPORTING_DOMAIN)
                            .contains(found.value().subdomain()),
                    "must return one of the two legally co-existing values, not throw or return null");
            assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("subdomain")
                            && event.getFormattedMessage().contains("2 distinct values")),
                    "the collapsed second subdomain value must be logged, exactly as findAll already does");
        } finally {
            detachLogAppender(logs);
        }
    }

    /**
     * The write-side consequence the issue is actually about: {@code bc_link_term}'s
     * read-modify-write reads via {@code findCurrentByCode}, then {@code compareAndUpdate} replaces
     * every triple of the subject with a graph built from exactly that read state (see
     * {@code replaceExistingTriples}). Whichever {@code ownedBy} value the read happened to surface
     * must be the one written back - not a different row re-picked at write time - since the domain
     * object cannot carry both values.
     */
    @Test
    void compareAndUpdateWritesBackTheOwnedByValueThatWasActuallyRead() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, "team-a", List.of());
        repository.create(PROJECT_A, original);
        insertTriple(id.value().value(), "https://w3id.org/arknet/ddd#ownedBy", "\"team-b\"");

        BoundedContextRepository.CurrentBoundedContext current =
                repository.findCurrentByCode(PROJECT_A, new BoundedContextCode("BC-1")).orElseThrow();
        String observedOwnedBy = current.value().ownedBy();

        BoundedContext renamed = new BoundedContext(id, new BoundedContextCode("BC-1"), "Renamed",
                current.value().domainVision(), current.value().subdomain(), observedOwnedBy,
                current.value().usesTerms());
        repository.compareAndUpdate(PROJECT_A, current.head(), renamed);

        assertEquals(observedOwnedBy,
                repository.findByCode(PROJECT_A, new BoundedContextCode("BC-1")).orElseThrow().ownedBy(),
                "the value actually read must be the one surviving the write, not a different row picked "
                        + "independently at write time");
    }

    /** Inserts one raw triple directly into the bounded-context named graph, bypassing the domain. */
    private void insertTriple(String subjectIri, String predicateIri, String objectTerm) {
        String insert = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + subjectIri + "> <"
                + predicateIri + "> " + objectTerm + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Attaches a fresh {@link ListAppender} to {@code KognioRdfBoundedContextRepository}'s logger so
     * a test can assert a specific {@code WARN} was actually logged, not merely that the picked
     * value happens to be valid - the module carries no SLF4J binding otherwise, so without this the
     * production {@code LOG.warn} calls are silent NOP-logger no-ops even when reached.
     */
    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KognioRdfBoundedContextRepository.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KognioRdfBoundedContextRepository.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    // ---- revision trail: one revision per write, head queryable ----------------

    /**
     * Revision basis, exercised against a real store: every write through the funnel
     * records exactly one immutable revision, the head is queryable per resource and moves
     * with every update, and the new head chains to the superseded one.
     */
    @Test
    void everyWriteRecordsExactlyOneRevisionAndMovesTheQueryableHead() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());
        repository.create(PROJECT_A, bc);
        String subject = bc.id().value().value();

        List<String> afterCreate = revisionsOf(subject);
        assertEquals(1, afterCreate.size(), "create must record exactly one revision");
        assertEquals(afterCreate, headsOf(subject), "the head must point at the sole revision");

        repository.compareAndUpdate(PROJECT_A, new RevisionToken(afterCreate.get(0)), new BoundedContext(bc.id(),
                bc.code(), "Renamed", bc.domainVision(), bc.subdomain(), bc.ownedBy(), bc.usesTerms()));

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
        repository.create(PROJECT_A, boundedContext(new BoundedContextCode("BC-1"), null, null, List.of()));

        assertThrows(DuplicateBoundedContextCodeException.class, () -> repository.create(PROJECT_A,
                boundedContext(new BoundedContextCode("BC-1"), null, null, List.of())));

        String all = "SELECT ?r WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?r a <" + ArkprovVocabulary.REVISION_TYPE + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertEquals(1, handle.sparqlQuery().select(all).count(),
                    "the rejected write must not have recorded a revision");
        }
    }

    // ---- compare-and-set ------------------------------------------------------------------

    /**
     * The read side of the guard: {@code findCurrentByCode} hands out the very {@code arkprov:head}
     * the last funnel write recorded, so a caller's {@code compareAndUpdate} can be checked against
     * it - and the state it pairs the head with is the same one {@code findByCode} reads.
     */
    @Test
    void findCurrentByCodeReturnsTheStateTogetherWithTheCurrentHead() {
        BoundedContext bc = boundedContext(new BoundedContextCode("BC-1"),
                Subdomain.CORE_DOMAIN, "orders-team", List.of());
        repository.create(PROJECT_A, bc);

        BoundedContextRepository.CurrentBoundedContext current =
                repository.findCurrentByCode(PROJECT_A, new BoundedContextCode("BC-1")).orElseThrow();

        assertEquals(bc, current.value());
        assertEquals(headsOf(bc.id().value().value()), List.of(current.head().value()));
    }

    @Test
    void findCurrentByCodeReturnsEmptyForAnUnknownCode() {
        assertEquals(Optional.empty(), repository.findCurrentByCode(PROJECT_A, new BoundedContextCode("BC-9")));
    }

    /**
     * The write side of the guard: a caller whose observed head is no longer current -
     * because another writer committed in between - is rejected instead of overwriting the change
     * it never saw, and its rejected write leaves neither a triple nor a revision behind.
     */
    @Test
    void compareAndUpdateRejectsAStaleHeadAndWritesNothing() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(PROJECT_A, original);
        RevisionToken staleHead = currentHeadOf(original.code());

        // A concurrent writer commits first, moving the head away from what the loser observed.
        BoundedContext byTheWinner = new BoundedContext(id, original.code(), "Renamed by the winner",
                original.domainVision(), original.subdomain(), original.ownedBy(), List.of());
        repository.compareAndUpdate(PROJECT_A, staleHead, byTheWinner);

        BoundedContext byTheLoser = new BoundedContext(id, original.code(), "Renamed by the loser",
                original.domainVision(), original.subdomain(), original.ownedBy(), List.of());
        assertThrows(BoundedContextConcurrentlyModifiedException.class,
                () -> repository.compareAndUpdate(PROJECT_A, staleHead, byTheLoser));

        assertEquals("Renamed by the winner",
                repository.findByCode(PROJECT_A, original.code()).orElseThrow().name());
        assertEquals(2, revisionsOf(id.value().value()).size(),
                "the rejected write must not have recorded a revision");
    }

    /**
     * A bounded context written before the funnel recorded revisions carries no head at all. Its
     * {@code null} head is a legitimate expectation, not a missing one - so a caller that observed
     * "no head yet" may still write, and the write records the first revision.
     */
    @Test
    void compareAndUpdateAcceptsANullHeadWhenTheResourceHasNoneYet() {
        BoundedContextId id = freshId();
        BoundedContext original = new BoundedContext(id, new BoundedContextCode("BC-1"), "OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.", null, null, List.of());
        repository.create(PROJECT_A, original);
        // Strips the head the create recorded, leaving the state from before the revision trail existed.
        String dropHead = "DELETE WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + id.value().value() + "> <" + ArkprovVocabulary.HEAD + "> ?head } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(dropHead);
                return null;
            });
        }
        assertNull(currentHeadOf(original.code()), "precondition: the bounded context carries no head");

        BoundedContext changed = new BoundedContext(id, original.code(), "Renamed",
                original.domainVision(), original.subdomain(), original.ownedBy(), List.of());
        repository.compareAndUpdate(PROJECT_A, null, changed);

        assertEquals("Renamed", repository.findByCode(PROJECT_A, original.code()).orElseThrow().name());
        assertEquals(1, headsOf(id.value().value()).size(), "the write must have recorded a head again");
    }

    // ---- batch identity resolution (backs the ResolveBoundedContexts in-port) ---------------

    /**
     * The batch a sibling hexagon's in-adapter borrows through {@code ResolveBoundedContexts}: one
     * {@code VALUES}-bound query, never rejecting - an identity the project does not
     * hold is simply absent from the result.
     */
    @Test
    void findByIdsResolvesOnlyTheIdentitiesTheProjectHolds() {
        BoundedContext first = boundedContext(new BoundedContextCode("BC-1"), null, null, List.of());
        BoundedContext second = boundedContext(new BoundedContextCode("BC-2"), null, null, List.of());
        repository.create(PROJECT_A, first);
        repository.create(PROJECT_A, second);
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID());

        List<ResolveBoundedContexts.ResolvedBoundedContext> resolved = repository.findByIds(
                PROJECT_A, List.of(first.id().value(), second.id().value(), unknown));

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveBoundedContexts.ResolvedBoundedContext(
                first.id().value(), first.code())));
        assertTrue(resolved.contains(new ResolveBoundedContexts.ResolvedBoundedContext(
                second.id().value(), second.code())));
    }

    @Test
    void findByIdsOfAnEmptyListQueriesNothing() {
        assertEquals(List.of(), repository.findByIds(PROJECT_A, List.of()));
    }

    /**
     * A store-first bounded context without {@code arknet:name}/{@code arkddd:domainVision}
     * is invisible to {@code findByCode}/{@code findAll} - but it still carries an identity and a
     * code, and the display resolution must therefore still find it. That is why this join covers
     * {@code dcterms:identifier} alone.
     */
    @Test
    void findByIdsResolvesABoundedContextThatCarriesNothingButTypeAndIdentifier() {
        String subject = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String insert = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + subject + "> a <"
                + BOUNDED_CONTEXT_TYPE + "> ; <http://purl.org/dc/terms/identifier> \"BC-7\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }

        List<ResolveBoundedContexts.ResolvedBoundedContext> resolved =
                repository.findByIds(PROJECT_A, List.of(ResourceId.of(subject)));

        assertEquals(List.of(new ResolveBoundedContexts.ResolvedBoundedContext(
                ResourceId.of(subject), new BoundedContextCode("BC-7"))), resolved);
        assertTrue(repository.findByCode(PROJECT_A, new BoundedContextCode("BC-7")).isEmpty(),
                "precondition: the single-context read path cannot surface it at all");
    }

    /** The head a caller would observe right now - what a well-behaved compare-and-set passes. */
    private RevisionToken currentHeadOf(BoundedContextCode code) {
        return repository.findCurrentByCode(PROJECT_A, code).orElseThrow().head();
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
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .toList();
        }
    }
}
