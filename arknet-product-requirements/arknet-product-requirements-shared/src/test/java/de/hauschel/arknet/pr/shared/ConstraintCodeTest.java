// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.pr.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link ConstraintCode}: the human-readable running label
 * ({@code TCON-1}, {@code BCON-3}, {@code RCON-1}), separate from the opaque {@code ConstraintId}.
 */
class ConstraintCodeTest {

    @Test
    void holdsItsValue() {
        ConstraintCode code = new ConstraintCode("TCON-1");

        assertEquals("TCON-1", code.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new ConstraintCode(null));
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> new ConstraintCode(" "));
    }
}
