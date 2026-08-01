// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link LocalizedLiteral}.
 *
 * <p>Pure, framework-free unit tests - they guard the value object's contract (issue #95).</p>
 */
class LocalizedLiteralTest {

    @Test
    void holdsItsValueAndLanguageTag() {
        LocalizedLiteral literal = new LocalizedLiteral("Kunde", "de");

        assertEquals("Kunde", literal.value());
        assertEquals("de", literal.languageTag());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new LocalizedLiteral(null, "de"));
    }

    @Test
    void acceptsANullLanguageTagOnTheCanonicalConstructor() {
        LocalizedLiteral literal = new LocalizedLiteral("Kunde", null);

        assertNull(literal.languageTag());
    }

    @Test
    void untaggedCreatesAPlainLiteral() {
        LocalizedLiteral literal = LocalizedLiteral.untagged("Kunde");

        assertEquals("Kunde", literal.value());
        assertNull(literal.languageTag());
        assertTrue(literal.isUntagged());
    }

    @Test
    void taggedCreatesALanguageTaggedLiteral() {
        LocalizedLiteral literal = LocalizedLiteral.tagged("Kunde", "de");

        assertEquals("Kunde", literal.value());
        assertEquals("de", literal.languageTag());
        assertFalse(literal.isUntagged());
    }

    @Test
    void taggedRejectsANullLanguageTag() {
        assertThrows(NullPointerException.class, () -> LocalizedLiteral.tagged("Kunde", null));
    }
}
