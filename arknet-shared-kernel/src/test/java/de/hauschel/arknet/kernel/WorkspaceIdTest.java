// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link WorkspaceId}.
 *
 * <p>Pure, framework-free unit tests - they guard the value object's contract.</p>
 */
class WorkspaceIdTest {

    @Test
    void holdsItsValue() {
        WorkspaceId workspaceId = new WorkspaceId("team-a");

        assertEquals("team-a", workspaceId.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new WorkspaceId(null));
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceId(" "));
    }

    @Test
    void defaultConstantIsNonBlank() {
        assertEquals("default", WorkspaceId.DEFAULT.value());
    }
}
