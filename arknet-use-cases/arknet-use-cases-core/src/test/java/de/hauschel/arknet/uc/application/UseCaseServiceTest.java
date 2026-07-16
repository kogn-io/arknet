package de.hauschel.arknet.uc.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;

/**
 * Policy tests for {@link UseCaseService}: opaque identity minting, code assignment, listing
 * and lookup, exercised against an in-memory fake repository and a deterministic fake
 * {@link ResourceIdFactory}.
 */
class UseCaseServiceTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;

    private InMemoryUseCaseRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private UseCaseService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryUseCaseRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        service = new UseCaseService(repository, resourceIdFactory);
    }

    private static NewUseCase newUseCase(String title) {
        return new NewUseCase(title, "goal of " + title, null, null, new ActorRef("Customer"),
                List.of(), null, null, List.of(new Step(1, "do something", List.of())), List.of());
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
    void addCarriesTheCompleteUseCaseThrough() {
        NewUseCase command = new NewUseCase("Place order", "Customer places an order", "Webshop",
                "Customer opens the cart", new ActorRef("Customer"),
                List.of(new ActorRef("PaymentProvider")), "Customer is logged in", "Order is recorded",
                List.of(new Step(1, "select items", List.of(new RequirementRef("FR5"))),
                        new Step(2, "confirm", List.of())),
                List.of("2a. Payment declined -> abort"));

        UseCase added = service.add(WS, command);

        assertEquals("Webshop", added.scope());
        assertEquals("Customer opens the cart", added.trigger());
        assertEquals(List.of(new ActorRef("PaymentProvider")), added.supportingActors());
        assertEquals("Customer is logged in", added.precondition());
        assertEquals("Order is recorded", added.postcondition());
        assertEquals(2, added.steps().size());
        assertEquals(List.of(new RequirementRef("FR5")), added.steps().get(0).realises());
        assertEquals(List.of("2a. Payment declined -> abort"), added.extensions());
    }

    @Test
    void addNumbersRunningPerWorkspace() {
        assertEquals(new UseCaseCode("UC1"), service.add(WS, newUseCase("a")).code());
        assertEquals(new UseCaseCode("UC2"), service.add(WS, newUseCase("b")).code());
        assertEquals(new UseCaseCode("UC3"), service.add(WS, newUseCase("c")).code());
    }

    @Test
    void addIsScopedPerWorkspace() {
        WorkspaceId other = new WorkspaceId("other");
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
