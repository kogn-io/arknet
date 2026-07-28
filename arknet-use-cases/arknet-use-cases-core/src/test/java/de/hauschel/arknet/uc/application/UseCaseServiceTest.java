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

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.StepTextPatch;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.StepPositionNotFoundException;
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
                List.of(), null, null, List.of(new NewStep(1, "do something", List.of())), List.of());
    }

    @Test
    void addAssignsFirstCode() {
        UseCase added = service.add(WS, newUseCase("Place order"));

        assertEquals(new UseCaseCode("UC1"), added.code());
        assertEquals("Place order", added.title());
        assertEquals(added, repository.findByCode(WS, added.code()).orElseThrow());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        UseCase first = service.add(WS, newUseCase("a"));
        UseCase second = service.add(WS, newUseCase("b"));

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
                List.of("2a. Payment declined -> abort"));

        UseCase added = service.add(WS, command);

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
                List.of(), null, null, List.of(new NewStep(1, "do something", List.of())), List.of());

        assertThrows(NoSuchElementException.class, () -> service.add(WS, command));
        assertTrue(service.list(WS).isEmpty());
    }

    @Test
    void addPropagatesAnUnknownRequirementReferenceFromTheLookupPort() {
        NewUseCase command = new NewUseCase("Broken", "goal", null, null, "Customer",
                List.of(), null, null,
                List.of(new NewStep(1, "do something", List.of("FR-UNKNOWN"))), List.of());

        assertThrows(NoSuchElementException.class, () -> service.add(WS, command));
        assertTrue(service.list(WS).isEmpty());
    }

    @Test
    void addNumbersRunningPerWorkspace() {
        assertEquals(new UseCaseCode("UC1"), service.add(WS, newUseCase("a")).code());
        assertEquals(new UseCaseCode("UC2"), service.add(WS, newUseCase("b")).code());
        assertEquals(new UseCaseCode("UC3"), service.add(WS, newUseCase("c")).code());
    }

    @Test
    void addIsScopedPerWorkspace() {
        ProjectId other = new ProjectId("other");
        service.add(WS, newUseCase("a"));

        UseCase inOther = service.add(other, newUseCase("b"));

        assertEquals(new UseCaseCode("UC1"), inOther.code());
        assertEquals(1, service.list(other).size());
        assertEquals(1, service.list(WS).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, newUseCase("a"));
        service.add(WS, newUseCase("b"));

        List<UseCase> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals("a", all.get(0).title());
        assertEquals("b", all.get(1).title());
    }

    @Test
    void getReturnsPersistedUseCase() {
        UseCaseCode code = service.add(WS, newUseCase("a")).code();

        assertTrue(service.get(WS, code).isPresent());
        assertEquals("a", service.get(WS, code).orElseThrow().title());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new UseCaseCode("UC99")).isPresent());
    }

    @Test
    void addGetListRoundtrip() {
        UseCase added = service.add(WS, newUseCase("Place order"));

        UseCase fetched = service.get(WS, added.code()).orElseThrow();

        assertEquals(added, fetched);
        assertTrue(service.list(WS).contains(added));
    }

    @Test
    void updateChangesGoalLevelFields() {
        UseCaseCode code = service.add(WS, newUseCase("Place order")).code();

        UseCase updated = service.update(WS, code, "New title", "New goal", "New scope", "New trigger",
                "New precondition", "New postcondition", null, null);

        assertEquals("New title", updated.title());
        assertEquals("New goal", updated.goal());
        assertEquals("New scope", updated.scope());
        assertEquals("New trigger", updated.trigger());
        assertEquals("New precondition", updated.precondition());
        assertEquals("New postcondition", updated.postcondition());
        assertEquals(updated, service.get(WS, code).orElseThrow());
    }

    @Test
    void updateWithNullFieldsLeavesThemUnchanged() {
        UseCaseCode code = service.add(WS, newUseCase("Place order")).code();

        UseCase updated = service.update(WS, code, null, "New goal", null, null, null, null, null, null);

        assertEquals("Place order", updated.title());
        assertEquals("New goal", updated.goal());
    }

    @Test
    void updateWithEverythingOmittedIsANoOp() {
        UseCaseCode code = service.add(WS, newUseCase("Place order")).code();
        UseCase before = service.get(WS, code).orElseThrow();

        UseCase result = service.update(WS, code, null, null, null, null, null, null, null, null);

        assertEquals(before, result);
    }

    @Test
    void updateReplacesExtensionsWholesale() {
        UseCaseCode code = service.add(WS, newUseCase("Place order")).code();

        UseCase updated = service.update(WS, code, null, null, null, null, null, null,
                List.of("2a. Payment declined -> abort"), null);

        assertEquals(List.of("2a. Payment declined -> abort"), updated.extensions());
    }

    @Test
    void updatePreservesPrimaryActorSupportingActorsAndSteps() {
        NewUseCase command = new NewUseCase("Place order", "Customer places an order", "Webshop",
                "Customer opens the cart", "Customer", List.of("PaymentProvider"),
                "Customer is logged in", "Order is recorded",
                List.of(new NewStep(1, "select items", List.of("FR5"))), List.of());
        UseCaseCode code = service.add(WS, command).code();
        UseCase before = service.get(WS, code).orElseThrow();

        UseCase updated = service.update(WS, code, "New title", null, null, null, null, null, null, null);

        assertEquals(before.primaryActor(), updated.primaryActor());
        assertEquals(before.supportingActors(), updated.supportingActors());
        assertEquals(before.steps(), updated.steps());
    }

    @Test
    void updateThrowsWhenUseCaseUnknown() {
        UseCaseNotFoundException ex = assertThrows(UseCaseNotFoundException.class,
                () -> service.update(WS, new UseCaseCode("UC99"), "New title", null, null, null, null, null,
                        null, null));

        assertSame(WS, ex.projectId());
        assertEquals(new UseCaseCode("UC99"), ex.useCaseCode());
    }

    @Test
    void updateCorrectsAnExistingStepsTextWithoutTouchingItsRealisesOrOtherSteps() {
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null,
                List.of(new NewStep(1, "select items", List.of("FR5")),
                        new NewStep(2, "confirm", List.of())),
                List.of());
        UseCaseCode code = service.add(WS, command).code();

        UseCase updated = service.update(WS, code, null, null, null, null, null, null, null,
                List.of(new StepTextPatch(1, "select the desired items")));

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
                List.of());
        UseCaseCode code = service.add(WS, command).code();

        UseCase updated = service.update(WS, code, null, null, null, null, null, null, null,
                List.of(new StepTextPatch(1, "select the desired items"),
                        new StepTextPatch(2, "confirm and pay")));

        assertEquals("select the desired items", updated.steps().get(0).text());
        assertEquals("confirm and pay", updated.steps().get(1).text());
    }

    @Test
    void updateRejectsAStepTextPatchForAnUnknownPosition() {
        UseCaseCode code = service.add(WS, newUseCase("Place order")).code();

        StepPositionNotFoundException ex = assertThrows(StepPositionNotFoundException.class,
                () -> service.update(WS, code, null, null, null, null, null, null, null,
                        List.of(new StepTextPatch(99, "does not exist"))));

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
        UseCaseCode code = service.add(WS, newUseCase("Place order")).code();
        UseCase before = service.get(WS, code).orElseThrow();

        assertThrows(StepPositionNotFoundException.class,
                () -> service.update(WS, code, "attempted title change", null, null, null, null, null, null,
                        List.of(new StepTextPatch(99, "does not exist"))));

        assertEquals(before, service.get(WS, code).orElseThrow());
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
