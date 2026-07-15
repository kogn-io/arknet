package de.hauschel.arknet.uc.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * Policy tests for {@link UseCaseService}: identity assignment, listing and
 * lookup, exercised against an in-memory fake repository.
 */
class UseCaseServiceTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;

    private InMemoryUseCaseRepository repository;
    private UseCaseService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryUseCaseRepository();
        service = new UseCaseService(repository);
    }

    private static NewUseCase newUseCase(String title) {
        return new NewUseCase(title, "goal of " + title, null, null, new ActorRef("Customer"),
                List.of(), null, null, List.of(new Step(1, "do something", List.of())), List.of());
    }

    @Test
    void addAssignsFirstIdentity() {
        UseCase added = service.add(WS, newUseCase("Place order"));

        assertEquals(new UseCaseId("UC1"), added.id());
        assertEquals("Place order", added.title());
        assertEquals(added, repository.findById(WS, added.id()).orElseThrow());
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
        assertEquals(new UseCaseId("UC1"), service.add(WS, newUseCase("a")).id());
        assertEquals(new UseCaseId("UC2"), service.add(WS, newUseCase("b")).id());
        assertEquals(new UseCaseId("UC3"), service.add(WS, newUseCase("c")).id());
    }

    @Test
    void addIsScopedPerWorkspace() {
        WorkspaceId other = new WorkspaceId("other");
        service.add(WS, newUseCase("a"));

        UseCase inOther = service.add(other, newUseCase("b"));

        assertEquals(new UseCaseId("UC1"), inOther.id());
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
        UseCaseId id = service.add(WS, newUseCase("a")).id();

        assertTrue(service.get(WS, id).isPresent());
        assertEquals("a", service.get(WS, id).orElseThrow().title());
    }

    @Test
    void getIsEmptyForUnknownId() {
        assertFalse(service.get(WS, new UseCaseId("UC99")).isPresent());
    }

    @Test
    void addGetListRoundtrip() {
        UseCase added = service.add(WS, newUseCase("Place order"));

        UseCase fetched = service.get(WS, added.id()).orElseThrow();

        assertEquals(added, fetched);
        assertTrue(service.list(WS).contains(added));
    }
}
