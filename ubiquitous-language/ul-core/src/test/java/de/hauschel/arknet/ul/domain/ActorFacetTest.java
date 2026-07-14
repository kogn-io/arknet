package de.hauschel.arknet.ul.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link ActorFacet}.
 *
 * <p>Pure, framework-free unit tests - they guard the value object's contract.</p>
 */
class ActorFacetTest {

    @Test
    void holdsItsValues() {
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");

        assertEquals(ActorKind.HUMAN, facet.kind());
        assertEquals("Sachbearbeiter", facet.role());
    }

    @Test
    void roleIsOptional() {
        ActorFacet facet = new ActorFacet(ActorKind.SYSTEM, null);

        assertNull(facet.role());
    }

    @Test
    void rejectsNullKind() {
        assertThrows(NullPointerException.class, () -> new ActorFacet(null, "role"));
    }

    @Test
    void rejectsBlankRole() {
        assertThrows(IllegalArgumentException.class, () -> new ActorFacet(ActorKind.HUMAN, " "));
    }
}
