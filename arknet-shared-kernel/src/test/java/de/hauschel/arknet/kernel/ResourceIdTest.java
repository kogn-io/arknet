// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link ResourceId}.
 *
 * <p>Pure, framework-free unit tests - they guard the value object's contract.</p>
 */
class ResourceIdTest {

    @Test
    void wrapsAValidHttpsIri() {
        ResourceId id = ResourceId.of("https://w3id.org/arknet/id/some-uuid");

        assertEquals("https://w3id.org/arknet/id/some-uuid", id.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> ResourceId.of(null));
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of(" "));
    }

    @Test
    void rejectsAnIriNotStartingWithHttps() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("http://w3id.org/arknet/id/x"));
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("urn:uuid:not-https"));
    }

    @Test
    void rejectsInteriorWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/a b"));
    }

    @Test
    void rejectsLessThan() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/<x"));
    }

    @Test
    void rejectsGreaterThan() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/>x"));
    }

    @Test
    void rejectsDoubleQuote() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/\"x"));
    }

    @Test
    void rejectsOpenBrace() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/{x"));
    }

    @Test
    void rejectsCloseBrace() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/}x"));
    }

    @Test
    void rejectsPipe() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/|x"));
    }

    @Test
    void rejectsCaret() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/^x"));
    }

    @Test
    void rejectsBacktick() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/`x"));
    }

    @Test
    void rejectsBackslash() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/\\x"));
    }

    @Test
    void rejectsControlCharacter() {
        assertThrows(IllegalArgumentException.class, () -> ResourceId.of("https://w3id.org/arknet/id/x"));
    }

    @Test
    void equalityIsByValue() {
        assertEquals(ResourceId.of("https://example.org/a"), ResourceId.of("https://example.org/a"));
        assertNotEquals(ResourceId.of("https://example.org/a"), ResourceId.of("https://example.org/b"));
    }
}
