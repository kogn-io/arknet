package de.hauschel.arknet.uc.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link UseCase} and its value objects.
 *
 * <p>Pure, framework-free unit tests - they guard the domain contract:
 * mandatory fields, step ordering and identifier/label validation.</p>
 */
class UseCaseTest {

    private static Step step(int position, String text) {
        return new Step(position, text, List.of());
    }

    private static UseCase useCaseWithSteps(List<Step> steps) {
        return new UseCase(new UseCaseId("UC1"), "Place order", "Customer places an order",
                null, null, new ActorRef("Customer"), List.of(), null, null, steps, List.of());
    }

    @Test
    void holdsItsFields() {
        Step s1 = new Step(1, "Customer selects items", List.of(new RequirementRef("FR5")));
        UseCase uc = new UseCase(new UseCaseId("UC1"), "Place order", "Customer places an order",
                "Webshop", "Customer opens the cart", new ActorRef("Customer"),
                List.of(new ActorRef("PaymentProvider")), "Customer is logged in",
                "Order is recorded", List.of(s1), List.of("2a. Payment declined -> abort"));

        assertEquals(new UseCaseId("UC1"), uc.id());
        assertEquals("Place order", uc.title());
        assertEquals("Customer places an order", uc.goal());
        assertEquals("Webshop", uc.scope());
        assertEquals("Customer opens the cart", uc.trigger());
        assertEquals(new ActorRef("Customer"), uc.primaryActor());
        assertEquals(List.of(new ActorRef("PaymentProvider")), uc.supportingActors());
        assertEquals("Customer is logged in", uc.precondition());
        assertEquals("Order is recorded", uc.postcondition());
        assertEquals(List.of(s1), uc.steps());
        assertEquals(List.of("2a. Payment declined -> abort"), uc.extensions());
        assertEquals(List.of(new RequirementRef("FR5")), uc.steps().get(0).realises());
    }

    @Test
    void optionalFieldsMayBeNullAndCollectionsDefaultToEmpty() {
        UseCase uc = new UseCase(new UseCaseId("UC1"), "t", "g", null, null,
                new ActorRef("A"), null, null, null, List.of(step(1, "do")), null);

        assertTrue(uc.supportingActors().isEmpty());
        assertTrue(uc.extensions().isEmpty());
    }

    @Test
    void rejectsNullMandatoryFields() {
        List<Step> steps = List.of(step(1, "do"));
        assertThrows(NullPointerException.class, () -> new UseCase(null, "t", "g", null, null,
                new ActorRef("A"), List.of(), null, null, steps, List.of()));
        assertThrows(NullPointerException.class, () -> new UseCase(new UseCaseId("UC1"), null, "g", null, null,
                new ActorRef("A"), List.of(), null, null, steps, List.of()));
        assertThrows(NullPointerException.class, () -> new UseCase(new UseCaseId("UC1"), "t", null, null, null,
                new ActorRef("A"), List.of(), null, null, steps, List.of()));
        assertThrows(NullPointerException.class, () -> new UseCase(new UseCaseId("UC1"), "t", "g", null, null,
                null, List.of(), null, null, steps, List.of()));
    }

    @Test
    void rejectsBlankTitle() {
        assertThrows(IllegalArgumentException.class, () -> new UseCase(new UseCaseId("UC1"), "  ", "g",
                null, null, new ActorRef("A"), List.of(), null, null, List.of(step(1, "do")), List.of()));
    }

    @Test
    void rejectsBlankGoal() {
        assertThrows(IllegalArgumentException.class, () -> new UseCase(new UseCaseId("UC1"), "t", "  ",
                null, null, new ActorRef("A"), List.of(), null, null, List.of(step(1, "do")), List.of()));
    }

    @Test
    void rejectsEmptyStepList() {
        assertThrows(IllegalArgumentException.class, () -> useCaseWithSteps(List.of()));
    }

    @Test
    void acceptsConsecutiveAscendingStepPositions() {
        UseCase uc = useCaseWithSteps(List.of(step(1, "a"), step(2, "b"), step(3, "c")));
        assertEquals(3, uc.steps().size());
    }

    @Test
    void rejectsGapInStepPositions() {
        assertThrows(IllegalArgumentException.class,
                () -> useCaseWithSteps(List.of(step(1, "a"), step(3, "c"))));
    }

    @Test
    void rejectsDuplicateStepPositions() {
        assertThrows(IllegalArgumentException.class,
                () -> useCaseWithSteps(List.of(step(1, "a"), step(1, "b"))));
    }

    @Test
    void rejectsUnorderedStepPositions() {
        assertThrows(IllegalArgumentException.class,
                () -> useCaseWithSteps(List.of(step(2, "b"), step(1, "a"))));
    }

    @Test
    void rejectsStepPositionBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new Step(0, "do", List.of()));
    }

    @Test
    void rejectsBlankStepText() {
        assertThrows(IllegalArgumentException.class, () -> new Step(1, "  ", List.of()));
    }

    @Test
    void rejectsBlankUseCaseId() {
        assertThrows(IllegalArgumentException.class, () -> new UseCaseId("  "));
    }

    @Test
    void rejectsBlankRequirementRefLabel() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementRef(" "));
    }

    @Test
    void rejectsBlankActorRefLabel() {
        assertThrows(IllegalArgumentException.class, () -> new ActorRef(" "));
    }
}
