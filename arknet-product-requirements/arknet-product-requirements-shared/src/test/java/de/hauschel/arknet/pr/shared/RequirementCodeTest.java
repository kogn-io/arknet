// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.pr.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link RequirementCode}: the human-readable running label
 * ({@code FR-1}, {@code NFR-7}), separate from the opaque {@code RequirementId}.
 */
class RequirementCodeTest {

    @Test
    void holdsItsValue() {
        RequirementCode code = new RequirementCode("FR-1");

        assertEquals("FR-1", code.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new RequirementCode(null));
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementCode(" "));
    }
}
