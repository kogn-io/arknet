package de.hauschel.arknet.req.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED);

        assertEquals(id, req.id());
        assertEquals("User can log in", req.title());
        assertEquals(RequirementType.FUNCTIONAL, req.type());
        assertEquals(RequirementStatus.PROPOSED, req.status());
    }

    @Test
    void rejectsNullFields() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(NullPointerException.class,
                () -> new Requirement(null, "t", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED));
        assertThrows(NullPointerException.class,
                () -> new Requirement(id, null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED));
    }

    @Test
    void rejectsBlankTitle() {
        RequirementId id = new RequirementId("FR-1");
        assertThrows(IllegalArgumentException.class,
                () -> new Requirement(id, "  ", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED));
    }

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementId(" "));
    }
}
