// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

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

import de.hauschel.arknet.kernel.MissingDefaultLanguageException;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.StepRealisesPatch;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.StepPositionNotFoundException;
import de.hauschel.arknet.uc.domain.StepTextPatch;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Policy tests for {@link UseCaseService}: opaque identity minting, code assignment, listing,
 * lookup and reference resolution, exercised against an in-memory fake repository, deterministic
 * fake {@link ResourceIdFactory} and fake {@code ActorLookup}/{@code RequirementLookup}.
 */
class UseCaseServiceTest {

    private static final ProjectId WS = new ProjectId("test-project");
    /**
     * A project default language for tests that do not themselves exercise issue #258's
     * language-resolution policy - passed explicitly so a {@code null} {@code language} argument
     * in a fixture (e.g. {@link #newUseCase(String)}) still resolves instead of throwing.
     */
    private static final String DEFAULT_LANGUAGE = "en";

    private static final ResourceId CUSTOMER_ID = ResourceId.of("https://w3id.org/arknet/id/actor-customer");
    private static final ResourceId PAYMENT_PROVIDER_ID =
            ResourceId.of("https://w3id.org/arknet/id/actor-payment-provider");
    private static final ResourceId FR5_ID = ResourceId.of("https://w3id.org/arknet/id/req-fr5");

