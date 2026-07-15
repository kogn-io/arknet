package de.hauschel.arknet.req.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link Requirement} and its value objects.
 *
 * <p>Pure, framework-free unit tests - they guard the scaffold's domain
 * contract, not yet-to-be-written application policy.</p>
 */
class RequirementTest {

    @Test
    void holdsItsFields() {
        RequirementId id = new RequirementId("FR-1");
        Requirement req = new Requirement(id, "User can log in",
                "The system shall let a registered user authenticate with email and password.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE,
                "https://w3id.org/arknet/model/goal/secure-login", null, List.of(new TermRef("TERM-1")));

        assertEquals(id, req.id());
        assertEquals("User can log in", req.title());
        assertEquals("The system shall let a registered user authenticate with email and password.",
                req.description());
        assertEquals(RequirementType.FUNCTIONAL, req.type());
        assertEquals(RequirementStatus.PROPOSED, req.status());
        assertEquals(Priority.MUST_HAVE, req.priority());
        assertEquals("https://w3id.org/arknet/model/goal/secure-login", req.motivatedBy());
        assertNull(req.qualityCategory());
        assertEquals(List.of(new TermRef("TERM-1")), req.usesTerms());
    }

    @Test
    void optionalFieldsMayBeNull() {
        RequirementId id = new RequirementId("FR-1");
        Requirement req = new Requirement(id, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, null, null);

        assertNull(req.priority());
        assertNull(req.motivatedBy());
        assertNull(req.qualityCategory());
    }

    @Test
    void nullUsesTermsIsNormalisedToAnEmptyList() {
        RequirementId id = new RequirementId("FR-1");
        Requirement req = new Requirement(id, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, null, null);

        assertEquals(List.of(), req.usesTerms());
    }

    @Test
    void usesTermsAreDefensivelyCopied() {
        RequirementId id = new RequirementId("FR-1");
        List<TermRef> terms = new ArrayList<>(List.of(new TermRef("TERM-1")));
        Requirement req = new Requirement(id, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, null, terms);

        terms.add(new TermRef("TERM-2"));

        assertEquals(List.of(new TermRef("TERM-1")), req.usesTerms());
        assertThrows(UnsupportedOperationException.class, () -> req.usesTerms().add(new TermRef("TERM-3")));
    }

    @Test
    void allowsQualityCategoryOnNonFunctionalRequirement() {
        RequirementId id = new RequirementId("NFR-1");
        Requirement req = new Requirement(id, "t", "d", RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, "performance", null);

        assertEquals("performance", req.qualityCategory());
    }

    @Test
    void rejectsQualityCategoryOnFunctionalRequirement() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(id, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, "performance", null));
    }

    @Test
    void rejectsNullFields() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(NullPointerException.class,
                () -> new Requirement(null, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
        assertThrows(NullPointerException.class,
                () -> new Requirement(id, null, "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
        assertThrows(NullPointerException.class,
                () -> new Requirement(id, "t", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
    }

    @Test
    void rejectsBlankTitle() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(id, "  ", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
    }

    @Test
    void rejectsBlankDescription() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(id, "t", "  ", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null, null));
    }

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementId(" "));
    }

    @Test
    void termRefHoldsItsIdentity() {
        assertEquals("TERM-1", new TermRef("TERM-1").termId());
    }

    @Test
    void termRefRejectsNullOrBlankIdentity() {
        assertThrows(NullPointerException.class, () -> new TermRef(null));
        assertThrows(IllegalArgumentException.class, () -> new TermRef(" "));
    }
}
