// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Domain invariant tests for {@link UseCase} and its value objects.
 *
 * <p>Pure, framework-free unit tests - they guard the domain contract:
 * mandatory fields, step ordering and identifier/label validation.</p>
 */
class UseCaseTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final UseCaseId ID = new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/uc-1"));
    private static final UseCaseCode CODE = new UseCaseCode("UC1");

    private static final RoleRef CUSTOMER = new RoleRef(ResourceId.of("https://w3id.org/arknet/id/actor-customer"));
    private static final RoleRef PAYMENT_PROVIDER =
            new RoleRef(ResourceId.of("https://w3id.org/arknet/id/actor-payment-provider"));
    private static final RoleRef ROLE_A = new RoleRef(ResourceId.of("https://w3id.org/arknet/id/actor-a"));
    private static final RequirementRef FR5 = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-5"));

    private static Step step(int position, String text) {
        return new Step(position, text, List.of());
    }

    private static UseCase useCaseWithSteps(List<Step> steps) {
        return new UseCase(ID, CODE, "Place order", "Customer places an order",
                null, null, CUSTOMER, List.of(), null, null, steps, List.of(), List.of(), List.of());
    }

    @Test
    void holdsItsFields() {
        Step s1 = new Step(1, "Customer selects items", List.of(FR5));
        TermRef termRef = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));
        ConstraintRef constraintRef = new ConstraintRef(ResourceId.of("https://w3id.org/arknet/id/constraint-1"));
        UseCase uc = new UseCase(ID, CODE, "Place order", "Customer places an order",
                "Webshop", "Customer opens the cart", CUSTOMER,
                List.of(PAYMENT_PROVIDER), "Customer is logged in",
                "Order is recorded", List.of(s1), List.of("2a. Payment declined -> abort"),
                List.of(termRef), List.of(constraintRef));

        assertEquals(ID, uc.id());
        assertEquals(CODE, uc.code());
        assertEquals("Place order", uc.title());
        assertEquals("Customer places an order", uc.goal());
        assertEquals("Webshop", uc.scope());
        assertEquals("Customer opens the cart", uc.trigger());
        assertEquals(CUSTOMER, uc.primaryRole());
        assertEquals(List.of(PAYMENT_PROVIDER), uc.supportingRoles());
        assertEquals("Customer is logged in", uc.precondition());
        assertEquals("Order is recorded", uc.postcondition());
        assertEquals(List.of(s1), uc.steps());
        assertEquals(List.of("2a. Payment declined -> abort"), uc.extensions());
        assertEquals(List.of(FR5), uc.steps().get(0).realises());
        assertEquals(List.of(termRef), uc.usesTerms());
        assertEquals(List.of(constraintRef), uc.constrainedBy());
    }

    @Test
    void optionalFieldsMayBeNullAndCollectionsDefaultToEmpty() {
        UseCase uc = new UseCase(ID, CODE, "t", "g", null, null,
                ROLE_A, null, null, null, List.of(step(1, "do")), null, null, null);

        assertTrue(uc.supportingRoles().isEmpty());
        assertTrue(uc.extensions().isEmpty());
        assertTrue(uc.usesTerms().isEmpty());
        assertTrue(uc.constrainedBy().isEmpty());
    }

    @Test
    void rejectsNullMandatoryFields() {
        List<Step> steps = List.of(step(1, "do"));
        assertThrows(NullPointerException.class, () -> new UseCase(null, CODE, "t", "g", null, null,
                ROLE_A, List.of(), null, null, steps, List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new UseCase(ID, null, "t", "g", null, null,
                ROLE_A, List.of(), null, null, steps, List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new UseCase(ID, CODE, null, "g", null, null,
                ROLE_A, List.of(), null, null, steps, List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new UseCase(ID, CODE, "t", null, null, null,
                ROLE_A, List.of(), null, null, steps, List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new UseCase(ID, CODE, "t", "g", null, null,
                null, List.of(), null, null, steps, List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new UseCase(ID, CODE, "t", "g", null, null,
                ROLE_A, List.of(), null, null, null, List.of(), List.of(), List.of()));
    }

    @Test
    void rejectsBlankTitle() {
        assertThrows(IllegalArgumentException.class, () -> new UseCase(ID, CODE, "  ", "g",
                null, null, ROLE_A, List.of(), null, null, List.of(step(1, "do")), List.of(), List.of(), List.of()));
    }

    @Test
    void rejectsBlankGoal() {
        assertThrows(IllegalArgumentException.class, () -> new UseCase(ID, CODE, "t", "  ",
                null, null, ROLE_A, List.of(), null, null, List.of(step(1, "do")), List.of(), List.of(), List.of()));
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
    void rejectsNullUseCaseId() {
        assertThrows(NullPointerException.class, () -> new UseCaseId(null));
    }

    @Test
    void rejectsBlankUseCaseCode() {
        assertThrows(IllegalArgumentException.class, () -> new UseCaseCode("  "));
    }

    /**
     * {@link UseCase#withStepTextPatches} corrects only the matched step's {@code text}, leaving
     * its {@code realises} references, every unmatched step and every other field of the use case
     * untouched (issue #96 - relocated from {@code UseCaseService#applyStepTextPatches}).
     */
    @Test
    void withStepTextPatchesCorrectsOnlyTheMatchedStepsTextLeavingRealisesAndOtherStepsUntouched() {
        UseCase uc = useCaseWithSteps(List.of(
                new Step(1, "select items", List.of(FR5)),
                new Step(2, "confirm", List.of())));

        UseCase patched = uc.withStepTextPatches(PROJECT, List.of(new StepTextPatch(1, "select the desired items")));

        assertEquals("select the desired items", patched.steps().get(0).text());
        assertEquals(List.of(FR5), patched.steps().get(0).realises());
        assertEquals("confirm", patched.steps().get(1).text());
        assertEquals(uc.id(), patched.id());
        assertEquals(uc.code(), patched.code());
    }

    @Test
    void withStepTextPatchesRejectsNullPatches() {
        UseCase uc = useCaseWithSteps(List.of(step(1, "select items")));

        assertThrows(NullPointerException.class, () -> uc.withStepTextPatches(PROJECT, null));
    }

    @Test
    void withStepTextPatchesRejectsAPatchForAnUnknownPosition() {
        UseCase uc = useCaseWithSteps(List.of(step(1, "select items")));

        StepPositionNotFoundException ex = assertThrows(StepPositionNotFoundException.class,
                () -> uc.withStepTextPatches(PROJECT, List.of(new StepTextPatch(99, "does not exist"))));

        assertSame(PROJECT, ex.projectId());
        assertEquals(CODE, ex.useCaseCode());
        assertEquals(99, ex.position());
    }

    /**
     * {@link UseCase#withStepRealisesPatches} corrects only the matched step's
     * {@code realises}, leaving its {@code text}, every unmatched step and every other field of
     * the use case untouched (issue #255).
     */
    @Test
    void withStepRealisesPatchesCorrectsOnlyTheMatchedStepsRealisesLeavingTextAndOtherStepsUntouched() {
        RequirementRef fr7 = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-7"));
        UseCase uc = useCaseWithSteps(List.of(
                new Step(1, "select items", List.of(FR5)),
                new Step(2, "confirm", List.of())));

        UseCase patched = uc.withStepRealisesPatches(PROJECT, Map.of(1, List.of(fr7)));

        assertEquals("select items", patched.steps().get(0).text());
        assertEquals(List.of(fr7), patched.steps().get(0).realises());
        assertEquals("confirm", patched.steps().get(1).text());
        assertEquals(List.of(), patched.steps().get(1).realises());
        assertEquals(uc.id(), patched.id());
        assertEquals(uc.code(), patched.code());
    }

    /**
     * An empty list for a named position clears that step's existing {@code realises} set - the
     * explicit, unambiguous signal to remove references, distinct from omitting the position
     * altogether (issue #255).
     */
    @Test
    void withStepRealisesPatchesClearsRealisesWhenGivenAnEmptyList() {
        UseCase uc = useCaseWithSteps(List.of(new Step(1, "select items", List.of(FR5))));

        UseCase patched = uc.withStepRealisesPatches(PROJECT, Map.of(1, List.of()));

        assertTrue(patched.steps().get(0).realises().isEmpty());
    }

    @Test
    void withStepRealisesPatchesRejectsAPatchForAnUnknownPosition() {
        UseCase uc = useCaseWithSteps(List.of(step(1, "select items")));

        StepPositionNotFoundException ex = assertThrows(StepPositionNotFoundException.class,
                () -> uc.withStepRealisesPatches(PROJECT, Map.of(99, List.of(FR5))));

        assertSame(PROJECT, ex.projectId());
        assertEquals(CODE, ex.useCaseCode());
        assertEquals(99, ex.position());
    }

    @Test
    void withStepRealisesPatchesRejectsNullMap() {
        UseCase uc = useCaseWithSteps(List.of(step(1, "select items")));

        assertThrows(NullPointerException.class, () -> uc.withStepRealisesPatches(PROJECT, null));
    }

    @Test
    void rejectsNullRequirementRefIdentity() {
        assertThrows(NullPointerException.class, () -> new RequirementRef(null));
    }

    @Test
    void rejectsNullRoleRefIdentity() {
        assertThrows(NullPointerException.class, () -> new RoleRef(null));
    }

    @Test
    void rejectsNullTermRefIdentity() {
        assertThrows(NullPointerException.class, () -> new TermRef(null));
    }

    @Test
    void rejectsNullConstraintRefIdentity() {
        assertThrows(NullPointerException.class, () -> new ConstraintRef(null));
    }

    /**
     * {@link UseCase#withStepTextPatches}/{@link UseCase#withStepRealisesPatches} touch only
     * steps - {@code usesTerms}/{@code constrainedBy} (issue #329) must survive both untouched,
     * the same way {@code title}/{@code code} already do.
     */
    @Test
    void withStepTextPatchesAndWithStepRealisesPatchesLeaveUsesTermsAndConstrainedByUntouched() {
        TermRef termRef = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));
        ConstraintRef constraintRef = new ConstraintRef(ResourceId.of("https://w3id.org/arknet/id/constraint-1"));
        UseCase uc = new UseCase(ID, CODE, "Place order", "Customer places an order",
                null, null, CUSTOMER, List.of(), null, null, List.of(step(1, "select items")), List.of(),
                List.of(termRef), List.of(constraintRef));

        UseCase textPatched = uc.withStepTextPatches(PROJECT, List.of(new StepTextPatch(1, "pick items")));
        UseCase realisesPatched = uc.withStepRealisesPatches(PROJECT, Map.of(1, List.of(FR5)));

        assertEquals(List.of(termRef), textPatched.usesTerms());
        assertEquals(List.of(constraintRef), textPatched.constrainedBy());
        assertEquals(List.of(termRef), realisesPatched.usesTerms());
        assertEquals(List.of(constraintRef), realisesPatched.constrainedBy());
    }
}
