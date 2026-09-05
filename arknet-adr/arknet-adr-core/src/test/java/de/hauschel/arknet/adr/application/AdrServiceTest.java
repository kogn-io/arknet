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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.Map;
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
import de.hauschel.arknet.adr.domain.RemovedPositions;
import de.hauschel.arknet.adr.domain.AdrTextImmutableException;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsequenceCorrection;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.adr.domain.NewConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.adr.domain.TermRef;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Policy tests for {@link AdrService}: identity minting, code assignment, listing, lookup, the
 * accept transition, field-wise correction (including the structured consequence/considered-option
 * lists and the fine-grained multilingual text lock, kogn-io/arknet#357), both directions of the
 * supersedes relation and the status- and reference-staged delete, exercised against an in-memory
 * fake repository, two fake lookups and a deterministic fake {@link ResourceIdFactory}.
 */
class AdrServiceTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final ResourceId FR_1 = ResourceId.of("https://w3id.org/arknet/id/fr-1");
    private static final ResourceId NFR_2 = ResourceId.of("https://w3id.org/arknet/id/nfr-2");
    private static final ResourceId BC_1 = ResourceId.of("https://w3id.org/arknet/id/bc-1");
    private static final ResourceId TERM_1 = ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final String DEFAULT_LANGUAGE = "en";
    /**
     * The day {@code accept}/{@code reject} stamp when the caller names none. Pinned rather than
     * ambient so the stamped date is asserted against a value instead of against whatever day the
     * suite happens to run on (kogn-io/arknet#374).
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private InMemoryAdrRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private InMemoryReferenceLookups.Requirements requirements;
    private InMemoryReferenceLookups.BoundedContexts contexts;
    private InMemoryReferenceLookups.Terms terms;
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
        terms = new InMemoryReferenceLookups.Terms();
        terms.register("TERM-1", TERM_1);
        service = new AdrService(repository, resourceIdFactory, requirements, contexts, terms, CLOCK);
    }

    private AdrDetail add(NewAdr command) {
        return service.add(PROJECT, command, DEFAULT_LANGUAGE);
    }

    private List<AdrDetail> list(ProjectId projectId) {
        return service.list(projectId, null);
    }

    private java.util.Optional<AdrDetail> get(ProjectId projectId, AdrCode code) {
        return service.get(projectId, code, null);
    }

    private AdrDetail update(AdrCode code, AdrCorrection correction) {
        return service.update(PROJECT, code, correction, DEFAULT_LANGUAGE);
    }

    @Test
    void addAssignsFirstBusinessCodeAndStartsProposed() {
        AdrDetail added = add(newAdr());

        assertEquals(new AdrCode("ADR-1"), added.adr().code());
        assertEquals(AdrStatus.PROPOSED, added.adr().status());
        assertEquals("Use an embedded triple store", added.adr().name());
        assertNull(added.adr().supersededBy());
        assertEquals(added.adr(), repository.findByCode(PROJECT, added.adr().code(), null).orElseThrow());
    }

    @Test
    void addAcceptsOptionalFieldsAsNullOrEmpty() {
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, null, null, null, null));

        assertEquals(List.of(), added.adr().consequences());
        assertEquals(List.of(), added.adr().consideredOptions());
        assertNull(added.adr().decisionDate());
        assertEquals(List.of(), added.adr().addressesRequirements());
        assertEquals(List.of(), added.adr().affectsContexts());
    }

    @Test
    void addAcceptsStructuredConsequencesAndConsideredOptions() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("Faster reads", ConsequenceType.POSITIVE),
                        new NewConsequence("More operational complexity", ConsequenceType.NEGATIVE)),
                List.of(new NewConsideredOption("Adopt library X", "Well understood", OptionOutcome.CHOSEN),
                        new NewConsideredOption("Build in-house", "Too slow", OptionOutcome.REJECTED)),
                DEFAULT_LANGUAGE, null, null, null, null));

        assertEquals(List.of(
                new Consequence(1, "Faster reads", ConsequenceType.POSITIVE),
                new Consequence(2, "More operational complexity", ConsequenceType.NEGATIVE)),
                added.adr().consequences());
        assertEquals(List.of(
                new ConsideredOption(1, "Adopt library X", "Well understood", OptionOutcome.CHOSEN),
                new ConsideredOption(2, "Build in-house", "Too slow", OptionOutcome.REJECTED)),
                added.adr().consideredOptions());
    }

    @Test
    void addResolvesCrossContextReferencesToOpaqueIdentities() {
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE,
                List.of("FR-1", "NFR-2"), List.of("BC-1"), List.of("TERM-1"), null));

        assertEquals(List.of(new RequirementRef(FR_1), new RequirementRef(NFR_2)),
                added.adr().addressesRequirements());
        assertEquals(List.of(new BoundedContextRef(BC_1)), added.adr().affectsContexts());
        assertEquals(List.of(new TermRef(TERM_1)), added.adr().usesTerms());
    }

    /**
     * Resolution happens in the service and must abort the whole call: a decision half-linked to a
     * requirement that does not exist is worse than no decision at all.
     */
    @Test
    void addPropagatesTheLookupFailureForAnUnknownReferenceAndWritesNothing() {
        assertThrows(NoSuchElementException.class, () -> add(
                new NewAdr("Title", "Some context here", "Some decision here", null, null,
                        DEFAULT_LANGUAGE, List.of("FR-99"), null, null, null)));

        assertTrue(list(PROJECT).isEmpty());
    }

    @Test
    void addDeduplicatesRepeatedReferenceCodes() {
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, List.of("FR-1", "FR-1"), null, null, null));

        assertEquals(List.of(new RequirementRef(FR_1)), added.adr().addressesRequirements());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        AdrDetail first = add(newAdr());
        AdrDetail second = add(newAdr());

        assertNotEquals(first.adr().id(), second.adr().id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addNumbersRunSequentially() {
        assertEquals(new AdrCode("ADR-1"), add(newAdr()).adr().code());
        assertEquals(new AdrCode("ADR-2"), add(newAdr()).adr().code());
        assertEquals(new AdrCode("ADR-3"), add(newAdr()).adr().code());
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        add(newAdr());

        AdrDetail inOther = service.add(other, newAdr(), DEFAULT_LANGUAGE);

        assertEquals(new AdrCode("ADR-1"), inOther.adr().code());
        assertEquals(1, list(PROJECT).size());
        assertEquals(1, list(other).size());
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
        add(newAdr());
        repository.seedUnmaterialisableCode(PROJECT, new AdrCode("ADR-2"));

        AdrDetail third = add(newAdr());

        assertEquals(new AdrCode("ADR-3"), third.adr().code());
    }

    @Test
    void skippedCountIsZeroWhenNothingWasSkipped() {
        add(newAdr());

        assertEquals(0, service.skippedCount(PROJECT, list(PROJECT).size()));
    }

    @Test
    void skippedCountReportsEveryCodeFindAllCouldNotMaterialise() {
        add(newAdr());
        repository.seedUnmaterialisableCode(PROJECT, new AdrCode("ADR-2"));
        repository.seedUnmaterialisableCode(PROJECT, new AdrCode("ADR-3"));

        assertEquals(2, service.skippedCount(PROJECT, list(PROJECT).size()));
        assertEquals(0, service.skippedCount(new ProjectId("other"), 0));
    }

    /**
     * The caller's count and this read are two unsynchronised observations: a decision recorded in
     * between makes the code list the longer one, and a negative "skipped" would be a worse signal
     * than a merely stale zero.
     */
    @Test
    void skippedCountClampsAtZeroWhenTheCallerSawMoreThanTheCodeListHolds() {
        add(newAdr());

        assertEquals(0, service.skippedCount(PROJECT, 5));
    }

    /** A negative count is a bug in the caller, not a store anomaly to be silently clamped away. */
    @Test
    void skippedCountRefusesANegativeMaterialisedCount() {
        assertThrows(IllegalArgumentException.class, () -> service.skippedCount(PROJECT, -1));
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        add(new NewAdr("A", "Context of A here", "Decision A", null, null, DEFAULT_LANGUAGE, null, null, null, null));
        add(new NewAdr("B", "Context of B here", "Decision B", null, null, DEFAULT_LANGUAGE, null, null, null, null));

        List<AdrDetail> all = list(PROJECT);

        assertEquals(2, all.size());
        assertEquals("A", all.get(0).adr().name());
        assertEquals("B", all.get(1).adr().name());
    }

    @Test
    void getReturnsPersistedAdr() {
        AdrCode code = add(newAdr()).adr().code();

        assertTrue(get(PROJECT, code).isPresent());
        assertEquals("Use an embedded triple store", get(PROJECT, code).orElseThrow().adr().name());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(get(PROJECT, new AdrCode("ADR-99")).isPresent());
    }

    /**
     * kogn-io/arknet#374: a decision that has not been made carries no date it was made on.
     * {@code adr_add} no longer takes the field at all, so this is what every new record looks like.
     */
    @Test
    void addRecordsNoDecisionDate() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                null, null, DEFAULT_LANGUAGE, null, null, null, null));

        assertEquals(AdrStatus.PROPOSED, added.adr().status());
        assertNull(added.adr().decisionDate());
    }

    /** The ordinary case: the decision is being made now, so now is what gets recorded. */
    @Test
    void acceptStampsTodayWhenTheCallerNamesNoDay() {
        AdrCode code = add(new NewAdr("Title", "Some context here", "Some decision here",
                null, null, DEFAULT_LANGUAGE, null, null, null, null)).adr().code();

        assertEquals(TODAY, service.accept(PROJECT, code, null, DEFAULT_LANGUAGE).adr().decisionDate());
    }

    /**
     * The one honest exception the free-form field used to serve: a decision genuinely made earlier
     * and only now entered. It is the caller's day that is recorded, not the day of the entry.
     */
    @Test
    void acceptStampsTheDayTheCallerNamesInstead() {
        AdrCode code = add(new NewAdr("Title", "Some context here", "Some decision here",
                null, null, DEFAULT_LANGUAGE, null, null, null, null)).adr().code();

        assertEquals(LocalDate.of(2024, 3, 11),
                service.accept(PROJECT, code, LocalDate.of(2024, 3, 11), DEFAULT_LANGUAGE).adr().decisionDate());
    }

    @Test
    void rejectStampsTheDecisionDateJustAsAcceptDoes() {
        AdrCode code = add(new NewAdr("Title", "Some context here", "Some decision here",
                null, null, DEFAULT_LANGUAGE, null, null, null, null)).adr().code();

        assertEquals(TODAY, service.reject(PROJECT, code, null, DEFAULT_LANGUAGE).adr().decisionDate());
    }

    /**
     * The correction path must carry the stamped date through untouched. It cannot address the field
     * any more (the correction object no longer has it), but it does rebuild the decision from the
     * caller's fields plus the current state - so a date silently dropped there would be the same
     * bug in a new place.
     */
    @Test
    void updateLeavesTheStampedDecisionDateAlone() {
        AdrCode code = add(new NewAdr("Title", "Some context here", "Some decision here",
                null, null, DEFAULT_LANGUAGE, null, null, null, null)).adr().code();
        service.accept(PROJECT, code, LocalDate.of(2024, 3, 11), DEFAULT_LANGUAGE);

        Adr corrected = update(code,
                AdrCorrection.builder().addressesRequirementCodes(List.of("FR-1")).build()).adr();

        assertEquals(LocalDate.of(2024, 3, 11), corrected.decisionDate());
    }

    @Test
    void acceptTransitionsFromProposedToAccepted() {
        AdrCode code = add(newAdr()).adr().code();

        AdrDetail accepted = service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        assertEquals(AdrStatus.ACCEPTED, accepted.adr().status());
        assertEquals(AdrStatus.ACCEPTED, get(PROJECT, code).orElseThrow().adr().status());
    }

    @Test
    void acceptingAnAlreadyAcceptedAdrIsANoOp() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        AdrDetail again = service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        assertEquals(AdrStatus.ACCEPTED, again.adr().status());
    }

    @Test
    void acceptThrowsWhenAdrUnknown() {
        AdrNotFoundException ex = assertThrows(AdrNotFoundException.class,
                () -> service.accept(PROJECT, new AdrCode("ADR-42"), null, DEFAULT_LANGUAGE));

        assertSame(PROJECT, ex.projectId());
        assertEquals(new AdrCode("ADR-42"), ex.adrCode());
    }

    @Test
    void rejectTransitionsFromProposedToRejected() {
        AdrCode code = add(newAdr()).adr().code();

        AdrDetail rejected = service.reject(PROJECT, code, null, DEFAULT_LANGUAGE);

        assertEquals(AdrStatus.REJECTED, rejected.adr().status());
        assertEquals(AdrStatus.REJECTED, get(PROJECT, code).orElseThrow().adr().status());
    }

    @Test
    void rejectingAnAlreadyRejectedAdrIsANoOp() {
        AdrCode code = add(newAdr()).adr().code();
        service.reject(PROJECT, code, null, DEFAULT_LANGUAGE);

        AdrDetail again = service.reject(PROJECT, code, null, DEFAULT_LANGUAGE);

        assertEquals(AdrStatus.REJECTED, again.adr().status());
    }

    @Test
    void rejectThrowsWhenAdrUnknown() {
        AdrNotFoundException ex = assertThrows(AdrNotFoundException.class,
                () -> service.reject(PROJECT, new AdrCode("ADR-42"), null, DEFAULT_LANGUAGE));

        assertSame(PROJECT, ex.projectId());
        assertEquals(new AdrCode("ADR-42"), ex.adrCode());
    }

    @Test
    void deprecateTransitionsFromAcceptedToDeprecated() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        AdrDetail deprecated = service.deprecate(PROJECT, code, DEFAULT_LANGUAGE);

        assertEquals(AdrStatus.DEPRECATED, deprecated.adr().status());
        assertEquals(AdrStatus.DEPRECATED, get(PROJECT, code).orElseThrow().adr().status());
    }

    @Test
    void deprecatingAnAlreadyDeprecatedAdrIsANoOp() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);
        service.deprecate(PROJECT, code, DEFAULT_LANGUAGE);

        AdrDetail again = service.deprecate(PROJECT, code, DEFAULT_LANGUAGE);

        assertEquals(AdrStatus.DEPRECATED, again.adr().status());
    }

    @Test
    void deprecateThrowsWhenAdrUnknown() {
        AdrNotFoundException ex = assertThrows(AdrNotFoundException.class,
                () -> service.deprecate(PROJECT, new AdrCode("ADR-42"), DEFAULT_LANGUAGE));

        assertSame(PROJECT, ex.projectId());
        assertEquals(new AdrCode("ADR-42"), ex.adrCode());
    }

    /**
     * Issue #468: {@link AdrService#accept}/{@link AdrService#reject}/{@link AdrService#deprecate}/
     * {@link AdrService#supersede}/{@link AdrService#unsupersede} share
     * {@link AdrService#update}'s read-modify-write round trip, but - unlike {@code update} since
     * issue #456 - used to always pass a hardcoded {@code null} {@code defaultLanguage} to
     * {@link AdrRepository#findCurrentByCode} regardless of what their own {@code defaultLanguage}
     * argument carried. This in-memory fake cannot select a different literal per language (see its
     * own javadoc), so these tests instead pin the argument itself: the project's default language
     * must reach {@code findCurrentByCode}, not {@code null}.
     */
    @Test
    void acceptPassesTheProjectsDefaultLanguageToFindCurrentByCode() {
        AdrCode code = add(newAdr()).adr().code();

        service.accept(PROJECT, code, null, "de");

        assertEquals("de", repository.lastFindCurrentByCodeDefaultLanguage);
    }

    @Test
    void rejectPassesTheProjectsDefaultLanguageToFindCurrentByCode() {
        AdrCode code = add(newAdr()).adr().code();

        service.reject(PROJECT, code, null, "de");

        assertEquals("de", repository.lastFindCurrentByCodeDefaultLanguage);
    }

    @Test
    void deprecatePassesTheProjectsDefaultLanguageToFindCurrentByCode() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        service.deprecate(PROJECT, code, "de");

        assertEquals("de", repository.lastFindCurrentByCodeDefaultLanguage);
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

        AdrDetail superseded = service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);

        assertEquals(older, superseded.adr().code());
        assertEquals(AdrStatus.SUPERSEDED, superseded.adr().status());
        assertEquals(List.of(newer), superseded.supersededBy());
        assertEquals(List.of(), superseded.supersedes());
        AdrDetail superseding = get(PROJECT, newer).orElseThrow();
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
        Adr before = repository.findByCode(PROJECT, newer, null).orElseThrow();

        service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);

        assertEquals(before, repository.findByCode(PROJECT, newer, null).orElseThrow());
    }

    @Test
    void supersedeIsIdempotent() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);

        AdrDetail again = service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);

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
        service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);
        service.deprecate(PROJECT, newer, DEFAULT_LANGUAGE);

        AdrDetail again = service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);

        assertEquals(AdrStatus.SUPERSEDED, again.adr().status());
        assertEquals(List.of(newer), again.supersededBy());
        assertEquals(AdrStatus.DEPRECATED, get(PROJECT, newer).orElseThrow().adr().status());
    }

    /** One successor may supersede several older decisions, each individually. */
    @Test
    void supersedeAccumulatesSeveralOlderDecisions() {
        AdrCode first = acceptedAdr();
        AdrCode second = acceptedAdr();
        AdrCode newest = acceptedAdr();

        service.supersede(PROJECT, newest, first, DEFAULT_LANGUAGE);
        service.supersede(PROJECT, newest, second, DEFAULT_LANGUAGE);

        assertEquals(List.of(first, second), get(PROJECT, newest).orElseThrow().supersedes());
    }

    @Test
    void supersedeRejectsAnUnknownSupersededCodeAndWritesNothing() {
        AdrCode newer = acceptedAdr();

        assertThrows(AdrNotFoundException.class,
                () -> service.supersede(PROJECT, newer, new AdrCode("ADR-99"), DEFAULT_LANGUAGE));

        assertEquals(List.of(), get(PROJECT, newer).orElseThrow().supersedes());
    }

    @Test
    void supersedeRejectsAnUnknownSupersedingCodeAndWritesNothing() {
        AdrCode older = acceptedAdr();

        assertThrows(AdrNotFoundException.class,
                () -> service.supersede(PROJECT, new AdrCode("ADR-99"), older, DEFAULT_LANGUAGE));

        assertEquals(AdrStatus.ACCEPTED, get(PROJECT, older).orElseThrow().adr().status());
    }

    /**
     * Only an ACCEPTED decision may supersede another - mutation test: removing this check would
     * let a PROPOSED (or REJECTED/DEPRECATED/SUPERSEDED) decision supersede an older one before it
     * was itself ever accepted.
     */
    @Test
    void supersedeRejectsWhenTheSupersedingDecisionIsNotAccepted() {
        AdrCode older = acceptedAdr();
        AdrCode proposedNewer = add(newAdr()).adr().code();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.supersede(PROJECT, proposedNewer, older, DEFAULT_LANGUAGE));

        assertTrue(thrown.getMessage().contains(proposedNewer.value()), thrown.getMessage());
        assertEquals(AdrStatus.ACCEPTED, get(PROJECT, older).orElseThrow().adr().status());
    }

    /**
     * Only an ACCEPTED decision may be superseded - mutation test: removing this check (which lives
     * on {@code Adr#supersededBy}, exercised here through the service) would let a PROPOSED decision
     * be marked SUPERSEDED without ever having been ACCEPTED.
     */
    @Test
    void supersedeRejectsWhenTheSupersededDecisionIsNotAccepted() {
        AdrCode newer = acceptedAdr();
        AdrCode proposedOlder = add(newAdr()).adr().code();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.supersede(PROJECT, newer, proposedOlder, DEFAULT_LANGUAGE));

        assertTrue(thrown.getMessage().contains(proposedOlder.value()), thrown.getMessage());
        assertEquals(AdrStatus.PROPOSED, get(PROJECT, proposedOlder).orElseThrow().adr().status());
    }

    /** A decision already superseded by one successor cannot be superseded again by a different one. */
    @Test
    void supersedeRejectsANewSuccessorForAnAlreadySupersededDecision() {
        AdrCode older = acceptedAdr();
        AdrCode firstSuccessor = acceptedAdr();
        AdrCode secondSuccessor = acceptedAdr();
        service.supersede(PROJECT, firstSuccessor, older, DEFAULT_LANGUAGE);

        assertThrows(IllegalStateException.class, () -> service.supersede(PROJECT, secondSuccessor, older, DEFAULT_LANGUAGE));

        assertEquals(List.of(firstSuccessor), get(PROJECT, older).orElseThrow().supersededBy());
    }

    /** Adds a decision and accepts it in one step - the precondition every supersede test needs. */
    private AdrCode acceptedAdr() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);
        return code;
    }

    @Test
    void supersedeRejectsSelfReference() {
        AdrCode code = add(newAdr()).adr().code();

        assertThrows(IllegalArgumentException.class, () -> service.supersede(PROJECT, code, code, DEFAULT_LANGUAGE));
    }

    /**
     * The remedy for a mistaken {@code adr_supersede} call (kogn-io/arknet#354): restores ACCEPTED
     * and clears the edge, in one write, without touching the successor's own record at all.
     */
    @Test
    void unsupersedeRestoresAcceptedAndClearsTheEdge() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);
        Adr successorBefore = repository.findByCode(PROJECT, newer, null).orElseThrow();

        AdrDetail restored = service.unsupersede(PROJECT, older, DEFAULT_LANGUAGE);

        assertEquals(AdrStatus.ACCEPTED, restored.adr().status());
        assertNull(restored.adr().supersededBy());
        assertEquals(List.of(), restored.supersededBy());
        assertEquals(successorBefore, repository.findByCode(PROJECT, newer, null).orElseThrow());
    }

    /** Once unsuperseded, the decision can be superseded again, including by the very same successor. */
    @Test
    void unsupersededDecisionCanBeSupersededAgain() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);
        service.unsupersede(PROJECT, older, DEFAULT_LANGUAGE);

        AdrDetail supersededAgain = service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);

        assertEquals(AdrStatus.SUPERSEDED, supersededAgain.adr().status());
        assertEquals(List.of(newer), supersededAgain.supersededBy());
    }

    @Test
    void unsupersedeThrowsUnlessTheDecisionIsSuperseded() {
        AdrCode accepted = acceptedAdr();
        AdrCode proposed = add(newAdr()).adr().code();

        assertThrows(IllegalStateException.class, () -> service.unsupersede(PROJECT, accepted, DEFAULT_LANGUAGE));
        assertThrows(IllegalStateException.class, () -> service.unsupersede(PROJECT, proposed, DEFAULT_LANGUAGE));
    }

    @Test
    void unsupersedeRejectsAnUnknownCode() {
        assertThrows(AdrNotFoundException.class, () -> service.unsupersede(PROJECT, new AdrCode("ADR-99"), DEFAULT_LANGUAGE));
    }

    /** {@link #acceptPassesTheProjectsDefaultLanguageToFindCurrentByCode}'s counterpart. */
    @Test
    void supersedePassesTheProjectsDefaultLanguageToFindCurrentByCode() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();

        service.supersede(PROJECT, newer, older, "de");

        assertEquals("de", repository.lastFindCurrentByCodeDefaultLanguage);
    }

    /** {@link #acceptPassesTheProjectsDefaultLanguageToFindCurrentByCode}'s counterpart. */
    @Test
    void unsupersedePassesTheProjectsDefaultLanguageToFindCurrentByCode() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);

        service.unsupersede(PROJECT, older, "de");

        assertEquals("de", repository.lastFindCurrentByCodeDefaultLanguage);
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
        service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);

        Adr updated = update(older,
                AdrCorrection.builder().addressesRequirementCodes(List.of("FR-1")).build()).adr();

        assertEquals(AdrStatus.SUPERSEDED, updated.status());
        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
    }

    /** {@code adr_list} derives both supersedes directions from its single full read. */
    @Test
    void listReportsBothSupersedesDirectionsWithoutAnyExtraRead() {
        AdrCode older = acceptedAdr();
        AdrCode newer = acceptedAdr();
        service.supersede(PROJECT, newer, older, DEFAULT_LANGUAGE);

        List<AdrDetail> all = list(PROJECT);

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
                null, null, null, List.of(), List.of(), List.of(), phantomSuccessorId, List.of());
        repository.create(PROJECT, superseded, "en");

        List<AdrDetail> all = service.list(PROJECT, null);

        AdrDetail detail = all.stream()
                .filter(candidate -> candidate.adr().code().equals(new AdrCode("ADR-1")))
                .findFirst().orElseThrow();
        assertEquals(List.of(new AdrCode("ADR-9")), detail.supersededBy());
    }

    /**
     * Since kogn-io/arknet#357 a live decision has at most one successor ({@code supersededBy}
     * carries {@code sh:maxCount 1}), so more than one entry in {@code supersededBy} can only come
     * from the pre-#357 legacy shape.
     */
    @Test
    void listSortsSupersededByRunningNumberNotLexicographically() {
        AdrCode target = add(newAdr()).adr().code();
        for (int i = 0; i < 10; i++) {
            AdrCode superseding = add(newAdr()).adr().code();
            repository.seedLegacySupersession(PROJECT, superseding, target);
        }

        AdrDetail targetDetail = list(PROJECT).stream()
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
        AdrDetail peer = add(newAdr());

        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, null, null, null,
                List.of(peer.adr().code().value())));

        assertEquals(List.of(peer.adr().id()), added.adr().relatedTo());
        assertEquals(List.of(peer.adr().code()), added.relatedTo());
    }

    @Test
    void updateSetsAndClearsRelatedTo() {
        AdrDetail peer = add(newAdr());
        AdrDetail added = add(newAdr());

        Adr linked = update(added.adr().code(), AdrCorrection.builder()
                .relatedToCodes(List.of(peer.adr().code().value())).build()).adr();
        assertEquals(List.of(peer.adr().id()), linked.relatedTo());

        Adr cleared = update(added.adr().code(),
                AdrCorrection.builder().relatedToCodes(List.of()).build()).adr();
        assertEquals(List.of(), cleared.relatedTo());
    }

    /** The tri-state again: an omitted list leaves the peers alone rather than wiping them. */
    @Test
    void updateLeavesRelatedToUntouchedWhenTheListIsNotGiven() {
        AdrDetail peer = add(newAdr());
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, null, null, null,
                List.of(peer.adr().code().value())));

        Adr updated = update(added.adr().code(),
                AdrCorrection.builder().name("Another title").build()).adr();

        assertEquals(List.of(peer.adr().id()), updated.relatedTo());
    }

    /**
     * Resolution sits before any write, exactly as for the three cross-context lists - an unknown peer
     * code aborts the whole call rather than leaving a half-linked decision behind.
     */
    @Test
    void addRejectsAnUnknownRelatedCodeBeforeWritingAnything() {
        assertThrows(AdrNotFoundException.class, () -> add(
                new NewAdr("Title", "Some context here", "Some decision here", null, null,
                        DEFAULT_LANGUAGE, null, null, null, List.of("ADR-9"))));

        assertTrue(list(PROJECT).isEmpty());
    }

    @Test
    void updateRejectsAnUnknownRelatedCodeBeforeWritingAnything() {
        AdrDetail added = add(newAdr());

        assertThrows(AdrNotFoundException.class, () -> update(added.adr().code(),
                AdrCorrection.builder().name("Another title").relatedToCodes(List.of("ADR-9")).build()));

        assertEquals(added.adr(), repository.findByCode(PROJECT, added.adr().code(), null).orElseThrow());
    }

    /**
     * Same refusal, same wording as {@code adr_supersede}'s: a decision is not its own peer.
     */
    @Test
    void updateRejectsRelatingADecisionToItselfBeforeWritingAnything() {
        AdrDetail added = add(newAdr());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> update(added.adr().code(), AdrCorrection.builder()
                        .name("Another title")
                        .relatedToCodes(List.of(added.adr().code().value())).build()));

        assertTrue(thrown.getMessage().contains(added.adr().code().value()), thrown.getMessage());
        assertEquals(added.adr(), repository.findByCode(PROJECT, added.adr().code(), null).orElseThrow());
    }

    /**
     * The relation is symmetric, so a reader of the peer must see the decision that named it even
     * though only the forward triple exists. One reverse read, not a traversal.
     */
    @Test
    void getMergesTheBackwardDirectionIntoTheOneRelatedToList() {
        AdrDetail peer = add(newAdr());
        AdrDetail naming = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, null, null, null,
                List.of(peer.adr().code().value())));

        assertEquals(List.of(peer.adr().code()),
                get(PROJECT, naming.adr().code()).orElseThrow().relatedTo());
        assertEquals(List.of(naming.adr().code()),
                get(PROJECT, peer.adr().code()).orElseThrow().relatedTo());
    }

    /** {@code list} inverts its one full read in memory and must land on the same answer. */
    @Test
    void listMergesRelatedToExactlyAsGetDoes() {
        AdrDetail peer = add(newAdr());
        AdrDetail naming = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, null, null, null,
                List.of(peer.adr().code().value())));

        List<AdrDetail> all = list(PROJECT);

        assertEquals(get(PROJECT, peer.adr().code()).orElseThrow().relatedTo(),
                detailOf(all, peer.adr().code()).relatedTo());
        assertEquals(get(PROJECT, naming.adr().code()).orElseThrow().relatedTo(),
                detailOf(all, naming.adr().code()).relatedTo());
    }

    /**
     * A mutually declared pair is legal - {@code relatedTo} permits cycles, unlike
     * {@code supersedes}. Merging must therefore terminate and report each peer once, not twice.
     */
    @Test
    void aMutualRelatedToPairTerminatesAndReportsEachPeerOnce() {
        AdrDetail first = add(newAdr());
        AdrDetail second = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, null, null, null,
                List.of(first.adr().code().value())));
        update(first.adr().code(), AdrCorrection.builder()
                .relatedToCodes(List.of(second.adr().code().value())).build());

        assertEquals(List.of(second.adr().code()),
                get(PROJECT, first.adr().code()).orElseThrow().relatedTo());
        assertEquals(List.of(first.adr().code()),
                get(PROJECT, second.adr().code()).orElseThrow().relatedTo());
        assertEquals(List.of(second.adr().code()),
                detailOf(list(PROJECT), first.adr().code()).relatedTo());
    }

    /** Ordering is by running number, the same rule {@code supersededBy} follows. */
    @Test
    void relatedToIsSortedByRunningNumberNotLexicographically() {
        AdrDetail target = add(newAdr());
        for (int i = 0; i < 10; i++) {
            add(new NewAdr("Title", "Some context here", "Some decision here",
                    null, null, DEFAULT_LANGUAGE, null, null, null, List.of(target.adr().code().value())));
        }

        assertEquals(
                List.of(new AdrCode("ADR-2"), new AdrCode("ADR-3"), new AdrCode("ADR-4"),
                        new AdrCode("ADR-5"), new AdrCode("ADR-6"), new AdrCode("ADR-7"),
                        new AdrCode("ADR-8"), new AdrCode("ADR-9"), new AdrCode("ADR-10"),
                        new AdrCode("ADR-11")),
                get(PROJECT, target.adr().code()).orElseThrow().relatedTo());
    }

    private static AdrDetail detailOf(List<AdrDetail> all, AdrCode code) {
        return all.stream().filter(detail -> detail.adr().code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void getSortsSupersededByRunningNumberNotLexicographically() {
        AdrCode target = add(newAdr()).adr().code();
        for (int i = 0; i < 10; i++) {
            AdrCode superseding = add(newAdr()).adr().code();
            repository.seedLegacySupersession(PROJECT, superseding, target);
        }

        AdrDetail targetDetail = get(PROJECT, target).orElseThrow();

        assertEquals(
                List.of(new AdrCode("ADR-2"), new AdrCode("ADR-3"), new AdrCode("ADR-4"),
                        new AdrCode("ADR-5"), new AdrCode("ADR-6"), new AdrCode("ADR-7"),
                        new AdrCode("ADR-8"), new AdrCode("ADR-9"), new AdrCode("ADR-10"),
                        new AdrCode("ADR-11")),
                targetDetail.supersededBy());
    }

    @Test
    void listKeepsBothSupersededByEntriesWhenTheirRunningNumbersCollide() {
        AdrCode target = add(newAdr()).adr().code();
        repository.seedLegacySupersession(PROJECT, new AdrCode("ADR-1x"), target);
        repository.seedLegacySupersession(PROJECT, new AdrCode("ADR-2y"), target);

        AdrDetail targetDetail = list(PROJECT).stream()
                .filter(d -> d.adr().code().equals(target)).findFirst().orElseThrow();

        assertEquals(List.of(new AdrCode("ADR-1x"), new AdrCode("ADR-2y")), targetDetail.supersededBy());
    }

    @Test
    void updatePatchesOnlyTheNamedFieldAndLeavesEveryOtherAlone() {
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here",
                List.of(new NewConsequence("What follows", ConsequenceType.NEUTRAL)),
                List.of(new NewConsideredOption("Option", "What else", OptionOutcome.REJECTED)),
                DEFAULT_LANGUAGE, List.of("FR-1"), List.of("BC-1"), null, null));

        Adr updated = update(added.adr().code(),
                AdrCorrection.builder().decision("A sharper decision").build()).adr();

        assertEquals("A sharper decision", updated.decision());
        assertEquals("Title", updated.name());
        assertEquals("Some context here", updated.context());
        assertEquals(List.of(new Consequence(1, "What follows", ConsequenceType.NEUTRAL)), updated.consequences());
        assertEquals(List.of(new ConsideredOption(1, "Option", "What else", OptionOutcome.REJECTED)),
                updated.consideredOptions());
        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
        assertEquals(List.of(new BoundedContextRef(BC_1)), updated.affectsContexts());
        assertEquals(added.adr().id(), updated.id());
        assertEquals(added.adr().code(), updated.code());
    }

    @Test
    void updateLeavesBothReferenceRelationsUntouchedWhenNeitherListIsGiven() {
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, List.of("FR-1"), List.of("BC-1"), null, null));

        Adr updated = update(added.adr().code(),
                AdrCorrection.builder().name("Another title").build()).adr();

        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
        assertEquals(List.of(new BoundedContextRef(BC_1)), updated.affectsContexts());
    }

    @Test
    void updateClearsAReferenceRelationWhenGivenAnEmptyList() {
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, List.of("FR-1"), List.of("BC-1"), null, null));

        Adr updated = update(added.adr().code(),
                AdrCorrection.builder().addressesRequirementCodes(List.of()).build()).adr();

        assertEquals(List.of(), updated.addressesRequirements());
        assertEquals(List.of(new BoundedContextRef(BC_1)), updated.affectsContexts());
    }

    @Test
    void updateReplacesAReferenceRelationWholesale() {
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, List.of("FR-1"), null, null, null));

        Adr updated = update(added.adr().code(),
                AdrCorrection.builder().addressesRequirementCodes(List.of("NFR-2")).build()).adr();

        assertEquals(List.of(new RequirementRef(NFR_2)), updated.addressesRequirements());
    }

    @Test
    void updateRejectsAnUnknownReferenceCodeBeforeWritingAnything() {
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, List.of("FR-1"), null, null, null));

        assertThrows(NoSuchElementException.class, () -> update(added.adr().code(),
                AdrCorrection.builder().name("Another title")
                        .addressesRequirementCodes(List.of("FR-99")).build()));
        assertThrows(NoSuchElementException.class, () -> update(added.adr().code(),
                AdrCorrection.builder().affectsContextCodes(List.of("BC-99")).build()));

        assertEquals(added.adr(), repository.findByCode(PROJECT, added.adr().code(), null).orElseThrow());
    }

    @Test
    void updateCorrectsTheReferencesOfAnAcceptedDecision() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        Adr updated = update(code,
                AdrCorrection.builder().addressesRequirementCodes(List.of("FR-1")).build()).adr();

        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
        assertEquals(AdrStatus.ACCEPTED, updated.status());
    }

    /**
     * The rule itself, at the service boundary: correcting an existing language variant of the prose
     * of a decision already in force is refused and nothing is written - the caller is pointed at
     * {@code adr_supersede} instead. Uses the same {@code DEFAULT_LANGUAGE} the decision was created
     * under, so this is a same-language edit, not a new variant.
     */
    @Test
    void updateRejectsASameLanguageTextChangeOnAnAcceptedDecisionAndWritesNothing() {
        AdrCode code = add(newAdr()).adr().code();
        Adr accepted = service.accept(PROJECT, code, null, DEFAULT_LANGUAGE).adr();

        AdrTextImmutableException thrown = assertThrows(AdrTextImmutableException.class,
                () -> update(code, AdrCorrection.builder().name("A rewritten title").build()));

        assertEquals(AdrStatus.ACCEPTED, thrown.status());
        assertEquals(accepted, repository.findByCode(PROJECT, code, null).orElseThrow());
    }

    /**
     * The kogn-io/arknet#357 exemption: a call that writes {@code name}/{@code context}/
     * {@code decision} under a language none of the three fields carries yet is exempt from the
     * immutability gate even though the decision is already ACCEPTED - it adds a translation, it
     * does not change what was decided. Mutation test: removing the {@code newLanguageVariant}
     * exemption in {@code Adr#reviseText} turns this green test into an unexpected
     * {@link AdrTextImmutableException}.
     */
    @Test
    void updateAllowsANewLanguageVariantOfTheTitleOnAnAcceptedDecision() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        AdrDetail updated = service.update(PROJECT, code,
                AdrCorrection.builder().name("Einen eingebetteten Triple Store verwenden")
                        .language("de").build(),
                DEFAULT_LANGUAGE);

        assertEquals("Einen eingebetteten Triple Store verwenden", updated.adr().name());
        assertEquals(AdrStatus.ACCEPTED, updated.adr().status());
    }

    /**
     * The mirror image of the previous test: correcting the SAME language ({@code DEFAULT_LANGUAGE},
     * already carried) on an accepted decision is still refused - the exemption only ever applies to
     * a genuinely new language, never a same-language edit dressed up with an explicit
     * {@code language} argument that happens to match what is already there.
     */
    @Test
    void updateRejectsExplicitlyReassertingTheSameLanguageOnAnAcceptedDecision() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        AdrTextImmutableException thrown = assertThrows(AdrTextImmutableException.class,
                () -> service.update(PROJECT, code, AdrCorrection.builder()
                        .name("A rewritten title").language(DEFAULT_LANGUAGE).build(), DEFAULT_LANGUAGE));

        assertEquals(AdrStatus.ACCEPTED, thrown.status());
    }

    /**
     * A reference-only correction of an accepted decision still travels through {@code reviseText}
     * with every text value unchanged - that path must stay a no-op rather than a rejection.
     */
    @Test
    void updateAcceptsACorrectionThatRestatesTheTextOfAnAcceptedDecisionUnchanged() {
        AdrCode code = add(newAdr()).adr().code();
        Adr accepted = service.accept(PROJECT, code, null, DEFAULT_LANGUAGE).adr();

        Adr updated = update(code, AdrCorrection.builder()
                .name(accepted.name())
                .addressesRequirementCodes(List.of("FR-1"))
                .build()).adr();

        assertEquals(List.of(new RequirementRef(FR_1)), updated.addressesRequirements());
    }

    @Test
    void updateRejectsAnUnknownCode() {
        assertThrows(AdrNotFoundException.class, () -> update(new AdrCode("ADR-9"),
                AdrCorrection.builder().name("Another title").build()));
    }

    // --- usesTerm (kogn-io/arknet#393) -----------------------------------------

    /** The tri-state {@code usesTermCodes} carries: replace, clear, and leave-untouched-when-omitted. */
    @Test
    void updateSetsClearsAndLeavesUsesTermUntouched() {
        AdrDetail added = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, null, null, List.of("TERM-1"), null));

        Adr untouched = update(added.adr().code(),
                AdrCorrection.builder().name("Another title").build()).adr();
        assertEquals(List.of(new TermRef(TERM_1)), untouched.usesTerms());

        Adr cleared = update(added.adr().code(),
                AdrCorrection.builder().usesTermCodes(List.of()).build()).adr();
        assertEquals(List.of(), cleared.usesTerms());
    }

    /**
     * Resolution sits before any write, exactly as for {@code addressesRequirement}/
     * {@code affectsContext} - an unknown term code aborts the whole call rather than leaving a
     * half-linked decision behind.
     */
    @Test
    void addPropagatesTheUsesTermLookupFailureAndWritesNothing() {
        assertThrows(NoSuchElementException.class, () -> add(
                new NewAdr("Title", "Some context here", "Some decision here", null, null,
                        DEFAULT_LANGUAGE, null, null, List.of("TERM-99"), null)));

        assertTrue(list(PROJECT).isEmpty());
    }

    /** {@code usesTerm} is correctable in every status, just like the other three reference lists. */
    @Test
    void updateCorrectsUsesTermOnAnAcceptedDecision() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        Adr updated = update(code,
                AdrCorrection.builder().usesTermCodes(List.of("TERM-1")).build()).adr();

        assertEquals(List.of(new TermRef(TERM_1)), updated.usesTerms());
        assertEquals(AdrStatus.ACCEPTED, updated.status());
    }

    // --- consequence/considered-option correction (kogn-io/arknet#357) --------

    @Test
    void updateAppendsConsequencesInAnyStatusIncludingAccepted() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        Adr updated = update(code, AdrCorrection.builder()
                .newConsequences(List.of(new NewConsequence("Discovered later", ConsequenceType.NEGATIVE)))
                .build()).adr();

        assertEquals(List.of(new Consequence(1, "Discovered later", ConsequenceType.NEGATIVE)),
                updated.consequences());
        assertEquals(AdrStatus.ACCEPTED, updated.status());
    }

    @Test
    void updateAppendsConsideredOptionsInAnyStatusIncludingAccepted() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        Adr updated = update(code, AdrCorrection.builder()
                .newConsideredOptions(List.of(
                        new NewConsideredOption("Discovered option", "Found later", OptionOutcome.REJECTED)))
                .build()).adr();

        assertEquals(List.of(new ConsideredOption(1, "Discovered option", "Found later", OptionOutcome.REJECTED)),
                updated.consideredOptions());
    }

    @Test
    void updateCorrectsAConsequenceWhilePROPOSEDByPosition() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("Draft wording", ConsequenceType.NEUTRAL)), null,
                DEFAULT_LANGUAGE, null, null, null, null));

        Adr updated = update(added.adr().code(), AdrCorrection.builder()
                .consequenceCorrections(List.of(new ConsequenceCorrection(1, "Sharper wording", ConsequenceType.POSITIVE)))
                .build()).adr();

        assertEquals(List.of(new Consequence(1, "Sharper wording", ConsequenceType.POSITIVE)),
                updated.consequences());
    }

    /**
     * Correcting an *existing* consequence's wording in the very language it already carries (this
     * call names no {@code language}, so it resolves to the same {@code DEFAULT_LANGUAGE} the
     * consequence was written under) is locked once the decision is no longer PROPOSED - it is an
     * edit, not a translation, so the new-language exemption does not apply. Mutation test: removing
     * the status guard in {@code Adr#withConsequenceCorrections} turns this into an unexpected pass.
     */
    @Test
    void updateRejectsCorrectingAnExistingConsequenceOnAnAcceptedDecision() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("Draft wording", ConsequenceType.NEUTRAL)), null,
                DEFAULT_LANGUAGE, null, null, null, null));
        service.accept(PROJECT, added.adr().code(), null, DEFAULT_LANGUAGE);

        assertThrows(AdrTextImmutableException.class, () -> update(added.adr().code(), AdrCorrection.builder()
                .consequenceCorrections(List.of(new ConsequenceCorrection(1, "Rewritten", ConsequenceType.POSITIVE)))
                .build()));
    }

    /**
     * kogn-io/arknet#357's follow-up: a consequence correction that writes a language the position
     * never carried is exempt from the status gate, even on an accepted decision - it translates an
     * already-decided consequence, it does not change what was decided. Mutation test: removing
     * {@code AdrService#newLanguageVariantPositions}'s exemption, or the branch in
     * {@code Adr#withConsequenceCorrections} that honours it, turns this into an unexpected throw.
     */
    @Test
    void updateAllowsANewLanguageConsequenceCorrectionOnAnAcceptedDecision() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("Draft wording", ConsequenceType.NEUTRAL)), null,
                DEFAULT_LANGUAGE, null, null, null, null));
        service.accept(PROJECT, added.adr().code(), null, DEFAULT_LANGUAGE);

        Adr updated = update(added.adr().code(), AdrCorrection.builder()
                .consequenceCorrections(
                        List.of(new ConsequenceCorrection(1, "Entwurfsformulierung", ConsequenceType.NEUTRAL)))
                .language("de")
                .build()).adr();

        assertEquals(List.of(new Consequence(1, "Entwurfsformulierung", ConsequenceType.NEUTRAL)),
                updated.consequences());
        assertEquals(AdrStatus.ACCEPTED, updated.status());
    }

    /**
     * The mirror image: writing a new language does not exempt a bundled {@code type} change - a
     * consequence's classification is a judgement about the decision, not a fact of its wording, so
     * it is gated regardless of language.
     */
    @Test
    void updateRejectsAConsequenceTypeChangeOnAnAcceptedDecisionEvenWithANewLanguage() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("Draft wording", ConsequenceType.NEUTRAL)), null,
                DEFAULT_LANGUAGE, null, null, null, null));
        service.accept(PROJECT, added.adr().code(), null, DEFAULT_LANGUAGE);

        assertThrows(AdrTextImmutableException.class, () -> update(added.adr().code(), AdrCorrection.builder()
                .consequenceCorrections(
                        List.of(new ConsequenceCorrection(1, "Entwurfsformulierung", ConsequenceType.POSITIVE)))
                .language("de")
                .build()));
    }

    /** {@code ConsideredOption}'s counterpart of the new-language-consequence-correction pair above. */
    @Test
    void updateAllowsANewLanguageConsideredOptionCorrectionOnAnAcceptedDecision() {
        // CHOSEN, not REJECTED (kogn-io/arknet#427): a lone REJECTED option would refuse the
        // accept() below outright, since ACCEPTED now requires exactly one CHOSEN whenever options
        // exist.
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here", null,
                List.of(new NewConsideredOption("Option A", "Rationale A", OptionOutcome.CHOSEN)),
                DEFAULT_LANGUAGE, null, null, null, null));
        service.accept(PROJECT, added.adr().code(), null, DEFAULT_LANGUAGE);

        Adr updated = update(added.adr().code(), AdrCorrection.builder()
                .consideredOptionCorrections(List.of(
                        new de.hauschel.arknet.adr.domain.ConsideredOptionCorrection(
                                1, "Option A (de)", "Begruendung A", OptionOutcome.CHOSEN)))
                .language("de")
                .build()).adr();

        assertEquals(List.of(new ConsideredOption(1, "Option A (de)", "Begruendung A", OptionOutcome.CHOSEN)),
                updated.consideredOptions());
        assertEquals(AdrStatus.ACCEPTED, updated.status());
    }

    /**
     * The mirror image for {@code ConsideredOption}: writing a new language does not exempt a bundled
     * {@code outcome} change - whether an option was chosen or rejected is a judgement about the
     * decision, not a fact of its wording.
     */
    @Test
    void updateRejectsAnOptionOutcomeChangeOnAnAcceptedDecisionEvenWithANewLanguage() {
        // CHOSEN, not REJECTED, for the same reason as the sibling test above - the correction below
        // then flips it to REJECTED, still the outcome change this test is about.
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here", null,
                List.of(new NewConsideredOption("Option A", "Rationale A", OptionOutcome.CHOSEN)),
                DEFAULT_LANGUAGE, null, null, null, null));
        service.accept(PROJECT, added.adr().code(), null, DEFAULT_LANGUAGE);

        assertThrows(AdrTextImmutableException.class, () -> update(added.adr().code(), AdrCorrection.builder()
                .consideredOptionCorrections(List.of(
                        new de.hauschel.arknet.adr.domain.ConsideredOptionCorrection(
                                1, "Option A (de)", "Begruendung A", OptionOutcome.REJECTED)))
                .language("de")
                .build()));
    }

    @Test
    void updateCorrectionOfAnUnknownConsequencePositionIsRejected() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("Draft wording", ConsequenceType.NEUTRAL)), null,
                DEFAULT_LANGUAGE, null, null, null, null));

        assertThrows(de.hauschel.arknet.adr.domain.ConsequencePositionNotFoundException.class,
                () -> update(added.adr().code(), AdrCorrection.builder()
                        .consequenceCorrections(List.of(new ConsequenceCorrection(9, "x", ConsequenceType.NEUTRAL)))
                        .build()));
    }

    // --- update: removal (kogn-io/arknet#483) --------------------------------

    @Test
    void updateRemovesAConsequenceByPositionAndTheOnesAfterItMoveUp() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("First", ConsequenceType.NEUTRAL),
                        new NewConsequence("Mistaken", ConsequenceType.NEGATIVE),
                        new NewConsequence("Third", ConsequenceType.POSITIVE)),
                null, DEFAULT_LANGUAGE, null, null, null, null));

        Adr updated = update(added.adr().code(), AdrCorrection.builder()
                .removedConsequencePositions(new RemovedPositions(Set.of(2)))
                .build()).adr();

        assertEquals(List.of(
                new Consequence(1, "First", ConsequenceType.NEUTRAL),
                new Consequence(2, "Third", ConsequenceType.POSITIVE)), updated.consequences());
    }

    @Test
    void updateRemovesAConsideredOptionByPosition() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here", null,
                List.of(new NewConsideredOption("Option A", "Rationale A", OptionOutcome.CHOSEN),
                        new NewConsideredOption("Folded-in duplicate", "Recorded by mistake", OptionOutcome.REJECTED)),
                DEFAULT_LANGUAGE, null, null, null, null));

        Adr updated = update(added.adr().code(), AdrCorrection.builder()
                .removedConsideredOptionPositions(new RemovedPositions(Set.of(2)))
                .build()).adr();

        assertEquals(List.of(new ConsideredOption(1, "Option A", "Rationale A", OptionOutcome.CHOSEN)),
                updated.consideredOptions());
    }

    /**
     * The translation exemption the corrections carry does not extend to a removal: even a call that
     * names a language the position never carried is refused once the decision is ACCEPTED, because
     * taking a consequence out is never a translation. Mutation test: routing the removal through the
     * per-position exemption set (as the corrections are) turns this into an unexpected pass.
     */
    @Test
    void updateRejectsRemovingAConsequenceOnAnAcceptedDecisionEvenUnderANewLanguage() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("Draft wording", ConsequenceType.NEUTRAL)), null,
                DEFAULT_LANGUAGE, null, null, null, null));
        service.accept(PROJECT, added.adr().code(), null, DEFAULT_LANGUAGE);

        assertThrows(AdrTextImmutableException.class, () -> update(added.adr().code(), AdrCorrection.builder()
                .removedConsequencePositions(new RemovedPositions(Set.of(1)))
                .language("de")
                .build()));
        assertEquals(1, get(PROJECT, added.adr().code()).orElseThrow().adr().consequences().size());
    }

    @Test
    void updateRefusesToCorrectAndRemoveTheSamePositionInOneCall() {
        assertThrows(IllegalArgumentException.class, () -> AdrCorrection.builder()
                .consequenceCorrections(List.of(new ConsequenceCorrection(2, "Sharper", ConsequenceType.POSITIVE)))
                .removedConsequencePositions(new RemovedPositions(Set.of(2)))
                .build());
    }

    @Test
    void updateRemovalOfAnUnknownConsequencePositionIsRejected() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("Draft wording", ConsequenceType.NEUTRAL)), null,
                DEFAULT_LANGUAGE, null, null, null, null));

        assertThrows(de.hauschel.arknet.adr.domain.ConsequencePositionNotFoundException.class,
                () -> update(added.adr().code(), AdrCorrection.builder()
                        .removedConsequencePositions(new RemovedPositions(Set.of(9)))
                        .build()));
    }

    /**
     * The whole reason the removal travels through the out-port as {@link RemovedPositions} rather
     * than as a shorter list (kogn-io/arknet#483): a position is a consequence's only identity, and
     * the language state the write path keeps per position must follow the surviving consequence to
     * its new number. Position 2 is translated into German, then position 1 is removed - the German
     * variant must now be on record for position 1, and the removed position's state gone. Mutation
     * test: keying {@code AdrService#positionLanguages} by the updated position without the remap
     * leaves the German tag on a position that no longer exists.
     */
    @Test
    void updateMovesAPositionsLanguageStateAlongWithItsSurvivingConsequence() {
        AdrDetail added = add(new NewAdr("Title", "Some context here", "Some decision here",
                List.of(new NewConsequence("First", ConsequenceType.NEUTRAL),
                        new NewConsequence("Second", ConsequenceType.POSITIVE)),
                null, DEFAULT_LANGUAGE, null, null, null, null));
        AdrCode code = added.adr().code();
        update(code, AdrCorrection.builder()
                .consequenceCorrections(List.of(new ConsequenceCorrection(2, "Zweite", ConsequenceType.POSITIVE)))
                .language("de")
                .build());
        assertEquals(Map.of(1, Set.of("en"), 2, Set.of("en", "de")),
                repository.findCurrentByCode(PROJECT, code, DEFAULT_LANGUAGE).orElseThrow()
                        .consequenceLanguagesByPosition());

        update(code, AdrCorrection.builder()
                .removedConsequencePositions(new RemovedPositions(Set.of(1)))
                .build());

        AdrRepository.CurrentAdr current = repository.findCurrentByCode(PROJECT, code, DEFAULT_LANGUAGE).orElseThrow();
        assertEquals(Map.of(1, Set.of("en", "de")), current.consequenceLanguagesByPosition());
        assertEquals(Map.of(1, "de"), current.consequenceLanguageByPosition());
    }

    // --- delete ---------------------------------------------------------------

    @Test
    void deleteRemovesAProposedDecision() {
        AdrCode code = add(newAdr()).adr().code();

        service.delete(PROJECT, code);

        assertTrue(get(PROJECT, code).isEmpty());
        assertTrue(list(PROJECT).isEmpty());
    }

    @Test
    void deleteRejectsAnUnknownCode() {
        assertThrows(AdrNotFoundException.class, () -> service.delete(PROJECT, new AdrCode("ADR-9")));
    }

    @Test
    void deleteRefusesAnAcceptedDecisionAndPointsAtSupersede() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);

        AdrNotDeletableException thrown =
                assertThrows(AdrNotDeletableException.class, () -> service.delete(PROJECT, code));

        assertEquals(AdrStatus.ACCEPTED, thrown.status());
        assertTrue(thrown.getMessage().contains("adr_supersede"), thrown.getMessage());
        assertTrue(get(PROJECT, code).isPresent(), "a refused delete must leave the decision");
    }

    @Test
    void deleteRefusesARejectedDecisionBecauseTurningAnOptionDownIsItselfADecision() {
        AdrCode code = add(newAdr()).adr().code();
        service.reject(PROJECT, code, null, DEFAULT_LANGUAGE);

        AdrNotDeletableException thrown =
                assertThrows(AdrNotDeletableException.class, () -> service.delete(PROJECT, code));

        assertEquals(AdrStatus.REJECTED, thrown.status());
        assertTrue(thrown.getMessage().contains("considered and turned down"), thrown.getMessage());
        assertTrue(get(PROJECT, code).isPresent(), "a refused delete must leave the decision");
    }

    @Test
    void deleteRefusesADeprecatedDecision() {
        AdrCode code = add(newAdr()).adr().code();
        service.accept(PROJECT, code, null, DEFAULT_LANGUAGE);
        service.deprecate(PROJECT, code, DEFAULT_LANGUAGE);

        AdrNotDeletableException thrown =
                assertThrows(AdrNotDeletableException.class, () -> service.delete(PROJECT, code));

        assertEquals(AdrStatus.DEPRECATED, thrown.status());
        assertTrue(get(PROJECT, code).isPresent(), "a refused delete must leave the decision");
    }

    /**
     * Since kogn-io/arknet#357 a decision reachable through {@code adr_supersede} is never PROPOSED
     * any more when this check runs - the superseding decision must already be ACCEPTED, and the
     * superseded one becomes SUPERSEDED in the very same write - so the status check in
     * {@link AdrService#delete} refuses it first. What is left reachable for a PROPOSED decision is
     * store-first data: a legacy {@code arkarch:supersedes} edge naming it, written before
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
        AdrCode superseded = add(newAdr()).adr().code();
        AdrCode successor = add(newAdr()).adr().code();
        repository.seedLegacySupersession(PROJECT, successor, superseded);

        AdrReferencedException thrown =
                assertThrows(AdrReferencedException.class, () -> service.delete(PROJECT, superseded));

        assertEquals(List.of(new AdrReferencedException.Reference(successor,
                AdrReferencedException.SUPERSEDED_BY)), thrown.references());
        assertTrue(thrown.getMessage().contains(successor.value()), thrown.getMessage());
        assertTrue(get(PROJECT, superseded).isPresent(), "a refused delete must leave the decision");
    }

    @Test
    void deleteRefusesADecisionAnotherOneIsRelatedTo() {
        AdrCode peer = add(newAdr()).adr().code();
        add(new NewAdr("Title", "Some context here", "Some decision here",
                null, null, DEFAULT_LANGUAGE, null, null, null, List.of(peer.value())));

        AdrReferencedException thrown =
                assertThrows(AdrReferencedException.class, () -> service.delete(PROJECT, peer));

        assertEquals(List.of(new AdrReferencedException.Reference(new AdrCode("ADR-2"),
                AdrReferencedException.RELATED_TO)), thrown.references());
        assertTrue(thrown.getMessage().contains("adr_update"), thrown.getMessage());
        assertTrue(get(PROJECT, peer).isPresent(), "a refused delete must leave the decision");
    }

    @Test
    void deleteAllowsADecisionThatOnlyPointsAtOthers() {
        AdrCode peer = add(newAdr()).adr().code();
        AdrCode naming = add(new NewAdr("Title", "Some context here",
                "Some decision here", null, null, DEFAULT_LANGUAGE, null, null, null, List.of(peer.value())))
                .adr().code();

        service.delete(PROJECT, naming);

        assertTrue(get(PROJECT, naming).isEmpty());
        assertTrue(get(PROJECT, peer).isPresent());
    }

    @Test
    void deleteDoesNotFreeTheCodeForTheNextDecision() {
        add(newAdr());
        AdrCode second = add(newAdr()).adr().code();
        assertEquals(new AdrCode("ADR-2"), second);

        service.delete(PROJECT, second);
        AdrCode next = add(newAdr()).adr().code();

        assertEquals(new AdrCode("ADR-3"), next);
    }

    @Test
    void retainedCodesDoNotLeakIntoAnotherProjectsNumbering() {
        ProjectId other = new ProjectId("other-project");
        add(newAdr());
        service.delete(PROJECT, new AdrCode("ADR-1"));

        assertEquals(new AdrCode("ADR-1"), service.add(other, newAdr(), DEFAULT_LANGUAGE).adr().code());
    }

    private static NewAdr newAdr() {
        return new NewAdr("Use an embedded triple store",
                "The model has to live somewhere a single-user client can reach without a server.",
                "Use kognio-rdf as the embedded RDF substrate behind an out-port.",
                null, null, DEFAULT_LANGUAGE, null, null, null, null);
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
