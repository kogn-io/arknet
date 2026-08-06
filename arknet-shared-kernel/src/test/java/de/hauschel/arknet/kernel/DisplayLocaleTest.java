// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the display-language fallback chain of {@link DisplayLocale}.
 *
 * <p>Pure, framework-free - the chain is the isolable unit worth testing directly, without
 * going through the whole out-adapter.</p>
 */
class DisplayLocaleTest {

    private static final DisplayLocale DE_THEN_EN =
            new DisplayLocale(Locale.GERMAN, Locale.ENGLISH);

    @Test
    void rejectsNullLocales() {
        assertThrows(NullPointerException.class, () -> new DisplayLocale(null, Locale.ENGLISH));
        assertThrows(NullPointerException.class, () -> new DisplayLocale(Locale.ENGLISH, null));
    }

    // ---- Step 1: requested locale wins ------------------------------------------------

    @Test
    void picksTheRequestedLanguageWhenPresent() {
        Optional<LocalizedLiteral> chosen = DE_THEN_EN.select(List.of(
                LocalizedLiteral.tagged("Kunde", "de"),
                LocalizedLiteral.tagged("Customer", "en")));

        assertEquals(Optional.of(LocalizedLiteral.tagged("Kunde", "de")), chosen);
    }

    @Test
    void matchesTheLanguageSubtagIgnoringRegionAndCase() {
        Optional<LocalizedLiteral> chosen = DE_THEN_EN.select(List.of(
                LocalizedLiteral.tagged("Kunde", "DE-AT"),
                LocalizedLiteral.tagged("Customer", "en")));

        assertEquals("Kunde", chosen.orElseThrow().value());
    }

    @Test
    void prefersTheExactBcp47TagOverALexicallySmallerSiblingRegion() {
        DisplayLocale enUs = new DisplayLocale(Locale.forLanguageTag("en-US"), Locale.ENGLISH);

        Optional<LocalizedLiteral> chosen = enUs.select(List.of(
                LocalizedLiteral.tagged("Customer UK", "en-GB"),
                LocalizedLiteral.tagged("Customer US", "en-US")));

        assertEquals("Customer US", chosen.orElseThrow().value());
    }

    // ---- Step 2: system default -------------------------------------------------------

    @Test
    void fallsBackToTheSystemDefaultWhenTheRequestedLanguageIsAbsent() {
        Optional<LocalizedLiteral> chosen = DE_THEN_EN.select(List.of(
                LocalizedLiteral.tagged("Customer", "en"),
                LocalizedLiteral.tagged("Client", "fr")));

        assertEquals(Optional.of(LocalizedLiteral.tagged("Customer", "en")), chosen);
    }

    // ---- Step 3: untagged literal -----------------------------------------------------

    @Test
    void fallsBackToAnUntaggedLiteralWhenNeitherLocaleMatches() {
        DisplayLocale deThenFr = new DisplayLocale(Locale.GERMAN, Locale.FRENCH);

        Optional<LocalizedLiteral> chosen = deThenFr.select(List.of(
                LocalizedLiteral.tagged("Customer", "en"),
                LocalizedLiteral.untagged("Kunde")));

        assertEquals(Optional.of(LocalizedLiteral.untagged("Kunde")), chosen);
    }

    @Test
    void untaggedLiteralIsTodaysNormalCase() {
        // term_add writes a plain literal; the default en/en preference must still surface it.
        Optional<LocalizedLiteral> chosen = DisplayLocale.DEFAULT.select(List.of(
                LocalizedLiteral.untagged("Gutschrift")));

        assertEquals(Optional.of(LocalizedLiteral.untagged("Gutschrift")), chosen);
    }

    // ---- Step 4: deterministic arbitrary ----------------------------------------------

    @Test
    void fallsBackToADeterministicArbitraryLiteralAsLastResort() {
        // Neither the requested (de) nor the default (en) language, and nothing untagged:
        // the chain must still yield a value - never empty, never a throw.
        DisplayLocale deThenEn = DE_THEN_EN;
        List<LocalizedLiteral> candidates = List.of(
                LocalizedLiteral.tagged("Client", "fr"),
                LocalizedLiteral.tagged("Cliente", "es"));

        Optional<LocalizedLiteral> first = deThenEn.select(candidates);
        Optional<LocalizedLiteral> second = deThenEn.select(List.of(
                LocalizedLiteral.tagged("Cliente", "es"),
                LocalizedLiteral.tagged("Client", "fr")));

        assertTrue(first.isPresent());
        // "es" sorts before "fr" - deterministic regardless of input order.
        assertEquals(LocalizedLiteral.tagged("Cliente", "es"), first.orElseThrow());
        assertEquals(first, second);
    }

    @Test
    void isDeterministicAmongSeveralUntaggedLiterals() {
        // Store-first data can break "one label per language"; step 3 must still be stable.
        List<LocalizedLiteral> candidates = List.of(
                LocalizedLiteral.untagged("Zebra"),
                LocalizedLiteral.untagged("Alpha"));

        assertEquals(LocalizedLiteral.untagged("Alpha"), DisplayLocale.DEFAULT.select(candidates).orElseThrow());
    }

    @Test
    void isDeterministicAmongSeveralLiteralsInTheRequestedLanguage() {
        // Two prefLabels with the same tag violate SKOS S14 but are store-first reachable.
        List<LocalizedLiteral> candidates = List.of(
                LocalizedLiteral.tagged("Kunde", "de"),
                LocalizedLiteral.tagged("Auftraggeber", "de"));

        assertEquals("Auftraggeber", DE_THEN_EN.select(candidates).orElseThrow().value());
    }

    // ---- Boundary conditions ----------------------------------------------------------

    @Test
    void returnsEmptyForNoCandidatesInsteadOfThrowing() {
        assertEquals(Optional.empty(), DE_THEN_EN.select(List.of()));
    }

    @Test
    void rejectsNullCandidateCollection() {
        assertThrows(NullPointerException.class, () -> DE_THEN_EN.select(null));
    }

    // ---- withRequestedOverride ---------------------------------------------------------

    @Test
    void withRequestedOverrideReplacesOnlyTheRequestedTier() {
        DisplayLocale overridden = DE_THEN_EN.withRequestedOverride("fr");

        assertEquals(Locale.FRENCH, overridden.requested());
        assertEquals(Locale.ENGLISH, overridden.systemDefault());
    }

    @Test
    void withRequestedOverrideLeavesTheInstanceUnchangedWhenGivenNullOrBlank() {
        assertEquals(DE_THEN_EN, DE_THEN_EN.withRequestedOverride(null));
        assertEquals(DE_THEN_EN, DE_THEN_EN.withRequestedOverride(""));
        assertEquals(DE_THEN_EN, DE_THEN_EN.withRequestedOverride("   "));
    }
}
