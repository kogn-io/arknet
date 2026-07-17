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

    @Test
    void holdsItsFields() {
        Requirement req = new Requirement(ID, CODE, "User can log in",
                "The system shall let a registered user authenticate with email and password.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE,
                "https://w3id.org/arknet/model/goal/secure-login", null, List.of(TERM_1));

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
    }

    @Test
    void optionalFieldsMayBeNull() {
        Requirement req = new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null);

        assertNull(req.priority());
        assertNull(req.motivatedBy());
        assertNull(req.qualityCategory());
    }

    @Test
    void nullUsesTermsIsNormalisedToAnEmptyList() {
        Requirement req = new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null);

        assertEquals(List.of(), req.usesTerms());
    }

    @Test
    void usesTermsAreDefensivelyCopied() {
        List<TermRef> terms = new ArrayList<>(List.of(TERM_1));
        Requirement req = new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, terms);

        terms.add(TERM_2);

        assertEquals(List.of(TERM_1), req.usesTerms());
        assertThrows(UnsupportedOperationException.class, () -> req.usesTerms().add(TERM_2));
    }

    @Test
    void allowsQualityCategoryOnNonFunctionalRequirement() {
        Requirement req = new Requirement(ID, new RequirementCode("NFR-1"), "t", "d",
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED, null, null, "performance", null);

        assertEquals("performance", req.qualityCategory());
    }

    @Test
    void rejectsQualityCategoryOnFunctionalRequirement() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, "performance", null));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new Requirement(null, CODE, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
        assertThrows(NullPointerException.class,
                () -> new Requirement(ID, null, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
        assertThrows(NullPointerException.class,
                () -> new Requirement(ID, CODE, null, "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
        assertThrows(NullPointerException.class,
                () -> new Requirement(ID, CODE, "t", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
    }

    @Test
    void rejectsBlankTitle() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "  ", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
    }

    @Test
    void rejectsBlankDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(ID, CODE, "t", "  ", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
    }

    @Test
    void rejectsNullResourceId() {
        assertThrows(NullPointerException.class, () -> new RequirementId(null));
    }

    @Test
    void rejectsBlankCode() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementCode(" "));
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
