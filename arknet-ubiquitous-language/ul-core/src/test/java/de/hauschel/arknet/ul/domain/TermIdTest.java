package de.hauschel.arknet.ul.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link TermId}.
 */
class TermIdTest {

    @Test
    void holdsItsValue() {
        TermId id = new TermId("TERM-1");

        assertEquals("TERM-1", id.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new TermId(null));
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> new TermId(" "));
    }
}
