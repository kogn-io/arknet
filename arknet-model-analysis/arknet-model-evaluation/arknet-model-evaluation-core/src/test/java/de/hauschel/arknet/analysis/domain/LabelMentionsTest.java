// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import de.hauschel.arknet.analysis.domain.LabelMentions.Mention;

/**
 * {@code de.hauschel.arknet.mcp.report.GlossaryTest} already pins the German-inflection and
 * overlap behaviour this class implements, for the {@code Term} payload. These tests instead pin
 * the same behaviour for a bare {@code String} payload - the shape the traceability read path
 * needs - so both callers are covered without either depending on the other's
 * domain type.
 */
class LabelMentionsTest {

    @Test
    void findsALabelRegardlessOfCase() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "Kunde");

        assertThat(matcher.in("kunde und Kunde")).containsExactly(
                new Mention<>(0, 5, "term-1"),
                new Mention<>(10, 15, "term-1"));
        assertThat(matcher.in("kunde und Kunde", "de")).containsExactly(new Mention<>(10, 15, "term-1"));
    }

    @Test
    void prefersTheLongerLabelWhenTwoItemsOverlap() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("term-2", "term-9"),
                id -> "term-9".equals(id) ? "offene Bestellung" : "Bestellung");

        assertThat(matcher.in("Eine offene Bestellung wartet."))
                .containsExactly(new Mention<>(5, 22, "term-9"));
    }

    /**
     * The qualifier the class Javadoc now spells out (issue #150): "the longer label wins" only
     * holds between mentions that start at the same position. Here the two labels overlap but
     * start at different positions ("Kunde Auftrag" at 0, "Auftrag Positionen" at 6) - the
     * earlier-starting match wins outright, even though the later, non-selected one is longer,
     * and the longer label is dropped entirely rather than trimmed to what is left.
     */
    @Test
    void anEarlierStartingMentionWinsOverALongerLaterStartingOverlap() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("term-1", "term-2"),
                id -> "term-1".equals(id) ? "Kunde Auftrag" : "Auftrag Positionen");

        assertThat(matcher.in("Kunde Auftrag Positionen werden geprueft."))
                .containsExactly(new Mention<>(0, 13, "term-1"));
    }

    @Test
    void mentionedInReturnsItemsInFirstAppearanceOrderAcrossTexts() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("term-1", "term-2"),
                id -> "term-1".equals(id) ? "Kunde" : "Bestellung");

        assertThat(matcher.mentionedIn(List.of("Die Bestellung wartet.", "Der Kunde zahlt.")))
                .containsExactly("term-2", "term-1");
    }

    @Test
    void skipsAnItemWithoutALabel() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> null);

        assertThat(matcher.in("Kunde")).isEmpty();
    }

    @Test
    void anEmptyMatcherFindsNothing() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of(), id -> id);

        assertThat(matcher.in("Kunde")).isEmpty();
        assertThat(matcher.mentionedIn(List.of("Kunde"))).isEqualTo(Set.of());
    }

    private static LabelMentions<String> single(final String label) {
        return LabelMentions.of(List.of("term-1"), id -> label);
    }

    @Test
    void aCapitalisedLabelIgnoresALowercaseOccurrenceInGermanText() {
        final LabelMentions<String> matcher = single("Soll");

        assertThat(matcher.in("Der Wert soll positiv sein.", "de")).isEmpty();
        assertThat(matcher.in("Soll und Haben.", "de")).containsExactly(new Mention<>(0, 4, "term-1"));
    }

    @Test
    void aCapitalisedLabelStillMatchesAllCapsAndAnyGermanRegionTag() {
        final LabelMentions<String> matcher = single("Kunde");

        assertThat(matcher.in("DER KUNDE zahlt.", "de")).containsExactly(new Mention<>(4, 9, "term-1"));
        assertThat(matcher.in("kunde", "de-AT")).isEmpty();
        assertThat(matcher.in("kunde", "DE-de")).isEmpty();
        assertThat(matcher.in("Kunde", "de-CH")).hasSize(1);
    }

    @Test
    void aCapitalisedLabelStaysCaseInsensitiveInTextWithoutANounCapitalisingLanguage() {
        final LabelMentions<String> matcher = single("Customer");

        assertThat(matcher.in("the customer pays", "en")).containsExactly(new Mention<>(4, 12, "term-1"));
        assertThat(matcher.in("the customer pays", null)).hasSize(1);
        assertThat(matcher.in("the customer pays")).hasSize(1);
        assertThat(matcher.in("the customer pays", "fr")).hasSize(1);
    }

    @Test
    void aLowercaseLabelKeepsMatchingBothCasesInGermanText() {
        final LabelMentions<String> matcher = single("soll");

        assertThat(matcher.in("Soll und soll", "de")).hasSize(2);
    }

    @Test
    void aLabelStartingWithANonLetterIsNotCaseConstrained() {
        assertThat(single("(Draft)").in("ein (draft) hier", "de")).hasSize(1);
        assertThat(single("3D-Modell").in("das 3d-modell", "de")).hasSize(1);
    }

    @Test
    void mentionedInHonoursTheLanguageOfTheTexts() {
        final LabelMentions<String> matcher = single("Soll");

        assertThat(matcher.mentionedIn(List.of("es soll"), "de")).isEmpty();
        assertThat(matcher.mentionedIn(List.of("es soll"), null)).containsExactly("term-1");
        assertThat(matcher.mentionedIn(List.of("es soll"))).containsExactly("term-1");
    }

    @Test
    void aHyphenatedCompoundDoesNotNameItsPart() {
        final LabelMentions<String> matcher = single("Kontoauszug");

        assertThat(matcher.in("Der Kontoauszug-Import laeuft.")).isEmpty();
        assertThat(matcher.in("Der Import-Kontoauszug laeuft.")).isEmpty();
        assertThat(matcher.in("Kontoauszug- und Buchungsimport")).isEmpty();
        assertThat(matcher.in("Kontoauszug\u2010Import und Kontoauszug\u2011Import")).isEmpty();
        assertThat(matcher.in("Kontoauszug - Import")).hasSize(1);
        assertThat(matcher.in("Kontoauszug\u2013Import und Kontoauszug\u2014Import")).hasSize(2);
        assertThat(matcher.in("Der Kontoauszug.")).hasSize(1);
        assertThat(matcher.in("Der Kontoauszug-Import und der Kontoauszug.")).containsExactly(
                new Mention<>(31, 42, "term-1"));
    }

    @Test
    void theHyphenRuleHoldsInGermanCaseAwareMatchingToo() {
        assertThat(single("Kontoauszug").in("Kontoauszug-Import", "de")).isEmpty();
    }

    @Test
    void aLabelWithAnInternalHyphenStillMatches() {
        assertThat(single("E-Mail").in("Eine E-Mail kommt.")).hasSize(1);
        assertThat(single("E-Mail").in("Die E-Mail-Adresse")).isEmpty();
        final LabelMentions<String> codes = LabelMentions.of(List.of("fr-1", "fr-12"), id -> id.toUpperCase());
        assertThat(codes.in("siehe FR-12 und (FR-1)")).containsExactly(
                new Mention<>(6, 11, "fr-12"), new Mention<>(17, 21, "fr-1"));
    }

    @Test
    void aLabelWithRegexMetacharactersIsStillMatchedLiterally() {
        assertThat(single("(draft)").in("ein (draft) hier")).containsExactly(new Mention<>(4, 11, "term-1"));
        assertThat(single("a.b").in("axb a.b")).containsExactly(new Mention<>(4, 7, "term-1"));
    }

    @Test
    void theLongerLabelStillWinsWhenAShorterOneSitsInsideItsSpanAfterItsStart() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("a", "b"), id -> "a".equals(id) ? "Bestellung" : "offene Bestellung");

        assertThat(matcher.in("die offene Bestellung", "de")).containsExactly(new Mention<>(4, 21, "b"));
    }

    /** German headwords capitalise the adjective of a multi-word label, running text does not. */
    @Test
    void aMultiWordLabelWithACapitalisedAdjectiveStillWinsOverItsLowercaseOccurrenceInGermanText() {
        final LabelMentions<String> energie = LabelMentions.of(
                List.of("long", "short"), id -> "long".equals(id) ? "Erneuerbare Energie" : "Energie");
        assertThat(energie.in("Wir nutzen erneuerbare Energie.", "de")).containsExactly(new Mention<>(11, 30, "long"));
        assertThat(energie.in("Wir nutzen erneuerbare Energie.", null)).containsExactly(new Mention<>(11, 30, "long"));

        final LabelMentions<String> posten = LabelMentions.of(
                List.of("long", "short"), id -> "long".equals(id) ? "Offener Posten" : "Posten");
        assertThat(posten.in("Jeder offener Posten wird gemahnt.", "de")).containsExactly(new Mention<>(6, 20, "long"));
    }

    @Test
    void theLongerCapitalisedMultiWordLabelWinsOverAShorterOneInsideItsSpan() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("a", "b"), id -> "a".equals(id) ? "Bestellung" : "Offene Bestellung");

        assertThat(matcher.in("die Offene Bestellung", "de")).containsExactly(new Mention<>(4, 21, "b"));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "de,true", "DE,true", "de-CH-1996,true", "de_DE,true", "deu,false", "den,false", "'',false", "en,false",
        "NULL,false"})
    void capitalisesNounsDecidesOnThePrimarySubtag(final String tag, final boolean expected) {
        assertThat(LabelMentions.capitalisesNouns(tag)).isEqualTo(expected);
    }

    /** A no-break space (U+00A0, U+2007, U+202F) separates the words of a label like a blank does. */
    @Test
    void aMultiWordLabelWithANoBreakSpaceIsStillMultiWord() {
        for (final String space : new String[] {"\u00a0", "\u2007", "\u202f"}) {
            final LabelMentions<String> posten = LabelMentions.of(
                    List.of("long", "short"), id -> "long".equals(id) ? "Offener" + space + "Posten" : "Posten");

            assertThat(posten.in("Jeder offener" + space + "Posten wird gemahnt.", "de"))
                    .containsExactly(new Mention<>(6, 20, "long"));
        }
    }
}