    private InMemoryUseCaseRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private InMemoryRequirementLookup requirementLookup;
    private InMemoryActorLookup actorLookup;
    private UseCaseService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryUseCaseRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        requirementLookup = new InMemoryRequirementLookup();
        actorLookup = new InMemoryActorLookup();
        actorLookup.register("Customer", CUSTOMER_ID);
        actorLookup.register("PaymentProvider", PAYMENT_PROVIDER_ID);
        requirementLookup.register("FR5", FR5_ID);
        service = new UseCaseService(repository, resourceIdFactory, requirementLookup, actorLookup);
    }

    private static NewUseCase newUseCase(String title) {
        return new NewUseCase(title, "goal of " + title, null, null, "Customer",
                List.of(), null, null, List.of(new NewStep(1, "do something", List.of())), List.of(), null);
    }

    @Test
    void addAssignsFirstCode() {
        UseCase added = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE);

        assertEquals(new UseCaseCode("UC1"), added.code());
        assertEquals("Place order", added.title());
        assertEquals(added, repository.findByCode(WS, added.code(), null).orElseThrow());
    }

    /**
     * Issue #258, decision 2: a write without an explicit {@code language} falls back to the
     * target project's configured {@code defaultLanguage} instead of writing an untagged literal.
     */
    @Test
    void addWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), "de").code();

        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.titleLanguage());
        assertEquals("de", current.goalLanguage());
    }

    /**
     * Issue #258, decision 1: a write without an explicit {@code language}, targeting a project
     * with no configured default either, is rejected instead of silently writing an untagged
     * literal - and nothing is persisted.
     */
    @Test
    void addWithoutLanguageAndWithoutAProjectDefaultIsRejected() {
        assertThrows(MissingDefaultLanguageException.class, () -> service.add(WS, newUseCase("Place order"), null));

        assertEquals(List.of(), service.list(WS));
    }

    /** Mirrors {@link #addWithoutLanguageFallsBackToTheProjectsDefaultLanguage}, for {@code update}. */
    @Test
    void updateWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        service.update(WS, code, "New title", null, null, null, null, null, null, null, null, null, "de");

        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.titleLanguage());
    }

    /** Mirrors {@link #addWithoutLanguageAndWithoutAProjectDefaultIsRejected}, for {@code update}. */
    @Test
    void updateWithoutLanguageAndWithoutAProjectDefaultIsRejected() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        assertThrows(MissingDefaultLanguageException.class, () -> service.update(
                WS, code, "New title", null, null, null, null, null, null, null, null, null, null));

        assertEquals("Place order", service.get(WS, code, null).orElseThrow().title());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        UseCase first = service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE);
        UseCase second = service.add(WS, newUseCase("b"), DEFAULT_LANGUAGE);

        assertNotEquals(first.id(), second.id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addResolvesActorAndRequirementReferencesViaTheLookupPorts() {
        NewUseCase command = new NewUseCase("Place order", "Customer places an order", "Webshop",
                "Customer opens the cart", "Customer",
                List.of("PaymentProvider"), "Customer is logged in", "Order is recorded",
                List.of(new NewStep(1, "select items", List.of("FR5")),
                        new NewStep(2, "confirm", List.of())),
                List.of("2a. Payment declined -> abort"), null);

        UseCase added = service.add(WS, command, DEFAULT_LANGUAGE);

        assertEquals("Webshop", added.scope());
        assertEquals("Customer opens the cart", added.trigger());
        assertEquals(new ActorRef(CUSTOMER_ID), added.primaryActor());
        assertEquals(List.of(new ActorRef(PAYMENT_PROVIDER_ID)), added.supportingActors());
        assertEquals("Customer is logged in", added.precondition());
        assertEquals("Order is recorded", added.postcondition());
        assertEquals(2, added.steps().size());
        assertEquals(List.of(new RequirementRef(FR5_ID)), added.steps().get(0).realises());
        assertEquals(List.of("2a. Payment declined -> abort"), added.extensions());
    }

    @Test
    void addPropagatesAnUnknownActorReferenceFromTheLookupPort() {
        NewUseCase command = new NewUseCase("Broken", "goal", null, null, "Unknown",
                List.of(), null, null, List.of(new NewStep(1, "do something", List.of())), List.of(), null);

        assertThrows(NoSuchElementException.class, () -> service.add(WS, command, DEFAULT_LANGUAGE));
        assertTrue(service.list(WS).isEmpty());
    }

    @Test
    void addPropagatesAnUnknownRequirementReferenceFromTheLookupPort() {
        NewUseCase command = new NewUseCase("Broken", "goal", null, null, "Customer",
                List.of(), null, null,
                List.of(new NewStep(1, "do something", List.of("FR-UNKNOWN"))), List.of(), null);

        assertThrows(NoSuchElementException.class, () -> service.add(WS, command, DEFAULT_LANGUAGE));
        assertTrue(service.list(WS).isEmpty());
    }

    @Test
    void addNumbersRunningPerProject() {
        assertEquals(new UseCaseCode("UC1"), service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE).code());
        assertEquals(new UseCaseCode("UC2"), service.add(WS, newUseCase("b"), DEFAULT_LANGUAGE).code());
        assertEquals(new UseCaseCode("UC3"), service.add(WS, newUseCase("c"), DEFAULT_LANGUAGE).code());
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE);

        UseCase inOther = service.add(other, newUseCase("b"), DEFAULT_LANGUAGE);

        assertEquals(new UseCaseCode("UC1"), inOther.code());
        assertEquals(1, service.list(other).size());
        assertEquals(1, service.list(WS).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE);
        service.add(WS, newUseCase("b"), DEFAULT_LANGUAGE);

        List<UseCase> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals("a", all.get(0).title());
        assertEquals("b", all.get(1).title());
    }

    @Test
    void getReturnsPersistedUseCase() {
        UseCaseCode code = service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE).code();

        assertTrue(service.get(WS, code, null).isPresent());
        assertEquals("a", service.get(WS, code, null).orElseThrow().title());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new UseCaseCode("UC99"), null).isPresent());
    }

    @Test
    void addGetListRoundtrip() {
        UseCase added = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE);

        UseCase fetched = service.get(WS, added.code(), null).orElseThrow();

        assertEquals(added, fetched);
        assertTrue(service.list(WS).contains(added));
    }

    @Test
    void updateChangesGoalLevelFields() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, "New title", "New goal", "New scope", "New trigger",
                "New precondition", "New postcondition", null, null, null, null, DEFAULT_LANGUAGE);

        assertEquals("New title", updated.title());
        assertEquals("New goal", updated.goal());
        assertEquals("New scope", updated.scope());
        assertEquals("New trigger", updated.trigger());
        assertEquals("New precondition", updated.precondition());
        assertEquals("New postcondition", updated.postcondition());
        assertEquals(updated, service.get(WS, code, null).orElseThrow());
    }

    @Test
    void updateWithNullFieldsLeavesThemUnchanged() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(
                WS, code, null, "New goal", null, null, null, null, null, null, null, null, DEFAULT_LANGUAGE);

        assertEquals("Place order", updated.title());
        assertEquals("New goal", updated.goal());
    }

    @Test
    void updateWithEverythingOmittedIsANoOp() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        UseCase before = service.get(WS, code, null).orElseThrow();

        UseCase result = service.update(
                WS, code, null, null, null, null, null, null, null, null, null, null, DEFAULT_LANGUAGE);

        assertEquals(before, result);
    }

    @Test
    void updateReplacesExtensionsWholesale() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, null, null, null, null, null, null,
                List.of("2a. Payment declined -> abort"), null, null, null, DEFAULT_LANGUAGE);

        assertEquals(List.of("2a. Payment declined -> abort"), updated.extensions());
    }

    /**
     * {@code stableExtensionPrefixLength} (passed on to
     * {@link UseCaseRepository#compareAndUpdate}, see its own javadoc) must cover every position
     * for an update that only edits content in place - here, translating the single position whose
     * content changed. The real out-adapter relies on this to know a prior other-language variant
     * at that position is still safe to preserve; the real out-adapter's {@code
     * KognioRdfUseCaseRepositoryMultilingualTest
     * #compareAndUpdateWithAnInsertedExtensionDoesNotMisattachAPriorPositionsOtherLanguageVariant}
     * covers the store-level consequence, this covers the service's own computation of the value.
     */
    @Test
    void updateThatOnlyTranslatesTheTrailingExtensionIsNotFlaggedAsRestructured() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        service.update(WS, code, null, null, null, null, null, null,
                List.of("2a. A", "3a. B"), null, null, null, DEFAULT_LANGUAGE);

        service.update(WS, code, null, null, null, null, null, null,
                List.of("2a. A", "3a. B (de)"), null, null, "de", DEFAULT_LANGUAGE);

        assertEquals(2, repository.lastStableExtensionPrefixLength());
    }

    /**
     * Counterpart to {@link #updateThatOnlyTranslatesTheTrailingExtensionIsNotFlaggedAsRestructured}:
     * inserting a new extension ahead of an existing position shifts everything after it, so more
     * than one position diverges once the longest common leading prefix is factored out - the value
     * must come out as exactly that prefix's length (1: only position 1, "2a. A", still matches),
     * telling the out-adapter position-based preservation is unsafe beyond it for this call.
     */
    @Test
    void updateThatInsertsAnExtensionIsFlaggedAsRestructured() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        service.update(WS, code, null, null, null, null, null, null,
                List.of("2a. A", "3a. B"), null, null, null, DEFAULT_LANGUAGE);

        service.update(WS, code, null, null, null, null, null, null,
                List.of("2a. A", "2b. New", "3a. B"), null, null, null, DEFAULT_LANGUAGE);

        assertEquals(1, repository.lastStableExtensionPrefixLength());
    }

    @Test
    void updatePreservesPrimaryActorSupportingActorsAndSteps() {
        NewUseCase command = new NewUseCase("Place order", "Customer places an order", "Webshop",
                "Customer opens the cart", "Customer", List.of("PaymentProvider"),
                "Customer is logged in", "Order is recorded",
                List.of(new NewStep(1, "select items", List.of("FR5"))), List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();
        UseCase before = service.get(WS, code, null).orElseThrow();

        UseCase updated = service.update(
                WS, code, "New title", null, null, null, null, null, null, null, null, null, DEFAULT_LANGUAGE);

        assertEquals(before.primaryActor(), updated.primaryActor());
        assertEquals(before.supportingActors(), updated.supportingActors());
        assertEquals(before.steps(), updated.steps());
    }

    @Test
    void updateThrowsWhenUseCaseUnknown() {
        UseCaseNotFoundException ex = assertThrows(UseCaseNotFoundException.class,
                () -> service.update(WS, new UseCaseCode("UC99"), "New title", null, null, null, null, null,
                        null, null, null, null, DEFAULT_LANGUAGE));

        assertSame(WS, ex.projectId());
        assertEquals(new UseCaseCode("UC99"), ex.useCaseCode());
    }

    @Test
    void updateCorrectsAnExistingStepsTextWithoutTouchingItsRealisesOrOtherSteps() {
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null,
                List.of(new NewStep(1, "select items", List.of("FR5")),
                        new NewStep(2, "confirm", List.of())),
                List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, null, null, null, null, null, null, null,
                List.of(new StepTextPatch(1, "select the desired items")), null, null, DEFAULT_LANGUAGE);

        assertEquals("select the desired items", updated.steps().get(0).text());
        assertEquals(List.of(new RequirementRef(FR5_ID)), updated.steps().get(0).realises());
        assertEquals("confirm", updated.steps().get(1).text());
    }

    @Test
    void updateCanPatchSeveralStepsAtOnce() {
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null,
                List.of(new NewStep(1, "select items", List.of()),
                        new NewStep(2, "confirm", List.of())),
                List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, null, null, null, null, null, null, null,
                List.of(new StepTextPatch(1, "select the desired items"),
                        new StepTextPatch(2, "confirm and pay")), null, null, DEFAULT_LANGUAGE);

        assertEquals("select the desired items", updated.steps().get(0).text());
        assertEquals("confirm and pay", updated.steps().get(1).text());
    }

    @Test
    void updateRejectsAStepTextPatchForAnUnknownPosition() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        StepPositionNotFoundException ex = assertThrows(StepPositionNotFoundException.class,
                () -> service.update(WS, code, null, null, null, null, null, null, null,
                        List.of(new StepTextPatch(99, "does not exist")), null, null, DEFAULT_LANGUAGE));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.useCaseCode());
        assertEquals(99, ex.position());
    }

    @Test
    void stepTextPatchRejectsNullTextInsteadOfSilentlyIgnoringThePosition() {
        assertThrows(NullPointerException.class, () -> new StepTextPatch(1, null));
    }

    @Test
    void stepTextPatchRejectsBlankText() {
        assertThrows(IllegalArgumentException.class, () -> new StepTextPatch(1, "  "));
    }

    @Test
    void updateWithAnUnknownStepPositionPatchLeavesTheUseCaseUntouched() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        UseCase before = service.get(WS, code, null).orElseThrow();

        assertThrows(StepPositionNotFoundException.class,
                () -> service.update(WS, code, "attempted title change", null, null, null, null, null, null,
                        List.of(new StepTextPatch(99, "does not exist")), null, null, DEFAULT_LANGUAGE));

        assertEquals(before, service.get(WS, code, null).orElseThrow());
    }

    @Test
    void updateWithStepRealisesPatchesResolvesEachCodeAndReplacesTheNamedStepsRealises() {
        ResourceId fr7Id = ResourceId.of("https://w3id.org/arknet/id/req-fr7");
        requirementLookup.register("FR7", fr7Id);
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null,
                List.of(new NewStep(1, "select items", List.of("FR5")),
                        new NewStep(2, "confirm", List.of())),
                List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, null, null, null, null, null, null, null, null,
                List.of(new StepRealisesPatch(1, List.of("FR7"))), null, DEFAULT_LANGUAGE);

        assertEquals("select items", updated.steps().get(0).text());
        assertEquals(List.of(new RequirementRef(fr7Id)), updated.steps().get(0).realises());
        assertEquals("confirm", updated.steps().get(1).text());
        assertEquals(List.of(), updated.steps().get(1).realises());
    }

    @Test
    void updateWithStepRealisesPatchesPropagatesAnUnknownRequirementReferenceFromTheLookupPort() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        assertThrows(NoSuchElementException.class,
                () -> service.update(WS, code, null, null, null, null, null, null, null, null,
                        List.of(new StepRealisesPatch(1, List.of("FR-UNKNOWN"))), null, DEFAULT_LANGUAGE));
    }

    @Test
    void updateWithStepRealisesPatchesClearsAnExistingRealisesSetWhenGivenAnEmptyList() {
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null, List.of(new NewStep(1, "select items", List.of("FR5"))), List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, null, null, null, null, null, null, null, null,
                List.of(new StepRealisesPatch(1, List.of())), null, DEFAULT_LANGUAGE);

        assertTrue(updated.steps().get(0).realises().isEmpty());
    }

    @Test
    void updateAppliesStepTextPatchesAndStepRealisesPatchesIndependently() {
        ResourceId fr7Id = ResourceId.of("https://w3id.org/arknet/id/req-fr7");
        requirementLookup.register("FR7", fr7Id);
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null,
                List.of(new NewStep(1, "select items", List.of("FR5")),
                        new NewStep(2, "confirm", List.of())),
                List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, null, null, null, null, null, null, null,
                List.of(new StepTextPatch(2, "confirm and pay")),
                List.of(new StepRealisesPatch(1, List.of("FR7"))), null, DEFAULT_LANGUAGE);

        assertEquals("select items", updated.steps().get(0).text());
        assertEquals(List.of(new RequirementRef(fr7Id)), updated.steps().get(0).realises());
        assertEquals("confirm and pay", updated.steps().get(1).text());
        assertEquals(List.of(), updated.steps().get(1).realises());
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
