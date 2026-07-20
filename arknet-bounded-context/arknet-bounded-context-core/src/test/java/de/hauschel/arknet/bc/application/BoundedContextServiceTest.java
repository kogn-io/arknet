// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Policy tests for {@link BoundedContextService}: identity minting, code assignment, listing,
 * lookup and term-linking rules, exercised against an in-memory fake repository and a
 * deterministic fake {@link ResourceIdFactory}.
 */
class BoundedContextServiceTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;
    private static final ResourceId TERM_1 =
            ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final ResourceId TERM_2 =
            ResourceId.of("https://w3id.org/arknet/id/term-2");

    private InMemoryBoundedContextRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private InMemoryTermLookup termLookup;
    private BoundedContextService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBoundedContextRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        termLookup = new InMemoryTermLookup();
        termLookup.register("TERM-1", TERM_1);
        termLookup.register("TERM-2", TERM_2);
        service = new BoundedContextService(repository, resourceIdFactory, termLookup);
    }

    @Test
    void addAssignsFirstBusinessCode() {
        BoundedContext added = service.add(WS, new NewBoundedContext("OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, "orders-team"));

        assertEquals(new BoundedContextCode("BC-1"), added.code());
        assertEquals("OrderManagement", added.name());
        assertEquals("Owns the lifecycle of a customer order from placement to fulfilment.",
                added.domainVision());
        assertEquals(Subdomain.CORE_DOMAIN, added.subdomain());
        assertEquals("orders-team", added.ownedBy());
        assertEquals(List.of(), added.usesTerms());
        assertEquals(added, repository.findByCode(WS, added.code()).orElseThrow());
    }

    @Test
    void addAcceptsOptionalSubdomainAndOwnedByAsNull() {
        BoundedContext added = service.add(WS, new NewBoundedContext("Shipping",
                "Coordinates the physical delivery of fulfilled orders to customers.", null, null));

        assertEquals(new BoundedContextCode("BC-1"), added.code());
        assertEquals(null, added.subdomain());
        assertEquals(null, added.ownedBy());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        BoundedContext first = service.add(WS, newBoundedContext());
        BoundedContext second = service.add(WS, newBoundedContext());

        assertNotEquals(first.id(), second.id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addNumbersRunSequentially() {
        BoundedContextCode bc1 = service.add(WS, newBoundedContext()).code();
        BoundedContextCode bc2 = service.add(WS, newBoundedContext()).code();
        BoundedContextCode bc3 = service.add(WS, newBoundedContext()).code();

        assertEquals(new BoundedContextCode("BC-1"), bc1);
        assertEquals(new BoundedContextCode("BC-2"), bc2);
        assertEquals(new BoundedContextCode("BC-3"), bc3);
    }

    @Test
    void addIsScopedPerWorkspace() {
        WorkspaceId other = new WorkspaceId("other");
        service.add(WS, newBoundedContext());

        BoundedContext inOther = service.add(other, newBoundedContext());

        assertEquals(new BoundedContextCode("BC-1"), inOther.code());
        assertEquals(1, service.list(WS).size());
        assertEquals(1, service.list(other).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewBoundedContext("A", "The first context does something useful here.",
                null, null));
        service.add(WS, new NewBoundedContext("B", "The second context does something else useful.",
                null, null));

        List<BoundedContext> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals("A", all.get(0).name());
        assertEquals("B", all.get(1).name());
    }

    @Test
    void getReturnsPersistedBoundedContext() {
        BoundedContextCode code = service.add(WS, newBoundedContext()).code();

        assertTrue(service.get(WS, code).isPresent());
        assertEquals("OrderManagement", service.get(WS, code).orElseThrow().name());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new BoundedContextCode("BC-99")).isPresent());
    }

    @Test
    void linkTermAddsTheTermToTheBoundedContext() {
        BoundedContextCode code = service.add(WS, newBoundedContext()).code();

        BoundedContext linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1)), linked.usesTerms());
        assertEquals(List.of(new TermRef(TERM_1)), service.get(WS, code).orElseThrow().usesTerms());
    }

    @Test
    void linkTermAppendsToAlreadyLinkedTerms() {
        BoundedContextCode code = service.add(WS, newBoundedContext()).code();
        service.linkTerm(WS, code, "TERM-1");

        BoundedContext linked = service.linkTerm(WS, code, "TERM-2");

        assertEquals(List.of(new TermRef(TERM_1), new TermRef(TERM_2)), linked.usesTerms());
    }

    @Test
    void linkingTheSameTermTwiceIsANoOp() {
        BoundedContextCode code = service.add(WS, newBoundedContext()).code();
        service.linkTerm(WS, code, "TERM-1");

        BoundedContext linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1)), linked.usesTerms());
    }

    @Test
    void linkTermThrowsWhenBoundedContextUnknown() {
        BoundedContextNotFoundException ex = assertThrows(BoundedContextNotFoundException.class,
                () -> service.linkTerm(WS, new BoundedContextCode("BC-42"), "TERM-1"));

        assertSame(WS, ex.workspaceId());
        assertEquals(new BoundedContextCode("BC-42"), ex.boundedContextCode());
    }

    /**
     * Resolution of the human-typed term code happens in the service, via {@link
     * InMemoryTermLookup}: a lookup failure must propagate unchanged and leave the bounded
     * context untouched.
     */
    @Test
    void linkTermPropagatesTheLookupFailureForAnUnknownTermCodeAndLinksNothing() {
        BoundedContextCode code = service.add(WS, newBoundedContext()).code();

        assertThrows(NoSuchElementException.class, () -> service.linkTerm(WS, code, "TERM-99"));

        assertEquals(List.of(), service.get(WS, code).orElseThrow().usesTerms());
    }

    /**
     * Regression guard for the replace-by-identity write path: the out-adapter persists a bounded
     * context by wiping and re-writing its triples, so a later term link must carry the earlier
     * links along rather than silently dropping them. Also covers that the non-edge fields
     * (name/domainVision/subdomain/ownedBy) survive an {@code update()}.
     */
    @Test
    void linkTermPreservesEarlierLinksAndFieldsAcrossUpdate() {
        BoundedContext added = service.add(WS, new NewBoundedContext("OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.SUPPORTING_DOMAIN, "orders-team"));
        service.linkTerm(WS, added.code(), "TERM-1");

        BoundedContext linked = service.linkTerm(WS, added.code(), "TERM-2");

        assertEquals(List.of(new TermRef(TERM_1), new TermRef(TERM_2)), linked.usesTerms());
        assertEquals("OrderManagement", linked.name());
        assertEquals(Subdomain.SUPPORTING_DOMAIN, linked.subdomain());
        assertEquals("orders-team", linked.ownedBy());
        BoundedContext reread = service.get(WS, added.code()).orElseThrow();
        assertEquals(List.of(new TermRef(TERM_1), new TermRef(TERM_2)), reread.usesTerms());
    }

    private static NewBoundedContext newBoundedContext() {
        return new NewBoundedContext("OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, "orders-team");
    }

    /** Deterministic fake minting sequential opaque ids, so tests never depend on randomness. */
    private static final class FakeResourceIdFactory implements ResourceIdFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public ResourceId newId() {
            return ResourceId.of("https://w3id.org/arknet/id/fake-" + counter.incrementAndGet());
        }

        int mintedCount() {
            return counter.get();
        }
    }
}
