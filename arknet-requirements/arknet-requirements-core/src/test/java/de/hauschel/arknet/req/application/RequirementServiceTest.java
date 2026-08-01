// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.domain.MissingAcceptanceCriteriaException;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Policy tests for {@link RequirementService}: identity minting, code assignment, listing,
 * lookup, status-transition and term-linking rules, exercised against an in-memory fake
 * repository and a deterministic fake {@link ResourceIdFactory}.
 */
class RequirementServiceTest {

    private static final ProjectId WS = new ProjectId("test-project");
    private static final ResourceId TERM_1 =
            ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final ResourceId TERM_2 =
            ResourceId.of("https://w3id.org/arknet/id/term-2");

    private InMemoryRequirementRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private InMemoryTermLookup termLookup;
    private FakeRequirementSchemaSource schemaSource;
    private RequirementService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRequirementRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        termLookup = new InMemoryTermLookup();
        termLookup.register("TERM-1", TERM_1);
        termLookup.register("TERM-2", TERM_2);
        schemaSource = new FakeRequirementSchemaSource();
        service = new RequirementService(repository, resourceIdFactory, termLookup, schemaSource);
    }

    @Test
    void addAssignsFirstFunctionalCode() {
        Requirement added = service.add(WS, new NewRequirement("User can log in",
                "The system shall let a registered user authenticate.", RequirementType.FUNCTIONAL,
                null, null, null, List.of("Done when it works")));

        assertEquals(new RequirementCode("FR-1"), added.code());
    }

    @Test
    void addSetsProposedStatusByDefault() {
        Requirement added = service.add(WS, new NewRequirement("User can log in",
                "The system shall let a registered user authenticate.", RequirementType.FUNCTIONAL,
                null, null, null, List.of("Done when it works")));

        assertEquals(RequirementStatus.PROPOSED, added.status());
    }

    /**
     * Every field {@code add} was given comes back unchanged on the returned {@link Requirement}
     * and is what a subsequent read from the repository sees too - one fact (faithful roundtrip
     * of the supplied fields), asserted from both ends.
     */
    @Test
    void addPersistsAllSuppliedFields() {
        Requirement added = service.add(WS, new NewRequirement("User can log in",
                "The system shall let a registered user authenticate.", RequirementType.FUNCTIONAL,
                null, null, null, List.of("Done when it works")));

        assertEquals("User can log in", added.title());
        assertEquals("The system shall let a registered user authenticate.", added.description());
        assertEquals(RequirementType.FUNCTIONAL, added.type());
        assertEquals(List.of("Done when it works"), added.acceptanceCriteria());
        assertEquals(added, repository.findByCode(WS, added.code()).orElseThrow());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        Requirement first = service.add(WS, newFunctionalRequirement());
        Requirement second = service.add(WS, newFunctionalRequirement());

        assertNotEquals(first.id(), second.id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addAssignsNfrPrefixForNonFunctional() {
        Requirement added = service.add(WS, new NewRequirement("Page loads < 200ms",
                "95% of page loads shall complete in under 200ms.", RequirementType.NON_FUNCTIONAL,
                null, null, null, List.of("Done when it works")));

        assertEquals(new RequirementCode("NFR-1"), added.code());
    }

    @Test
    void addCarriesPriorityMotivatedByAndQualityCategoryThrough() {
        Requirement added = service.add(WS, new NewRequirement("Page loads < 200ms",
                "95% of page loads shall complete in under 200ms.", RequirementType.NON_FUNCTIONAL,
                Priority.MUST_HAVE, "https://w3id.org/arknet/model/goal/fast-ux", "performance", List.of("Done when it works")));

        assertEquals(Priority.MUST_HAVE, added.priority());
        assertEquals("https://w3id.org/arknet/model/goal/fast-ux", added.motivatedBy());
        assertEquals("performance", added.qualityCategory());
        assertEquals(added, repository.findByCode(WS, added.code()).orElseThrow());
    }

    @Test
    void addNumbersRunPerTypeIndependently() {
        RequirementCode fr1 = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"))).code();
        RequirementCode nfr1 = service.add(WS,
                new NewRequirement("b", "desc b", RequirementType.NON_FUNCTIONAL, null, null, null, List.of("Done when it works"))).code();
        RequirementCode fr2 = service.add(WS,
                new NewRequirement("c", "desc c", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"))).code();

        assertEquals(new RequirementCode("FR-1"), fr1);
        assertEquals(new RequirementCode("NFR-1"), nfr1);
        assertEquals(new RequirementCode("FR-2"), fr2);
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works")));

        Requirement inOther = service.add(other,
                new NewRequirement("b", "desc b", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works")));

        assertEquals(new RequirementCode("FR-1"), inOther.code());
        assertTrue(service.list(other).stream().allMatch(r -> r.title().equals("b")));
        assertEquals(1, service.list(WS).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works")));
        service.add(WS, new NewRequirement("b", "desc b", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works")));

        List<Requirement> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals("a", all.get(0).title());
        assertEquals("b", all.get(1).title());
    }

    @Test
    void getReturnsPersistedRequirement() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"))).code();

        assertTrue(service.get(WS, code).isPresent());
        assertEquals("a", service.get(WS, code).orElseThrow().title());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new RequirementCode("FR-99")).isPresent());
    }

    @Test
    void acceptTransitionsProposedToAccepted() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"))).code();

        Requirement accepted = service.accept(WS, code);

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals("desc a", accepted.description());
        assertEquals(RequirementStatus.ACCEPTED, repository.findByCode(WS, code).orElseThrow().status());
    }

    @Test
    void acceptPreservesPriorityMotivatedByAndQualityCategory() {
        RequirementCode code = service.add(WS, new NewRequirement("a", "desc a", RequirementType.NON_FUNCTIONAL,
                Priority.COULD_HAVE, "https://w3id.org/arknet/model/goal/g", "security", List.of("Done when it works"))).code();

        Requirement accepted = service.accept(WS, code);

        assertEquals(Priority.COULD_HAVE, accepted.priority());
        assertEquals("https://w3id.org/arknet/model/goal/g", accepted.motivatedBy());
        assertEquals("security", accepted.qualityCategory());
    }

    @Test
    void acceptIsIdempotentWhenAlreadyAccepted() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"))).code();
        service.accept(WS, code);

        Requirement result = service.accept(WS, code);

        assertEquals(RequirementStatus.ACCEPTED, result.status());
    }

    @Test
    void acceptThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.accept(WS, new RequirementCode("FR-42")));

        assertSame(WS, ex.projectId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    @Test
    void updateChangesTitleDescriptionAndAcceptanceCriteria() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();

        Requirement updated = service.update(WS, code, "New title", "New description",
                List.of("New done-when criterion"), null);

        assertEquals("New title", updated.title());
        assertEquals("New description", updated.description());
        assertEquals(List.of("New done-when criterion"), updated.acceptanceCriteria());
        assertEquals(updated, service.get(WS, code).orElseThrow());
    }

    @Test
    void updateWithNullFieldsLeavesThemUnchanged() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();

        Requirement updated = service.update(WS, code, null, "New description", null, null);

        assertEquals("User can log in", updated.title());
        assertEquals("New description", updated.description());
        assertEquals(List.of("Done when it works"), updated.acceptanceCriteria());
    }

    @Test
    void updateWithAllNullFieldsIsANoOp() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();
        Requirement before = service.get(WS, code).orElseThrow();

        Requirement result = service.update(WS, code, null, null, null, null);

        assertEquals(before, result);
    }

    @Test
    void updatePreservesStatusLinkedTermsAndOtherFields() {
        RequirementCode code = service.add(WS, new NewRequirement("a", "desc a", RequirementType.NON_FUNCTIONAL,
                Priority.COULD_HAVE, "https://w3id.org/arknet/model/goal/g", "security",
                List.of("Done when it works"))).code();
        service.accept(WS, code);
        service.linkTerm(WS, code, "TERM-1");

        Requirement updated = service.update(WS, code, "New title", null, null, null);

        assertEquals(RequirementStatus.ACCEPTED, updated.status());
        assertEquals(List.of(new TermRef(TERM_1)), updated.usesTerms());
        assertEquals(Priority.COULD_HAVE, updated.priority());
        assertEquals("https://w3id.org/arknet/model/goal/g", updated.motivatedBy());
        assertEquals("security", updated.qualityCategory());
    }

    /**
     * The concrete case - a register audited as uniformly {@code MUST_HAVE} is
     * corrected down to {@code SHOULD_HAVE}, keeping the requirement's code and thus every
     * {@code usesTerm}/{@code realises} reference into it intact.
     */
    @Test
    void updateChangesThePriorityWithoutTouchingAnyOtherField() {
        RequirementCode code = service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL,
                Priority.MUST_HAVE, null, null, List.of("Done when it works"))).code();

        Requirement updated = service.update(WS, code, null, null, null, Priority.SHOULD_HAVE);

        assertEquals(Priority.SHOULD_HAVE, updated.priority());
        assertEquals("a", updated.title());
        assertEquals("desc a", updated.description());
        assertEquals(List.of("Done when it works"), updated.acceptanceCriteria());
        assertEquals(updated, service.get(WS, code).orElseThrow());
    }

    /** A requirement added without a priority can have one set after the fact. */
    @Test
    void updateCanSetAPriorityThatWasNeverSet() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();
        assertNull(service.get(WS, code).orElseThrow().priority());

        Requirement updated = service.update(WS, code, null, null, null, Priority.COULD_HAVE);

        assertEquals(Priority.COULD_HAVE, updated.priority());
    }

    /**
     * {@code null} means "unchanged", not "remove": correcting only the title must not silently
     * strip an already-set priority (removing one is deliberately out of this port's scope).
     */
    @Test
    void updateWithANullPriorityDoesNotClearAnAlreadySetOne() {
        RequirementCode code = service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL,
                Priority.MUST_HAVE, null, null, List.of("Done when it works"))).code();

        Requirement updated = service.update(WS, code, "New title", null, null, null);

        assertEquals(Priority.MUST_HAVE, updated.priority());
    }

    @Test
    void updateRejectsABlankTitleViaTheDomainInvariant() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();

        assertThrows(IllegalArgumentException.class, () -> service.update(WS, code, " ", null, null, null));
    }

    @Test
    void updateThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.update(WS, new RequirementCode("FR-42"), "New title", null, null, null));

        assertSame(WS, ex.projectId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    @Test
    void addStartsWithoutLinkedTerms() {
        Requirement added = service.add(WS, newFunctionalRequirement());

        assertEquals(List.of(), added.usesTerms());
    }

    @Test
    void linkTermAddsTheTermToTheRequirement() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();

        Requirement linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1)), linked.usesTerms());
        assertEquals(List.of(new TermRef(TERM_1)), service.get(WS, code).orElseThrow().usesTerms());
    }

    @Test
    void linkTermAppendsToAlreadyLinkedTerms() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();
        service.linkTerm(WS, code, "TERM-1");

        Requirement linked = service.linkTerm(WS, code, "TERM-2");

        assertEquals(List.of(new TermRef(TERM_1), new TermRef(TERM_2)), linked.usesTerms());
    }

    @Test
    void linkingTheSameTermTwiceIsANoOp() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();
        service.linkTerm(WS, code, "TERM-1");

        Requirement linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1)), linked.usesTerms());
    }

    @Test
    void linkTermThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.linkTerm(WS, new RequirementCode("FR-42"), "TERM-1"));

        assertSame(WS, ex.projectId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    /**
     * Resolution of the human-typed term code happens here, via {@link InMemoryTermLookup}
     * - not in the out-adapter's write path any more. A lookup failure must
     * propagate unchanged and leave the requirement untouched.
     */
    @Test
    void linkTermPropagatesTheLookupFailureForAnUnknownTermCodeAndLinksNothing() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();

        assertThrows(NoSuchElementException.class, () -> service.linkTerm(WS, code, "TERM-99"));

        assertEquals(List.of(), service.get(WS, code).orElseThrow().usesTerms());
    }

    /**
     * Regression guard for the replace-by-identity write path: the out-adapter persists a
     * requirement by wiping and re-writing its triples, so a status change must carry the
     * linked terms along rather than silently dropping them.
     */
    @Test
    void acceptPreservesLinkedTerms() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();
        service.linkTerm(WS, code, "TERM-1");

        Requirement accepted = service.accept(WS, code);

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals(List.of(new TermRef(TERM_1)), accepted.usesTerms());
        assertEquals(List.of(new TermRef(TERM_1)), service.get(WS, code).orElseThrow().usesTerms());
    }

    /**
     * Same regression as {@link #acceptPreservesLinkedTerms}, for the mandatory
     * acceptance criteria: accepting a requirement must not drop them either.
     */
    @Test
    void acceptPreservesAcceptanceCriteria() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();

        Requirement accepted = service.accept(WS, code);

        assertEquals(List.of("Done when it works"), accepted.acceptanceCriteria());
        assertEquals(List.of("Done when it works"), service.get(WS, code).orElseThrow().acceptanceCriteria());
    }

    /**
     * Same regression, exercised via {@code linkTerm}'s own replace-by-identity write.
     */
    @Test
    void linkTermPreservesAcceptanceCriteria() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();

        Requirement linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of("Done when it works"), linked.acceptanceCriteria());
    }

    /**
     * Regression for issue #157: a requirement that predates the mandatory acceptance-criterion
     * invariant reads back with the read-time legacy placeholder standing in for its
     * {@code acceptanceCriteria} (see {@link
     * de.hauschel.arknet.req.application.port.out.RequirementRepository.CurrentRequirement#acceptanceCriteriaIsSynthesized()}).
     * {@code accept} never supplies a replacement, so writing it through must reject instead of
     * turning that placeholder into a real, persisted literal - and must leave the requirement's
     * status untouched, exactly as if the write had never been attempted.
     */
    @Test
    void acceptRejectsALegacyRequirementInsteadOfPersistingThePlaceholder() {
        RequirementCode code = givenLegacyRequirement();

        MissingAcceptanceCriteriaException ex = assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.accept(WS, code));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.requirementCode());
        assertEquals(RequirementStatus.PROPOSED, service.get(WS, code).orElseThrow().status());
    }

    /** Same regression as {@link #acceptRejectsALegacyRequirementInsteadOfPersistingThePlaceholder}, via {@code linkTerm}. */
    @Test
    void linkTermRejectsALegacyRequirementInsteadOfPersistingThePlaceholder() {
        RequirementCode code = givenLegacyRequirement();

        MissingAcceptanceCriteriaException ex = assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.linkTerm(WS, code, "TERM-1"));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.requirementCode());
        assertEquals(List.of(), service.get(WS, code).orElseThrow().usesTerms());
    }

    /**
     * Same regression as {@link #acceptRejectsALegacyRequirementInsteadOfPersistingThePlaceholder},
     * via {@code update} - but only when the caller leaves {@code acceptanceCriteria} {@code null}
     * ("unchanged"), the same argument that used to carry the placeholder straight into the store.
     */
    @Test
    void updateRejectsALegacyRequirementWhenAcceptanceCriteriaAreLeftUnchanged() {
        RequirementCode code = givenLegacyRequirement();

        MissingAcceptanceCriteriaException ex = assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.update(WS, code, "New title", null, null, null));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.requirementCode());
        assertEquals("legacy title", service.get(WS, code).orElseThrow().title());
    }

    /**
     * The escape hatch: a caller closing the gap by supplying real acceptance criteria to
     * {@code update} must succeed - the guard only blocks a write that would carry the
     * placeholder forward unchanged, not one that explicitly replaces it.
     */
    @Test
    void updateAcceptsALegacyRequirementWhenExplicitAcceptanceCriteriaAreSupplied() {
        RequirementCode code = givenLegacyRequirement();

        Requirement updated = service.update(WS, code, null, null, List.of("Real done-when criterion"), null);

        assertEquals(List.of("Real done-when criterion"), updated.acceptanceCriteria());
        assertEquals(List.of("Real done-when criterion"), service.get(WS, code).orElseThrow().acceptanceCriteria());
    }

    /**
     * Once real acceptance criteria have been supplied, the requirement is no longer legacy: a
     * subsequent {@code accept} (which supplies no replacement of its own) must succeed instead of
     * throwing again.
     */
    @Test
    void acceptSucceedsOnceALegacyRequirementsAcceptanceCriteriaHaveBeenSupplied() {
        RequirementCode code = givenLegacyRequirement();
        service.update(WS, code, null, null, List.of("Real done-when criterion"), null);

        Requirement accepted = service.accept(WS, code);

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals(List.of("Real done-when criterion"), accepted.acceptanceCriteria());
    }

    /**
     * Stores a requirement the way {@code repository.createLegacy} models a pre-invariant
     * requirement: some non-blank text stands in for {@code acceptanceCriteria} ({@link
     * Requirement}'s constructor rejects an empty list unconditionally, so the domain object
     * itself can never represent "no criteria at all"), but {@link
     * InMemoryRequirementRepository#createLegacy} marks the identity so {@link
     * RequirementRepository.CurrentRequirement#acceptanceCriteriaIsSynthesized()} reports
     * {@code true} for it - mirroring the real adapter's structural signal.
     */
    private RequirementCode givenLegacyRequirement() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement legacy = new Requirement(
                new RequirementId(resourceIdFactory.newId()), code, "legacy title",
                "A requirement predating the acceptance-criterion invariant.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(),
                List.of("(legacy placeholder - no acceptance criterion on record)"));
        repository.createLegacy(WS, legacy);
        return code;
    }

    /**
     * A sibling bounded context's driving adapter resolves opaque requirement
     * identities back to their identity and business code (e.g. to render {@code FR-N} for
     * display) - in one batch, not per-id.
     */
    @Test
    void resolveExistingResolvesKnownIdentitiesInOneBatch() {
        Requirement first = service.add(WS, newFunctionalRequirement());
        Requirement second = service.add(WS, newFunctionalRequirement());

        List<ResolveRequirements.ResolvedRequirement> resolved =
                service.resolveExisting(WS, first.id().value(), second.id().value());

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveRequirements.ResolvedRequirement(first.id().value(), first.code())));
        assertTrue(
                resolved.contains(new ResolveRequirements.ResolvedRequirement(second.id().value(), second.code())));
    }

    /**
     * The port never rejects an unresolvable id - it simply omits it from the result, so the
     * caller (not this port) decides what "missing" means for its own display.
     */
    @Test
    void resolveExistingSilentlyOmitsUnknownIdentities() {
        Requirement known = service.add(WS, newFunctionalRequirement());
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/does-not-exist");

        List<ResolveRequirements.ResolvedRequirement> resolved =
                service.resolveExisting(WS, known.id().value(), unknown);

        assertEquals(List.of(new ResolveRequirements.ResolvedRequirement(known.id().value(), known.code())),
                resolved);
    }

    @Test
    void resolveExistingWithNoIdsReturnsAnEmptyList() {
        assertEquals(List.of(), service.resolveExisting(WS));
    }

    @Test
    void resolveExistingIsScopedPerProject() {
        Requirement inWs = service.add(WS, newFunctionalRequirement());
        ProjectId other = new ProjectId("other");

        assertEquals(List.of(), service.resolveExisting(other, inWs.id().value()));
    }

    /**
     * {@code schema()} is pure delegation to the {@link RequirementSchemaSource}
     * driven port - the service adds no policy of its own, only the seam between the driving
     * and driven port.
     */
    @Test
    void schemaDelegatesToTheSchemaSource() {
        List<RequirementSchemaTerm> result = service.schema();

        assertEquals(schemaSource.terms(), result);
        assertEquals(1, schemaSource.callCount());
    }

    private static NewRequirement newFunctionalRequirement() {
        return new NewRequirement("User can log in", "The system shall let a registered user authenticate.",
                RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"));
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

    /**
     * Fake {@link RequirementSchemaSource}: hands back a fixed, canned list of terms and counts
     * its own invocations, so the test can pin "schema() is exactly one delegating call" without
     * a mocking framework.
     */
    private static final class FakeRequirementSchemaSource implements RequirementSchemaSource {

        private final List<RequirementSchemaTerm> terms =
                List.of(new RequirementSchemaTerm("Priority", "Priorisierung nach MoSCoW.",
                        List.of("MUST_HAVE", "SHOULD_HAVE", "COULD_HAVE", "WONT_HAVE")));
        private int calls;

        @Override
        public List<RequirementSchemaTerm> schema() {
            calls++;
            return terms;
        }

        List<RequirementSchemaTerm> terms() {
            return terms;
        }

        int callCount() {
            return calls;
        }
    }
}
