// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Domain invariant tests for {@link Requirement} and its value objects.
 *
 * <p>Pure, framework-free unit tests - they guard the scaffold's domain
 * contract, not yet-to-be-written application policy.</p>
 */
class RequirementTest {

    private static final RequirementId ID =
            new RequirementId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));
    private static final RequirementCode CODE = new RequirementCode("FR-1");
    private static final TermRef TERM_1 =
            new TermRef(ResourceId.of("https://w3id.org/arknet/id/22222222-2222-2222-2222-222222222222"));
    private static final TermRef TERM_2 =
            new TermRef(ResourceId.of("https://w3id.org/arknet/id/33333333-3333-3333-3333-333333333333"));
    private static final List<String> CRITERIA = List.of("Login succeeds with valid credentials");
    private static final String CRITERION_2 = "Login is rejected with invalid credentials";

    @Test
    void holdsItsFields() {
        Requirement req = new Requirement(ID, CODE, "User can log in",
                "The system shall let a registered user authenticate with email and password.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE,
                "https://w3id.org/arknet/model/goal/secure-login", null, List.of(TERM_1), CRITERIA);

        assertEquals(ID, req.id());
        assertEquals(CODE, req.code());
        assertEquals("User can log in", req.title());
        assertEquals("The system shall let a registered user authenticate with email and password.",
                req.description());
        assertEquals(RequirementType.FUNCTIONAL, req.type());
        assertEquals(RequirementStatus.PROPOSED, req.status());
        assertEquals(Priority.MUST_HAVE, req.priority());
        assertEquals("https://w3id.org/arknet/model/goal/secure-login", req.motivatedBy());
        assertNull(req.qualityCategory());
        assertEquals(List.of(TERM_1), req.usesTerms());
        assertEquals(CRITERIA, req.acceptanceCriteria());
    }

    @Test
    void optionalFieldsMayBeNull() {
        Requirement req = new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, CRITERIA);

        assertNull(req.priority());
        assertNull(req.motivatedBy());
        assertNull(req.qualityCategory());
    }

    @Test
    void nullUsesTermsIsNormalisedToAnEmptyList() {
        Requirement req = new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, CRITERIA);

        assertEquals(List.of(), req.usesTerms());
    }

    @Test
    void usesTermsAreDefensivelyCopied() {
        List<TermRef> terms = new ArrayList<>(List.of(TERM_1));
        Requirement req = new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, terms, CRITERIA);

        terms.add(TERM_2);

        assertEquals(List.of(TERM_1), req.usesTerms());
        assertThrows(UnsupportedOperationException.class, () -> req.usesTerms().add(TERM_2));
    }

    @Test
    void acceptanceCriteriaAreDefensivelyCopied() {
        List<String> criteria = new ArrayList<>(CRITERIA);
        Requirement req = new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, criteria);

        criteria.add(CRITERION_2);

        assertEquals(CRITERIA, req.acceptanceCriteria());
        assertThrows(UnsupportedOperationException.class, () -> req.acceptanceCriteria().add(CRITERION_2));
    }

    @Test
    void rejectsNullAcceptanceCriteria() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, null));
    }

    @Test
    void rejectsEmptyAcceptanceCriteria() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, List.of()));
    }

    @Test
    void rejectsBlankAcceptanceCriterion() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, List.of("Login succeeds", "   ")));
    }

    @Test
    void rejectsDuplicateAcceptanceCriteria() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, List.of("Login succeeds", "Login succeeds")));
    }

    @Test
    void rejectsDuplicateUsesTerms() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, List.of(TERM_1, TERM_1), CRITERIA));
    }

    @Test
    void allowsQualityCategoryOnNonFunctionalRequirement() {
        Requirement req = new Requirement(ID, new RequirementCode("NFR-1"), "t", "d",
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED, null, null, "performance", null,
                CRITERIA);

        assertEquals("performance", req.qualityCategory());
    }

    @Test
    void rejectsQualityCategoryOnFunctionalRequirement() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, "performance", null, CRITERIA));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new Requirement(null, CODE, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, CRITERIA));
        assertThrows(NullPointerException.class,
                () -> new Requirement(ID, null, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, CRITERIA));
        assertThrows(NullPointerException.class,
                () -> new Requirement(ID, CODE, null, "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, CRITERIA));
        assertThrows(NullPointerException.class,
                () -> new Requirement(ID, CODE, "t", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, CRITERIA));
    }

    @Test
    void rejectsBlankTitle() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "  ", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, CRITERIA));
    }

    @Test
    void rejectsBlankDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "t", "  ", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null, CRITERIA));
    }

    @Test
    void rejectsNullResourceId() {
        assertThrows(NullPointerException.class, () -> new RequirementId(null));
    }

    @Test
    void rejectsBlankCode() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementCode(" "));
    }

    /** The transition rule itself lives on {@link Requirement#accept()}. */
    @Test
    void acceptTransitionsProposedToAccepted() {
        Requirement req = new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, CRITERIA);

        Requirement accepted = req.accept();

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals(req.title(), accepted.title());
        assertEquals(req.acceptanceCriteria(), accepted.acceptanceCriteria());
    }

    /** Accepting an already-accepted requirement is a no-op, not a rejection. */
    @Test
    void acceptOnAnAlreadyAcceptedRequirementIsANoOp() {
        Requirement req = new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.ACCEPTED, null, null, null, null, CRITERIA);

        Requirement result = req.accept();

        assertEquals(req, result);
    }

    @Test
    void termRefHoldsItsIdentity() {
        ResourceId termId = ResourceId.of("https://w3id.org/arknet/id/44444444-4444-4444-4444-444444444444");

        assertEquals(termId, new TermRef(termId).value());
    }

    @Test
    void termRefRejectsNullIdentity() {
        assertThrows(NullPointerException.class, () -> new TermRef(null));
    }
}
