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
import de.hauschel.arknet.adr.application.port.in.UpdateAdr.AdrCorrection;
import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotDeletableException;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrReferencedException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.AdrTextImmutableException;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Policy tests for {@link AdrService}: identity minting, code assignment, listing, lookup, the
 * accept transition, field-wise correction, both directions of the supersedes relation and the
 * status- and reference-staged delete, exercised against an in-memory fake repository, two fake
 * lookups and a deterministic fake {@link ResourceIdFactory}.
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
        assertNull(added.adr().supersededBy());
        assertEquals(added.adr(), repository.findByCode(PROJECT, added.adr().code()).orElseThrow());
    }

    @Test
    void addAcceptsOptionalTextFieldsAsNull() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, null, null, null));

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
                List.of("FR-1", "NFR-2"), List.of("BC-1"), null));

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
                        List.of("FR-99"), null, null)));

        assertTrue(service.list(PROJECT).isEmpty());
    }

    @Test
    void addDeduplicatesRepeatedReferenceCodes() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, List.of("FR-1", "FR-1"), null, null));

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

    /**
     * Mutation-tests {@code nextCode}'s reliance on {@link AdrRepository#findAllCodes} rather than
     * {@link AdrRepository#findAll} (kogn-io/arknet#359): revert {@code nextCode} back to deriving
     * its maximum from {@code findAll} and this goes red - the seeded {@code ADR-2} holds the
     * project's highest number but is invisible to {@code findAll}, exactly as a real store-first
     * status/{@code supersededBy} disagreement would make it, so {@code add} would recompute
     * {@code ADR-2} again instead of {@code ADR-3} and collide with a code that is still very much
     * assigned.
     */
    @Test
    void addSkipsOverACodeThatIsAssignedButNotCurrentlyMaterialisable() {
        service.add(PROJECT, newAdr());
        repository.seedUnmaterialisableCode(PROJECT, new AdrCode("ADR-2"));

        AdrDetail third = service.add(PROJECT, newAdr());

        assertEquals(new AdrCode("ADR-3"), third.adr().code());
    }

    @Test
    void skippedCountIsZeroWhenNothingWasSkipped() {
        service.add(PROJECT, newAdr());

        assertEquals(0, service.skippedCount(PROJECT));
    }

    @Test
    void skippedCountReportsEveryCodeFindAllCouldNotMaterialise() {
        service.add(PROJECT, newAdr());
        repository.seedUnmaterialisableCode(PROJECT, new AdrCode("ADR-2"));
        repository.seedUnmaterialisableCode(PROJECT, new AdrCode("ADR-3"));

        assertEquals(2, service.skippedCount(PROJECT));
        assertEquals(0, service.skippedCount(new ProjectId("other")));
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(PROJECT, new NewAdr("A", "Context of A here", "Decision A", null, null, null, null, null, null));
        service.add(PROJECT, new NewAdr("B", "Context of B here", "Decision B", null, null, null, null, null, null));

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

    /**
     * The write lands on the superseded decision (kogn-io/arknet#357): its status becomes
     * SUPERSEDED and its supersededBy edge names the successor, in one write - the superseding
     * decision's own record is left untouched by this call.
     */
    @Test
    void supersedeWritesTheSupersededDecisionsStatusAndEdge() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();

        AdrDetail superseded = service.supersede(PROJECT, newer, older);

        assertEquals(older, superseded.adr().code());
        assertEquals(AdrStatus.SUPERSEDED, superseded.adr().status());
        assertEquals(List.of(newer), superseded.supersededBy());
        assertEquals(List.of(), superseded.supersedes());
        AdrDetail superseding = service.get(PROJECT, newer).orElseThrow();
        assertEquals(AdrStatus.ACCEPTED, superseding.adr().status());
        assertEquals(List.of(older), superseding.supersedes());
        assertEquals(List.of(), superseding.supersededBy());
    }

    /**
     * The superseding decision's own aggregate is read (to check its status) but never written by
     * this call - only the superseded decision's compare-and-set path is exercised.
     */
    @Test
    void supersedeLeavesTheSupersedingAdrsOwnStateUntouched() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        Adr before = repository.findByCode(PROJECT, newer).orElseThrow();

        service.supersede(PROJECT, newer, older);

        assertEquals(before, repository.findByCode(PROJECT, newer).orElseThrow());
    }

    @Test
    void supersedeIsIdempotent() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        service.supersede(PROJECT, newer, older);

        AdrDetail again = service.supersede(PROJECT, newer, older);

        assertEquals(AdrStatus.SUPERSEDED, again.adr().status());
        assertEquals(List.of(newer), again.supersededBy());
    }

    /**
     * Idempotency must not depend on the superseding decision's current status (kogn-io/arknet#359):
     * recording the very same pair a second time is a no-op {@link AdrService#supersede} takes before
     * it ever re-reads and re-checks the superseding decision - so it stays a no-op even once that
     * decision has since moved on (here to DEPRECATED, reached the only way it legitimately can once
     * it has already superseded something). Mutation test: checking the superseding decision's status
     * before the idempotency short-circuit, instead of after, would turn this promised no-op into a
     * spurious {@link IllegalStateException}.
     */
    @Test
    void supersedeStaysIdempotentAfterTheSupersedingDecisionIsLaterDeprecated() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        service.supersede(PROJECT, newer, older);
        service.deprecate(PROJECT, newer);

        AdrDetail again = service.supersede(PROJECT, newer, older);

        assertEquals(AdrStatus.SUPERSEDED, again.adr().status());
        assertEquals(List.of(newer), again.supersededBy());
        assertEquals(AdrStatus.DEPRECATED, service.get(PROJECT, newer).orElseThrow().adr().status());
    }

    /** One successor may supersede several older decisions, each individually. */
    @Test
    void supersedeAccumulatesSeveralOlderDecisions() {
        AdrCode first = acceptedAdr();
        AdrCode second = acceptedAdr();
        AdrCode newest = acceptedAdr();

        service.supersede(PROJECT, newest, first);
        service.supersede(PROJECT, newest, second);

        assertEquals(List.of(first, second), service.get(PROJECT, newest).orElseThrow().supersedes());
    }

    @Test
    void supersedeRejectsAnUnknownSupersededCodeAndWritesNothing() {
        AdrCode newer = acceptedAdr();

        assertThrows(AdrNotFoundException.class,
                () -> service.supersede(PROJECT, newer, new AdrCode("ADR-99")));

        assertEquals(List.of(), service.get(PROJECT, newer).orElseThrow().supersedes());
    }

    @Test
    void supersedeRejectsAnUnknownSupersedingCodeAndWritesNothing() {
        AdrCode older = acceptedAdr();

        assertThrows(AdrNotFoundException.class,
                () -> service.supersede(PROJECT, new AdrCode("ADR-99"), older));

        assertEquals(AdrStatus.ACCEPTED, service.get(PROJECT, older).orElseThrow().adr().status());
    }

    /**
     * Only an ACCEPTED decision may supersede another - mutation test: removing this check would
     * let a PROPOSED (or REJECTED/DEPRECATED/SUPERSEDED) decision supersede an older one before it
     * was itself ever accepted.
     */
    @Test
    void supersedeRejectsWhenTheSupersedingDecisionIsNotAccepted() {
        AdrCode older = acceptedAdr();
        AdrCode proposedNewer = service.add(PROJECT, newAdr()).adr().code();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.supersede(PROJECT, proposedNewer, older));

        assertTrue(thrown.getMessage().contains(proposedNewer.value()), thrown.getMessage());
        assertEquals(AdrStatus.ACCEPTED, service.get(PROJECT, older).orElseThrow().adr().status());
    }

    /**
     * Only an ACCEPTED decision may be superseded - mutation test: removing this check (which lives
     * on {@code Adr#supersededBy}, exercised here through the service) would let a PROPOSED decision
     * be marked SUPERSEDED without ever having been ACCEPTED.
     */
    @Test
    void supersedeRejectsWhenTheSupersededDecisionIsNotAccepted() {
        AdrCode newer = acceptedAdr();
        AdrCode proposedOlder = service.add(PROJECT, newAdr()).adr().code();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.supersede(PROJECT, newer, proposedOlder));

        assertTrue(thrown.getMessage().contains(proposedOlder.value()), thrown.getMessage());
        assertEquals(AdrStatus.PROPOSED, service.get(PROJECT, proposedOlder).orElseThrow().adr().status());
    }

    /** A decision already superseded by one successor cannot be superseded again by a different one. */
    @Test
    void supersedeRejectsANewSuccessorForAnAlreadySupersededDecision() {
        AdrCode older = acceptedAdr();
        AdrCode firstSuccessor = acceptedAdr();
        AdrCode secondSuccessor = acceptedAdr();
        service.supersede(PROJECT, firstSuccessor, older);

        assertThrows(IllegalStateException.class, () -> service.supersede(PROJECT, secondSuccessor, older));

        assertEquals(List.of(firstSuccessor), service.get(PROJECT, older).orElseThrow().supersededBy());
    }

    /** Adds a decision and accepts it in one step - the precondition every supersede test needs. */
    private AdrCode acceptedAdr() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        service.accept(PROJECT, code);
        return code;
    }

    @Test
    void supersedeRejectsSelfReference() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();

        assertThrows(IllegalArgumentException.class, () -> service.supersede(PROJECT, code, code));
    }

    /**
     * Regression guard for the replace-by-identity write path: the out-adapter persists a decision by
     * wiping and re-writing its triples, so correcting the superseded decision's references
     * afterwards - allowed in every status - must carry its SUPERSEDED status and supersededBy edge
     * along rather than silently dropping either.
     */
    @Test
    void updatePreservesTheSupersededByEdgeAndStatusOfASupersededDecision() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        service.supersede(PROJECT, newer, older);

        Adr updated = service.update(PROJECT, older,
                AdrCorrection.builder().addressesRequirementCodes(List.of("FR-1")).build()).adr();

        assertEquals(AdrStatus.SUPERSEDED, updated.status());
        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
    }

    /** {@code adr_list} derives both supersedes directions from its single full read. */
    @Test
    void listReportsBothSupersedesDirectionsWithoutAnyExtraRead() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        service.supersede(PROJECT, newer, older);

        List<AdrDetail> all = service.list(PROJECT);

        AdrDetail olderDetail = all.stream().filter(d -> d.adr().code().equals(older)).findFirst().orElseThrow();
        AdrDetail newerDetail = all.stream().filter(d -> d.adr().code().equals(newer)).findFirst().orElseThrow();
        assertEquals(List.of(newer), olderDetail.supersededBy());
        assertEquals(List.of(older), newerDetail.supersedes());
    }

    /**
     * {@code adr_list} and {@code adr_get} must not disagree about the same edge (kogn-io/arknet#359):
     * {@code list()} inverts {@code supersededBy} in memory over its one {@code findAll} read, which
     * silently omits a successor the out-adapter's own read-time tolerance could not materialise
     * (kogn-io/arknet#357) - {@code detailOf} (backing {@code adr_get}) never has this problem, since
     * it resolves the very same identity through a dedicated {@code findCodesByIds} call instead.
     * Mutation test: dropping the fallback lookup in {@code list()} makes this decision's
     * {@code supersededBy} come back empty instead of naming {@code ADR-9}.
     */
    @Test
    void listFallsBackToACodeLookupWhenTheSuccessorWasSkippedByFindAll() {
        AdrId phantomSuccessorId = new AdrId(ResourceId.of("https://w3id.org/arknet/id/phantom-successor"));
        repository.seedUnmaterialisableCode(PROJECT, phantomSuccessorId, new AdrCode("ADR-9"));
        Adr superseded = new Adr(new AdrId(ResourceId.of("https://w3id.org/arknet/id/superseded-1")),
                new AdrCode("ADR-1"), "Title", AdrStatus.SUPERSEDED, "Some context here", "Some decision here",
                null, null, null, List.of(), List.of(), phantomSuccessorId, List.of());
        repository.create(PROJECT, superseded);

        List<AdrDetail> all = service.list(PROJECT);

        AdrDetail detail = all.stream()
                .filter(candidate -> candidate.adr().code().equals(new AdrCode("ADR-1")))
                .findFirst().orElseThrow();
        assertEquals(List.of(new AdrCode("ADR-9")), detail.supersededBy());
    }

    /**
     * {@code supersededBy} must sort by parsed running number, not by {@link String}'s natural
     * (lexicographic) order - which would put {@code ADR-10}/{@code ADR-11} before {@code ADR-2}
     * through {@code ADR-9} once a project passes ten decisions.
     */
    /**
     * Since kogn-io/arknet#357 a live decision has at most one successor ({@code supersededBy}
     * carries {@code sh:maxCount 1}), so more than one entry in {@code supersededBy} can only come
     * from the pre-#357 legacy shape - several decisions each still asserting their own
     * {@code arkarch:supersedes} at {@code target} (a state a store-first write, not
     * {@code adr_supersede}, could have left behind). {@link AdrRepository#findLegacySupersedesEdges}
     * is what folds those in; this test seeds ten such pairs directly, bypassing the service exactly
     * as store-first data would have arrived.
     */
    @Test
    void listSortsSupersededByRunningNumberNotLexicographically() {
        AdrCode target = service.add(PROJECT, newAdr()).adr().code();
        for (int i = 0; i < 10; i++) {
            AdrCode superseding = service.add(PROJECT, newAdr()).adr().code();
            repository.seedLegacySupersession(PROJECT, superseding, target);
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

    // --- relatedTo ------------------------------------------------------------

    @Test
    void addResolvesRelatedToCodesAgainstThisHexagonsOwnDecisions() {
        AdrDetail peer = service.add(PROJECT, newAdr());

        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, null, null, List.of(peer.adr().code().value())));

        assertEquals(List.of(peer.adr().id()), added.adr().relatedTo());
        assertEquals(List.of(peer.adr().code()), added.relatedTo());
    }

    @Test
    void updateSetsAndClearsRelatedTo() {
        AdrDetail peer = service.add(PROJECT, newAdr());
        AdrDetail added = service.add(PROJECT, newAdr());

        Adr linked = service.update(PROJECT, added.adr().code(), AdrCorrection.builder()
                .relatedToCodes(List.of(peer.adr().code().value())).build()).adr();
        assertEquals(List.of(peer.adr().id()), linked.relatedTo());

        Adr cleared = service.update(PROJECT, added.adr().code(),
                AdrCorrection.builder().relatedToCodes(List.of()).build()).adr();
        assertEquals(List.of(), cleared.relatedTo());
    }

    /** The tri-state again: an omitted list leaves the peers alone rather than wiping them. */
    @Test
    void updateLeavesRelatedToUntouchedWhenTheListIsNotGiven() {
        AdrDetail peer = service.add(PROJECT, newAdr());
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, null, null, List.of(peer.adr().code().value())));

        Adr updated = service.update(PROJECT, added.adr().code(),
                AdrCorrection.builder().name("Another title").build()).adr();

        assertEquals(List.of(peer.adr().id()), updated.relatedTo());
    }

    /**
     * Resolution sits before any write, exactly as for the two cross-context lists - an unknown peer
     * code aborts the whole call rather than leaving a half-linked decision behind.
     */
    @Test
    void addRejectsAnUnknownRelatedCodeBeforeWritingAnything() {
        assertThrows(AdrNotFoundException.class, () -> service.add(PROJECT,
                new NewAdr("Title", "Some context here", "Some decision here", null, null, null,
                        null, null, List.of("ADR-9"))));

        assertTrue(service.list(PROJECT).isEmpty());
    }

    @Test
    void updateRejectsAnUnknownRelatedCodeBeforeWritingAnything() {
        AdrDetail added = service.add(PROJECT, newAdr());

        assertThrows(AdrNotFoundException.class, () -> service.update(PROJECT, added.adr().code(),
                AdrCorrection.builder().name("Another title").relatedToCodes(List.of("ADR-9")).build()));

        assertEquals(added.adr(), repository.findByCode(PROJECT, added.adr().code()).orElseThrow());
    }

    /**
     * Same refusal, same wording as {@code adr_supersede}'s: a decision is not its own peer. The
     * service refuses it before it even looks the code up, so the message names the offending code -
     * the record's own constructor would otherwise catch it later, without being able to say which
     * code the caller typed.
     */
    @Test
    void updateRejectsRelatingADecisionToItselfBeforeWritingAnything() {
        AdrDetail added = service.add(PROJECT, newAdr());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.update(PROJECT, added.adr().code(), AdrCorrection.builder()
                        .name("Another title")
                        .relatedToCodes(List.of(added.adr().code().value())).build()));

        assertTrue(thrown.getMessage().contains(added.adr().code().value()), thrown.getMessage());
        assertEquals(added.adr(), repository.findByCode(PROJECT, added.adr().code()).orElseThrow());
    }

    /**
     * The relation is symmetric, so a reader of the peer must see the decision that named it even
     * though only the forward triple exists. One reverse read, not a traversal.
     */
    @Test
    void getMergesTheBackwardDirectionIntoTheOneRelatedToList() {
        AdrDetail peer = service.add(PROJECT, newAdr());
        AdrDetail naming = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, null, null, List.of(peer.adr().code().value())));

        assertEquals(List.of(peer.adr().code()),
                service.get(PROJECT, naming.adr().code()).orElseThrow().relatedTo());
        assertEquals(List.of(naming.adr().code()),
                service.get(PROJECT, peer.adr().code()).orElseThrow().relatedTo());
    }

    /** {@code list} inverts its one full read in memory and must land on the same answer. */
    @Test
    void listMergesRelatedToExactlyAsGetDoes() {
        AdrDetail peer = service.add(PROJECT, newAdr());
        AdrDetail naming = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, null, null, List.of(peer.adr().code().value())));

        List<AdrDetail> all = service.list(PROJECT);

        assertEquals(service.get(PROJECT, peer.adr().code()).orElseThrow().relatedTo(),
                detailOf(all, peer.adr().code()).relatedTo());
        assertEquals(service.get(PROJECT, naming.adr().code()).orElseThrow().relatedTo(),
                detailOf(all, naming.adr().code()).relatedTo());
    }

    /**
     * A mutually declared pair is legal - {@code relatedTo} permits cycles, unlike
     * {@code supersedes}. Merging must therefore terminate and report each peer once, not twice:
     * both reads are one step, never a traversal.
     */
    @Test
    void aMutualRelatedToPairTerminatesAndReportsEachPeerOnce() {
        AdrDetail first = service.add(PROJECT, newAdr());
        AdrDetail second = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, null, null, List.of(first.adr().code().value())));
        service.update(PROJECT, first.adr().code(), AdrCorrection.builder()
                .relatedToCodes(List.of(second.adr().code().value())).build());

        assertEquals(List.of(second.adr().code()),
                service.get(PROJECT, first.adr().code()).orElseThrow().relatedTo());
        assertEquals(List.of(first.adr().code()),
                service.get(PROJECT, second.adr().code()).orElseThrow().relatedTo());
        assertEquals(List.of(second.adr().code()),
                detailOf(service.list(PROJECT), first.adr().code()).relatedTo());
    }

    /** Ordering is by running number, the same rule {@code supersededBy} follows. */
    @Test
    void relatedToIsSortedByRunningNumberNotLexicographically() {
        AdrDetail target = service.add(PROJECT, newAdr());
        for (int i = 0; i < 10; i++) {
            service.add(PROJECT, new NewAdr("Title", "Some context here", "Some decision here",
                    null, null, null, null, null, List.of(target.adr().code().value())));
        }

        assertEquals(
                List.of(new AdrCode("ADR-2"), new AdrCode("ADR-3"), new AdrCode("ADR-4"),
                        new AdrCode("ADR-5"), new AdrCode("ADR-6"), new AdrCode("ADR-7"),
                        new AdrCode("ADR-8"), new AdrCode("ADR-9"), new AdrCode("ADR-10"),
                        new AdrCode("ADR-11")),
                service.get(PROJECT, target.adr().code()).orElseThrow().relatedTo());
    }

    private static AdrDetail detailOf(List<AdrDetail> all, AdrCode code) {
        return all.stream().filter(detail -> detail.adr().code().equals(code)).findFirst().orElseThrow();
    }

    /**
     * Regression for #189: {@code get} (unlike {@code list}) derives {@code supersededBy} through
     * {@link de.hauschel.arknet.adr.application.port.out.AdrRepository#findSupersedingCodes} rather
     * than {@code AdrService}'s own comparator, so it exercises the repository's own sort order. The
     * in-memory test fake must sort by parsed running number, not by {@link String}'s natural
     * (lexicographic) order, the same contract {@code KognioRdfAdrRepository} honours.
     */
    @Test
    void getSortsSupersededByRunningNumberNotLexicographically() {
        AdrCode target = service.add(PROJECT, newAdr()).adr().code();
        for (int i = 0; i < 10; i++) {
            AdrCode superseding = service.add(PROJECT, newAdr()).adr().code();
            repository.seedLegacySupersession(PROJECT, superseding, target);
        }

        AdrDetail targetDetail = service.get(PROJECT, target).orElseThrow();

        assertEquals(
                List.of(new AdrCode("ADR-2"), new AdrCode("ADR-3"), new AdrCode("ADR-4"),
                        new AdrCode("ADR-5"), new AdrCode("ADR-6"), new AdrCode("ADR-7"),
                        new AdrCode("ADR-8"), new AdrCode("ADR-9"), new AdrCode("ADR-10"),
                        new AdrCode("ADR-11")),
                targetDetail.supersededBy());
    }

    /**
     * Regression for #187: two distinct, non-standard store-first (ADR-005) codes that both parse to
     * the same running number (unparseable, hence 0 - see {@code AdrService#runningNumber}) must not
     * collide in the {@code supersededBy} {@link java.util.TreeSet}. A comparator ordering only by
     * parsed running number is inconsistent with {@link AdrCode#equals}, and a {@link java.util.TreeSet}
     * dedupes by comparator, not by {@code equals} - so without a tie-breaker one of the two entries
     * would be silently dropped.
     */
    @Test
    void listKeepsBothSupersededByEntriesWhenTheirRunningNumbersCollide() {
        AdrCode target = service.add(PROJECT, newAdr()).adr().code();
        repository.seedLegacySupersession(PROJECT, new AdrCode("ADR-1x"), target);
        repository.seedLegacySupersession(PROJECT, new AdrCode("ADR-2y"), target);

        AdrDetail targetDetail = service.list(PROJECT).stream()
                .filter(d -> d.adr().code().equals(target)).findFirst().orElseThrow();

        assertEquals(List.of(new AdrCode("ADR-1x"), new AdrCode("ADR-2y")), targetDetail.supersededBy());
    }

    @Test
    void updatePatchesOnlyTheNamedFieldAndLeavesEveryOtherAlone() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", "What follows", "What else", LocalDate.of(2026, 7, 31),
                List.of("FR-1"), List.of("BC-1"), null));

        Adr updated = service.update(PROJECT, added.adr().code(),
                AdrCorrection.builder().decision("A sharper decision").build()).adr();

        assertEquals("A sharper decision", updated.decision());
        assertEquals("Title", updated.name());
        assertEquals("Some context here", updated.context());
        assertEquals("What follows", updated.consequences());
        assertEquals("What else", updated.alternatives());
        assertEquals(LocalDate.of(2026, 7, 31), updated.decisionDate());
        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
        assertEquals(List.of(new BoundedContextRef(BC_1)), updated.affectsContexts());
        assertEquals(added.adr().id(), updated.id());
        assertEquals(added.adr().code(), updated.code());
    }

    /**
     * The tri-state of both reference lists: {@code null} is "leave this relation alone", not "clear
     * it" - conflating the two would silently wipe every edge of a decision whose caller only meant
     * to fix a typo.
     */
    @Test
    void updateLeavesBothReferenceRelationsUntouchedWhenNeitherListIsGiven() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, List.of("FR-1"), List.of("BC-1"), null));

        Adr updated = service.update(PROJECT, added.adr().code(),
                AdrCorrection.builder().name("Another title").build()).adr();

        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
        assertEquals(List.of(new BoundedContextRef(BC_1)), updated.affectsContexts());
    }

    /** The other half of the tri-state: an empty list is the explicit signal to remove every edge. */
    @Test
    void updateClearsAReferenceRelationWhenGivenAnEmptyList() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, List.of("FR-1"), List.of("BC-1"), null));

        Adr updated = service.update(PROJECT, added.adr().code(),
                AdrCorrection.builder().addressesRequirementCodes(List.of()).build()).adr();

        assertEquals(List.of(), updated.addressesRequirements());
        assertEquals(List.of(new BoundedContextRef(BC_1)), updated.affectsContexts());
    }

    @Test
    void updateReplacesAReferenceRelationWholesale() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, List.of("FR-1"), null, null));

        Adr updated = service.update(PROJECT, added.adr().code(),
                AdrCorrection.builder().addressesRequirementCodes(List.of("NFR-2")).build()).adr();

        assertEquals(List.of(new RequirementRef(NFR_2)), updated.addressesRequirements());
    }

    /**
     * Resolution sits outside the retry and before any write, exactly as in {@code add}: an unknown
     * code must abort the whole call and leave the decision exactly as it was.
     */
    @Test
    void updateRejectsAnUnknownReferenceCodeBeforeWritingAnything() {
        AdrDetail added = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, List.of("FR-1"), null, null));

        assertThrows(NoSuchElementException.class, () -> service.update(PROJECT, added.adr().code(),
                AdrCorrection.builder().name("Another title")
                        .addressesRequirementCodes(List.of("FR-99")).build()));
        assertThrows(NoSuchElementException.class, () -> service.update(PROJECT, added.adr().code(),
                AdrCorrection.builder().affectsContextCodes(List.of("BC-99")).build()));

        assertEquals(added.adr(), repository.findByCode(PROJECT, added.adr().code()).orElseThrow());
    }

    /**
     * The deliberate exception to the immutability rule (see {@link Adr#reviseReferences}): an edge
     * to a resource that did not exist when the decision was accepted is still completable
     * afterwards.
     */
    @Test
    void updateCorrectsTheReferencesOfAnAcceptedDecision() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        service.accept(PROJECT, code);

        Adr updated = service.update(PROJECT, code,
                AdrCorrection.builder().addressesRequirementCodes(List.of("FR-1")).build()).adr();

        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
        assertEquals(AdrStatus.ACCEPTED, updated.status());
    }

    /**
     * The rule itself, at the service boundary: correcting the prose of a decision already in force
     * is refused and nothing is written - the caller is pointed at {@code adr_supersede} instead.
     */
    @Test
    void updateRejectsATextChangeOnAnAcceptedDecisionAndWritesNothing() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        Adr accepted = service.accept(PROJECT, code).adr();

        AdrTextImmutableException thrown = assertThrows(AdrTextImmutableException.class,
                () -> service.update(PROJECT, code,
                        AdrCorrection.builder().name("A rewritten title").build()));

        assertEquals(AdrStatus.ACCEPTED, thrown.status());
        assertEquals(accepted, repository.findByCode(PROJECT, code).orElseThrow());
    }

    /**
     * A reference-only correction of an accepted decision still travels through {@code reviseText}
     * with every text value unchanged - that path must stay a no-op rather than a rejection, or the
     * exception above would swallow the exception this rule deliberately grants.
     */
    @Test
    void updateAcceptsACorrectionThatRestatesTheTextOfAnAcceptedDecisionUnchanged() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        Adr accepted = service.accept(PROJECT, code).adr();

        Adr updated = service.update(PROJECT, code, AdrCorrection.builder()
                .name(accepted.name())
                .addressesRequirementCodes(List.of("FR-1"))
                .build()).adr();

        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
    }

    @Test
    void updateRejectsAnUnknownCode() {
        assertThrows(AdrNotFoundException.class, () -> service.update(PROJECT, new AdrCode("ADR-9"),
                AdrCorrection.builder().name("Another title").build()));
    }

    // --- delete ---------------------------------------------------------------

    @Test
    void deleteRemovesAProposedDecision() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();

        service.delete(PROJECT, code);

        assertTrue(service.get(PROJECT, code).isEmpty());
        assertTrue(service.list(PROJECT).isEmpty());
    }

    @Test
    void deleteRejectsAnUnknownCode() {
        assertThrows(AdrNotFoundException.class, () -> service.delete(PROJECT, new AdrCode("ADR-9")));
    }

    /**
     * The staging itself: an accepted decision is a record of what was decided, so it stays - and the
     * refusal names the successor path rather than only saying no.
     */
    @Test
    void deleteRefusesAnAcceptedDecisionAndPointsAtSupersede() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        service.accept(PROJECT, code);

        AdrNotDeletableException thrown =
                assertThrows(AdrNotDeletableException.class, () -> service.delete(PROJECT, code));

        assertEquals(AdrStatus.ACCEPTED, thrown.status());
        assertTrue(thrown.getMessage().contains("adr_supersede"), thrown.getMessage());
        assertTrue(service.get(PROJECT, code).isPresent(), "a refused delete must leave the decision");
    }

    /**
     * The point of the whole staging (see {@code DeleteAdr}): "considered and turned down" is a
     * decision with value, so REJECTED is as undeletable as ACCEPTED - and must not become the
     * back door for getting rid of a record either.
     */
    @Test
    void deleteRefusesARejectedDecisionBecauseTurningAnOptionDownIsItselfADecision() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        service.reject(PROJECT, code);

        AdrNotDeletableException thrown =
                assertThrows(AdrNotDeletableException.class, () -> service.delete(PROJECT, code));

        assertEquals(AdrStatus.REJECTED, thrown.status());
        assertTrue(thrown.getMessage().contains("considered and turned down"), thrown.getMessage());
        assertTrue(service.get(PROJECT, code).isPresent(), "a refused delete must leave the decision");
    }

    @Test
    void deleteRefusesADeprecatedDecision() {
        AdrCode code = service.add(PROJECT, newAdr()).adr().code();
        service.accept(PROJECT, code);
        service.deprecate(PROJECT, code);

        AdrNotDeletableException thrown =
                assertThrows(AdrNotDeletableException.class, () -> service.delete(PROJECT, code));

        assertEquals(AdrStatus.DEPRECATED, thrown.status());
        assertTrue(service.get(PROJECT, code).isPresent(), "a refused delete must leave the decision");
    }

    /**
     * Since kogn-io/arknet#357 a decision reachable through {@code adr_supersede} is never PROPOSED
     * any more when this check runs - the superseding decision must already be ACCEPTED, and the
     * superseded one becomes SUPERSEDED in the very same write - so the status check in
     * {@link AdrService#delete} refuses it first. What is left reachable for a PROPOSED decision is
     * store-first (ADR-005) data: a legacy {@code arkarch:supersedes} edge naming it, written before
     * this issue rather than through the service. This test seeds exactly that, bypassing the
     * service the same way store-first data would have arrived.
     *
     * <p><strong>Known imprecision, accepted rather than hidden (kogn-io/arknet#359).</strong>
     * {@link AdrService#rejectIfReferenced} labels every referrer {@code supersededBy}, the only
     * shape any write path still produces -
     * {@code de.hauschel.arknet.adr.application.port.out.AdrRepository#findSupersessionReferrers}
     * unions the current-model edge with this legacy one into a single list this didactic pre-check
     * cannot split without a dedicated port method. The rejection therefore names this test's legacy
     * referrer with the same {@code supersededBy} label a current-model one would get - wrong for
     * this one rare, store-first-only case, but the race-free backstop
     * ({@code AdrRepository#delete}) reads the two predicates separately and gets it right, which is
     * what actually stops the delete.</p>
     */
    @Test
    void deleteRefusesAProposedDecisionALegacySupersedesEdgeStillNames() {
        AdrCode superseded = service.add(PROJECT, newAdr()).adr().code();
        AdrCode successor = service.add(PROJECT, newAdr()).adr().code();
        repository.seedLegacySupersession(PROJECT, successor, superseded);

        AdrReferencedException thrown =
                assertThrows(AdrReferencedException.class, () -> service.delete(PROJECT, superseded));

        assertEquals(List.of(new AdrReferencedException.Reference(successor,
                AdrReferencedException.SUPERSEDED_BY)), thrown.references());
        assertTrue(thrown.getMessage().contains(successor.value()), thrown.getMessage());
        assertTrue(service.get(PROJECT, superseded).isPresent(), "a refused delete must leave the decision");
    }

    /**
     * The other incoming edge, and the one a caller can actually clear: only the forward direction of
     * {@code relatedTo} is stored, so the peer named by somebody else has to be protected by a
     * reverse read - and the refusal points at {@code adr_update} for it.
     */
    @Test
    void deleteRefusesADecisionAnotherOneIsRelatedTo() {
        AdrCode peer = service.add(PROJECT, newAdr()).adr().code();
        service.add(PROJECT, new NewAdr("Title", "Some context here", "Some decision here",
                null, null, null, null, null, List.of(peer.value())));

        AdrReferencedException thrown =
                assertThrows(AdrReferencedException.class, () -> service.delete(PROJECT, peer));

        assertEquals(List.of(new AdrReferencedException.Reference(new AdrCode("ADR-2"),
                AdrReferencedException.RELATED_TO)), thrown.references());
        assertTrue(thrown.getMessage().contains("adr_update"), thrown.getMessage());
        assertTrue(service.get(PROJECT, peer).isPresent(), "a refused delete must leave the decision");
    }

    /**
     * The decision that carries the edge is free to go: only the <em>incoming</em> direction leaves a
     * dangling reference behind, so a decision that merely names others is deletable.
     */
    @Test
    void deleteAllowsADecisionThatOnlyPointsAtOthers() {
        AdrCode peer = service.add(PROJECT, newAdr()).adr().code();
        AdrCode naming = service.add(PROJECT, new NewAdr("Title", "Some context here",
                "Some decision here", null, null, null, null, null, List.of(peer.value())))
                .adr().code();

        service.delete(PROJECT, naming);

        assertTrue(service.get(PROJECT, naming).isEmpty());
        assertTrue(service.get(PROJECT, peer).isPresent());
    }

    /**
     * The numbering must not fall back over a delete: {@code ADR-2} may already stand for something
     * in a commit message or a note, so the next decision is {@code ADR-3} even though nothing
     * carries the number 2 any more.
     */
    @Test
    void deleteDoesNotFreeTheCodeForTheNextDecision() {
        service.add(PROJECT, newAdr());
        AdrCode second = service.add(PROJECT, newAdr()).adr().code();
        assertEquals(new AdrCode("ADR-2"), second);

        service.delete(PROJECT, second);
        AdrCode next = service.add(PROJECT, newAdr()).adr().code();

        assertEquals(new AdrCode("ADR-3"), next);
    }

    /** Retained codes are per project, exactly as the living ones are. */
    @Test
    void retainedCodesDoNotLeakIntoAnotherProjectsNumbering() {
        ProjectId other = new ProjectId("other-project");
        service.add(PROJECT, newAdr());
        service.delete(PROJECT, new AdrCode("ADR-1"));

        assertEquals(new AdrCode("ADR-1"), service.add(other, newAdr()).adr().code());
    }

    private static NewAdr newAdr() {
        return new NewAdr("Use an embedded triple store",
                "The model has to live somewhere a single-user client can reach without a server.",
                "Use kognio-rdf as the embedded RDF substrate behind an out-port.",
                null, null, null, null, null, null);
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
