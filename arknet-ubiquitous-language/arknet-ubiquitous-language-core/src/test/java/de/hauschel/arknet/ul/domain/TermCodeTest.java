// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link TermCode}: the human-readable running label
 * ({@code TERM-1}), separate from the opaque {@link TermId}.
 */
class TermCodeTest {

    @Test
    void holdsItsValue() {
        TermCode code = new TermCode("TERM-1");

        assertEquals("TERM-1", code.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new TermCode(null));
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> new TermCode(" "));
    }
}
