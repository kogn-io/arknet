// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link UnresolvedProjectAnchorException}.
 *
 * <p>Round-trips {@code anchor()} for both constructors: nothing else in the repository calls or
 * asserts it, so an accidental swap of the constructor's two {@code String} parameters (anchor,
 * message) would otherwise compile cleanly and go unnoticed.</p>
 */
class UnresolvedProjectAnchorExceptionTest {

    @Test
    void twoArgConstructorRoundTripsANonNullAnchor() {
        UnresolvedProjectAnchorException e = new UnresolvedProjectAnchorException("/home/a/arknet", "no project");

        assertEquals("/home/a/arknet", e.anchor());
        assertEquals("no project", e.getMessage());
    }

    @Test
    void twoArgConstructorRoundTripsANullAnchor() {
        UnresolvedProjectAnchorException e = new UnresolvedProjectAnchorException(null, "no anchor at all");

        assertNull(e.anchor());
        assertEquals("no anchor at all", e.getMessage());
    }

    @Test
    void threeArgConstructorRoundTripsANonNullAnchor() {
        RuntimeException cause = new RuntimeException("unknown anchor");

        UnresolvedProjectAnchorException e =
                new UnresolvedProjectAnchorException("/home/a/arknet", "no project", cause);

        assertEquals("/home/a/arknet", e.anchor());
        assertEquals("no project", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void threeArgConstructorRoundTripsANullAnchor() {
        RuntimeException cause = new RuntimeException("unknown anchor");

        UnresolvedProjectAnchorException e = new UnresolvedProjectAnchorException(null, "no anchor at all", cause);

        assertNull(e.anchor());
        assertEquals("no anchor at all", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void rejectsANullMessage() {
        assertThrows(NullPointerException.class,
                () -> new UnresolvedProjectAnchorException("/home/a/arknet", null));
    }

    @Test
    void rejectsANullMessageWithACause() {
        assertThrows(NullPointerException.class,
                () -> new UnresolvedProjectAnchorException("/home/a/arknet", null, new RuntimeException()));
    }
}
