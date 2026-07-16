package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link SparqlTerms}.
 */
class SparqlTermsTest {

    @Test
    void escapesBackslashFirst() {
        assertEquals("a\\\\b", SparqlTerms.escape("a\\b"));
    }

    @Test
    void escapesQuote() {
        assertEquals("a\\\"b", SparqlTerms.escape("a\"b"));
    }

    @Test
    void escapesLineFeed() {
        assertEquals("a\\nb", SparqlTerms.escape("a\nb"));
    }

    @Test
    void escapesCarriageReturn() {
        assertEquals("a\\rb", SparqlTerms.escape("a\rb"));
    }

    @Test
    void escapesTab() {
        assertEquals("a\\tb", SparqlTerms.escape("a\tb"));
    }

    @Test
    void escapesCombinationWithoutDoubleEscaping() {
        assertEquals("\\\\\\\"\\n", SparqlTerms.escape("\\\"\n"));
    }

    @Test
    void leavesOrdinaryTextUnchanged() {
        assertEquals("TERM-1", SparqlTerms.escape("TERM-1"));
    }

    @Test
    void acceptsOrdinaryIri() {
        assertTrue(SparqlTerms.isValidIriReference("https://example.org/thing"));
    }

    @Test
    void rejectsLessThan() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/<thing"));
    }

    @Test
    void rejectsGreaterThan() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/>thing"));
    }

    @Test
    void rejectsDoubleQuote() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/\"thing"));
    }

    @Test
    void rejectsOpenBrace() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/{thing"));
    }

    @Test
    void rejectsCloseBrace() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/}thing"));
    }

    @Test
    void rejectsPipe() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/|thing"));
    }

    @Test
    void rejectsCaret() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/^thing"));
    }

    @Test
    void rejectsBacktick() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/`thing"));
    }

    @Test
    void rejectsBackslash() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/\\thing"));
    }

    @Test
    void rejectsSpace() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/ thing"));
    }

    @Test
    void rejectsControlCharacter() {
        assertFalse(SparqlTerms.isValidIriReference("https://example.org/thing"));
    }

    @Test
    void iriRefWrapsValidIri() {
        assertEquals("<https://example.org/thing>", SparqlTerms.iriRef("https://example.org/thing"));
    }

    @Test
    void iriRefThrowsForInvalidIri() {
        assertThrows(IllegalArgumentException.class, () -> SparqlTerms.iriRef("https://example.org/<thing"));
    }
}
