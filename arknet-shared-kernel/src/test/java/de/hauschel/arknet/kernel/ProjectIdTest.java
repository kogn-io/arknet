// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link ProjectId}.
 *
 * <p>Pure, framework-free unit tests - they guard the value object's contract.</p>
 */
class ProjectIdTest {

    @Test
    void holdsItsValue() {
        ProjectId projectId = new ProjectId("team-a");

        assertEquals("team-a", projectId.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new ProjectId(null));
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectId(" "));
    }

    @Test
    void defaultConstantIsNonBlank() {
        assertEquals("default", ProjectId.DEFAULT.value());
    }

    /**
     * Moved here from {@code ProjectServiceTest} along with the type itself: the reserved value is
     * an invariant of {@link ProjectId}, so it belongs to the type's own test rather than to the
     * test of a service that happens to construct one.
     */
    @Test
    void rejectsTheReservedSystemDatasetValue() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectId(ProjectId.RESERVED_SYSTEM_DATASET));
    }
}
