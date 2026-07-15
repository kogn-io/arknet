package de.hauschel.arknet.req.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                "https://w3id.org/arknet/model/goal/secure-login", null);

        assertEquals(id, req.id());
        assertEquals("User can log in", req.title());
        assertEquals("The system shall let a registered user authenticate with email and password.",
                req.description());
        assertEquals(RequirementType.FUNCTIONAL, req.type());
        assertEquals(RequirementStatus.PROPOSED, req.status());
        assertEquals(Priority.MUST_HAVE, req.priority());
        assertEquals("https://w3id.org/arknet/model/goal/secure-login", req.motivatedBy());
        assertNull(req.qualityCategory());
    }

    @Test
    void optionalFieldsMayBeNull() {
        RequirementId id = new RequirementId("FR-1");
        Requirement req = new Requirement(id, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, null);

        assertNull(req.priority());
        assertNull(req.motivatedBy());
        assertNull(req.qualityCategory());
    }

    @Test
    void allowsQualityCategoryOnNonFunctionalRequirement() {
        RequirementId id = new RequirementId("NFR-1");
        Requirement req = new Requirement(id, "t", "d", RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, "performance");

        assertEquals("performance", req.qualityCategory());
    }

    @Test
    void rejectsQualityCategoryOnFunctionalRequirement() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(id, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, "performance"));
    }

    @Test
    void rejectsNullFields() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(NullPointerException.class,
                () -> new Requirement(null, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null));
        assertThrows(NullPointerException.class,
                () -> new Requirement(id, null, "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null));
        assertThrows(NullPointerException.class,
                () -> new Requirement(id, "t", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null));
    }

    @Test
    void rejectsBlankTitle() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(id, "  ", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null));
    }

    @Test
    void rejectsBlankDescription() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(id, "t", "  ", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                        null, null, null));
    }

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementId(" "));
    }
}
