// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.adr.application.port.in.AddAdr.NewAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Policy tests for {@link AdrService}: identity minting, code assignment, listing, lookup, the
 * accept transition and both directions of the supersedes relation, exercised against an in-memory
 * fake repository, two fake lookups and a deterministic fake {@link ResourceIdFactory}.
 */
class AdrServiceTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final ResourceId FR_1 = ResourceId.of("https://w3id.org/arknet/id/fr-1");
    private static final ResourceId NFR_2 = ResourceId.of("https://w3id.org/arknet/id/nfr-2");
    private static final ResourceId BC_1 = ResourceId.of("https://w3id.org/arknet/id/bc-1");

    private InMemoryAdrRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private InMemoryReferenceLookups.Requirements requirements;
    private InMemoryReferenceLookups.BoundedContexts contexts;
    private AdrService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAdrRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        requirements = new InMemoryReferenceLookups.Requirements();
        requirements.register("FR-1", FR_1);
        requirements.register("NFR-2", NFR_2);
        contexts = new InMemoryReferenceLookups.BoundedContexts();
        contexts.register("BC-1", BC_1);
        service = new AdrService(repository, resourceIdFactory, requirements, contexts);
    }

    @Test
    void addAssignsFirstBusinessCodeAndStartsProposed() {
        AdrDetail added = service.add(PROJECT, newAdr());

        assertEquals(new AdrCode("ADR-1"), added.adr().code());
        assertEquals(AdrStatus.PROPOSED, added.adr().status());
        assertEquals("Use an embedded triple store", added.adr().name());
        assertEquals(List.of(), added.adr().supersedes());
        assertEquals(added.adr(), repository.findByCode(PROJECT, added.adr().code()).orElseThrow());
    }

    @Test
    void addAcceptsOptionalTextFieldsAsNull() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, null, null));

        assertNull(added.adr().consequences());
        assertNull(added.adr().alternatives());
        assertNull(added.adr().decisionDate());
        assertEquals(List.of(), added.adr().addressesRequirements());
        assertEquals(List.of(), added.adr().affectsContexts());
    }

    @Test
    void addResolvesCrossContextReferencesToOpaqueIdentities() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, LocalDate.of(2026, 7, 31),
                List.of("FR-1", "NFR-2"), List.of("BC-1")));

        assertEquals(List.of(new RequirementRef(FR_1), new RequirementRef(NFR_2)),
                added.adr().addressesRequirements());
        assertEquals(List.of(new BoundedContextRef(BC_1)), added.adr().affectsContexts());
        assertEquals(LocalDate.of(2026, 7, 31), added.adr().decisionDate());
    }

    /**
     * Resolution happens in the service and must abort the whole call: a decision half-linked to a
     * requirement that does not exist is worse than no decision at all.
     */
    @Test
    void addPropagatesTheLookupFailureForAnUnknownReferenceAndWritesNothing() {
        assertThrows(NoSuchElementException.class, () -> service.add(PROJECT,
                new NewAdr("Title", "Some context here", "Some decision here", null, null, null,
                        List.of("FR-99"), null)));

        assertTrue(service.list(PROJECT).isEmpty());
    }

    @Test
    void addDeduplicatesRepeatedReferenceCodes() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, List.of("FR-1", "FR-1"), null));

        assertEquals(List.of(new RequirementRef(FR_1)), added.adr().addressesRequirements());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        AdrDetail first = service.add(PROJECT, newAdr());
        AdrDetail second = service.add(PROJECT, newAdr());

        assertNotEquals(first.adr().id(), second.adr().id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addNumbersRunSequentially() {
        assertEquals(new AdrCode("ADR-1"), service.add(PROJECT, newAdr()).adr().code());
        assertEquals(new AdrCode("ADR-2"), service.add(PROJECT, newAdr()).adr().code());
        assertEquals(new AdrCode("ADR-3"), service.add(PROJECT, newAdr()).adr().code());
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(PROJECT, newAdr());

        AdrDetail inOther = service.add(other, newAdr());

        assertEquals(new AdrCode("ADR-1"), inOther.adr().code());
        assertEquals(1, service.list(PROJECT).size());
        assertEquals(1, service.list(other).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(PROJECT, new NewAdr("A", "Context of A here", "Decision A", null, null, null, null, null));
        service.add(PROJECT, new NewAdr("B", "Context of B here", "Decision B", null, null, null, null, null));

        List<AdrDetail> all = service.list(PROJECT);

        assertEquals(2, all.size());
        assertEquals("A", all.get(0).adr().name());
        assertEquals("B", all.get(1).adr().name());
    }

    @Test
    void getReturnsPersistedAdr() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();

        assertTrue(service.get(PROJECT, code).isPresent());
        assertEquals("Use an embedded triple store", service.get(PROJECT, code).orElseThrow().adr().name());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(PROJECT, new AdrCode("ADR-99")).isPresent());
    }

    @Test
    void acceptTransitionsFromProposedToAccepted() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();

        AdrDetail accepted = service.accept(PROJECT, code);

        assertEquals(AdrStatus.ACCEPTED, accepted.adr().status());
        assertEquals(AdrStatus.ACCEPTED, service.get(PROJECT, code).orElseThrow().adr().status());
    }

    @Test
    void acceptingAnAlreadyAcceptedAdrIsANoOp() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        service.accept(PROJECT, code);

        AdrDetail again = service.accept(PROJECT, code);

        assertEquals(AdrStatus.ACCEPTED, again.adr().status());
    }

    @Test
    void acceptThrowsWhenAdrUnknown() {
        AdrNotFoundException ex = assertThrows(AdrNotFoundException.class,
                () -> service.accept(PROJECT, new AdrCode("ADR-42")));

        assertSame(PROJECT, ex.projectId());
        assertEquals(new AdrCode("ADR-42"), ex.adrCode());
    }

    @Test
    void rejectTransitionsFromProposedToRejected() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();

        AdrDetail rejected = service.reject(PROJECT, code);

        assertEquals(AdrStatus.REJECTED, rejected.adr().status());
        assertEquals(AdrStatus.REJECTED, service.get(PROJECT, code).orElseThrow().adr().status());
    }

    @Test
    void rejectingAnAlreadyRejectedAdrIsANoOp() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        service.reject(PROJECT, code);

        AdrDetail again = service.reject(PROJECT, code);

        assertEquals(AdrStatus.REJECTED, again.adr().status());
    }

    @Test
    void rejectThrowsWhenAdrUnknown() {
        AdrNotFoundException ex = assertThrows(AdrNotFoundException.class,
                () -> service.reject(PROJECT, new AdrCode("ADR-42")));

        assertSame(PROJECT, ex.projectId());
        assertEquals(new AdrCode("ADR-42"), ex.adrCode());
    }

    @Test
    void deprecateTransitionsFromAcceptedToDeprecated() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        service.accept(PROJECT, code);

        AdrDetail deprecated = service.deprecate(PROJECT, code);

        assertEquals(AdrStatus.DEPRECATED, deprecated.adr().status());
        assertEquals(AdrStatus.DEPRECATED, service.get(PROJECT, code).orElseThrow().adr().status());
    }

    @Test
    void deprecatingAnAlreadyDeprecatedAdrIsANoOp() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        service.accept(PROJECT, code);
        service.deprecate(PROJECT, code);

        AdrDetail again = service.deprecate(PROJECT, code);

        assertEquals(AdrStatus.DEPRECATED, again.adr().status());
    }

    @Test
    void deprecateThrowsWhenAdrUnknown() {
        AdrNotFoundException ex = assertThrows(AdrNotFoundException.class,
                () -> service.deprecate(PROJECT, new AdrCode("ADR-42")));

        assertSame(PROJECT, ex.projectId());
        assertEquals(new AdrCode("ADR-42"), ex.adrCode());
    }

    @Test
    void supersedeRecordsTheForwardEdgeAndReportsBothDirections() {
        AdrCode older = service.add(PROJECT, newAdr()).adr().code();
        AdrCode newer = service.add(PROJECT, newAdr()).adr().code();

        AdrDetail superseding = service.supersede(PROJECT, newer, older);

        assertEquals(List.of(older), superseding.supersedes());
        assertEquals(List.of(), superseding.supersededBy());
        AdrDetail superseded = service.get(PROJECT, older).orElseThrow();
        assertEquals(List.of(), superseded.supersedes());
        assertEquals(List.of(newer), superseded.supersededBy());
    }

    /**
     * The backward direction is derived, never stored: nothing writes an
     * {@code arkarch:supersededBy} triple, so the superseded decision's own aggregate must stay
     * untouched by the operation.
     */
    @Test
    void supersedeLeavesTheSupersededAdrsOwnStateUntouched() {
        AdrCode older = service.add(PROJECT, newAdr()).adr().code();
        AdrCode newer = service.add(PROJECT, newAdr()).adr().code();
        Adr before = repository.findByCode(PROJECT, older).orElseThrow();

        service.supersede(PROJECT, newer, older);

        assertEquals(before, repository.findByCode(PROJECT, older).orElseThrow());
    }

    @Test
    void supersedeIsIdempotent() {
        AdrCode older = service.add(PROJECT, newAdr()).adr().code();
        AdrCode newer = service.add(PROJECT, newAdr()).adr().code();
        service.supersede(PROJECT, newer, older);

        AdrDetail again = service.supersede(PROJECT, newer, older);

        assertEquals(List.of(older), again.supersedes());
    }

    @Test
    void supersedeAccumulatesSeveralOlderDecisions() {
        AdrCode first = service.add(PROJECT, newAdr()).adr().code();
        AdrCode second = service.add(PROJECT, newAdr()).adr().code();
        AdrCode newest = service.add(PROJECT, newAdr()).adr().code();

        service.supersede(PROJECT, newest, first);
        AdrDetail superseding = service.supersede(PROJECT, newest, second);

        assertEquals(List.of(first, second), superseding.supersedes());
    }

    @Test
    void supersedeRejectsAnUnknownSupersededCodeAndWritesNothing() {
        AdrCode newer = service.add(PROJECT, newAdr()).adr().code();

        assertThrows(AdrNotFoundException.class,
                () -> service.supersede(PROJECT, newer, new AdrCode("ADR-99")));

        assertEquals(List.of(), service.get(PROJECT, newer).orElseThrow().supersedes());
    }

    @Test
    void supersedeRejectsSelfReference() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();

        assertThrows(IllegalArgumentException.class, () -> service.supersede(PROJECT, code, code));
    }

    /**
     * Regression guard for the replace-by-identity write path: the out-adapter persists a decision by
     * wiping and re-writing its triples, so an accept after a supersede - and vice versa - must carry
     * the earlier change along rather than silently dropping it.
     */
    @Test
    void acceptPreservesTheSupersedesEdgeAndTheCrossContextReferences() {
        AdrCode older = service.add(PROJECT, newAdr()).adr().code();
        AdrDetail newer = service.add(PROJECT, new NewAdr("Newer", "Context of the newer decision",
                "Decision of the newer one", "Some consequences", "Some options",
                LocalDate.of(2026, 7, 31), List.of("FR-1"), List.of("BC-1")));
        service.supersede(PROJECT, newer.adr().code(), older);

        AdrDetail accepted = service.accept(PROJECT, newer.adr().code());

        assertEquals(AdrStatus.ACCEPTED, accepted.adr().status());
        assertEquals(List.of(older), accepted.supersedes());
        assertEquals(List.of(new RequirementRef(FR_1)), accepted.adr().addressesRequirements());
        assertEquals(List.of(new BoundedContextRef(BC_1)), accepted.adr().affectsContexts());
        assertEquals("Some consequences", accepted.adr().consequences());
        assertEquals(LocalDate.of(2026, 7, 31), accepted.adr().decisionDate());
    }

    /** {@code adr_list} derives both supersedes directions from its single full read. */
    @Test
    void listReportsBothSupersedesDirectionsWithoutAnyExtraRead() {
        AdrCode older = service.add(PROJECT, newAdr()).adr().code();
        AdrCode newer = service.add(PROJECT, newAdr()).adr().code();
        service.supersede(PROJECT, newer, older);

        List<AdrDetail> all = service.list(PROJECT);

        AdrDetail olderDetail = all.stream().filter(d -> d.adr().code().equals(older)).findFirst().orElseThrow();
        AdrDetail newerDetail = all.stream().filter(d -> d.adr().code().equals(newer)).findFirst().orElseThrow();
        assertEquals(List.of(newer), olderDetail.supersededBy());
        assertEquals(List.of(older), newerDetail.supersedes());
    }

    /**
     * {@code supersededBy} must sort by parsed running number, not by {@link String}'s natural
     * (lexicographic) order - which would put {@code ADR-10}/{@code ADR-11} before {@code ADR-2}
     * through {@code ADR-9} once a project passes ten decisions.
     */
    @Test
    void listSortsSupersededByRunningNumberNotLexicographically() {
        AdrCode target = service.add(PROJECT, newAdr()).adr().code();
        for (int i = 0; i < 10; i++) {
            AdrCode superseding = service.add(PROJECT, newAdr()).adr().code();
            service.supersede(PROJECT, superseding, target);
        }

        AdrDetail targetDetail = service.list(PROJECT).stream()
                .filter(d -> d.adr().code().equals(target)).findFirst().orElseThrow();

        assertEquals(
                List.of(new AdrCode("ADR-2"), new AdrCode("ADR-3"), new AdrCode("ADR-4"),
                        new AdrCode("ADR-5"), new AdrCode("ADR-6"), new AdrCode("ADR-7"),
                        new AdrCode("ADR-8"), new AdrCode("ADR-9"), new AdrCode("ADR-10"),
                        new AdrCode("ADR-11")),
                targetDetail.supersededBy());
    }

    private static NewAdr newAdr() {
        return new NewAdr("Use an embedded triple store",
                "The model has to live somewhere a single-user client can reach without a server.",
                "Use kognio-rdf as the embedded RDF substrate behind an out-port.",
                null, null, null, null, null);
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
