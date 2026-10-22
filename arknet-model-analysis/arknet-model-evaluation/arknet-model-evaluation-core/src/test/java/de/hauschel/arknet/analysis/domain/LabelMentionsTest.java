// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

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
    void aLabelWithALowercaseInitialMatchesRegardlessOfCase() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "kunde");

        assertThat(matcher.in("Kunde und kunde und KUNDE")).containsExactly(
                new Mention<>(0, 5, "term-1"),
                new Mention<>(10, 15, "term-1"),
                new Mention<>(20, 25, "term-1"));
    }

    @Test
    void aLabelWithACapitalInitialOnlyMatchesCapitalised() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "Soll");

        assertThat(matcher.in("Er soll zahlen.")).isEmpty();
        assertThat(matcher.in("Soll zahlen.")).containsExactly(new Mention<>(0, 4, "term-1"));
    }

    @Test
    void onlyTheInitialIsCaseSensitive() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "Kunde");

        assertThat(matcher.in("Der KUNDE und der kunde")).containsExactly(new Mention<>(4, 9, "term-1"));
    }

    @Test
    void aCapitalUmlautInitialIsCaseSensitiveToo() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "\u00c4nderung");

        assertThat(matcher.in("die \u00e4nderung")).isEmpty();
        assertThat(matcher.in("die \u00c4nderung")).containsExactly(new Mention<>(4, 12, "term-1"));
    }

    @Test
    void aLabelStartingWithADigitOrSymbolStaysCaseInsensitive() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "(Draft)");

        assertThat(matcher.in("a (draft) b")).containsExactly(new Mention<>(2, 9, "term-1"));
    }

    @Test
    void aHyphenatedCompoundDoesNotMentionItsFirstPart() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "Kontoauszug");

        assertThat(matcher.in("Kontoauszug-Import l\u00e4uft")).isEmpty();
        assertThat(matcher.in("der Import-Kontoauszug")).isEmpty();
    }

    @Test
    void aCombiningMarkIsAWordCharacter() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "Konto");

        assertThat(matcher.in("Konto\u0308r")).isEmpty();
    }

    @Test
    void aHyphenatedLabelMatchesItself() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "Kontoauszug-Import");

        assertThat(matcher.in("Der Kontoauszug-Import l\u00e4uft"))
                .containsExactly(new Mention<>(4, 22, "term-1"));
    }

    @Test
    void spacedDashesAndPunctuationAreStillWordBoundaries() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "Kontoauszug");

        assertThat(matcher.in("Kontoauszug - Import")).hasSize(1);
        assertThat(matcher.in("Kontoauszug, Import")).hasSize(1);
        assertThat(matcher.in("Kontoauszug.")).hasSize(1);
        assertThat(matcher.in("(Kontoauszug)")).hasSize(1);
    }

    @Test
    void aCodeWithAHyphenMatchesAsAWholeToken() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("FR-7"), id -> id);

        assertThat(matcher.in("Kunde (FR-7)")).hasSize(1);
        assertThat(matcher.in("FR-7: Kunde")).hasSize(1);
        assertThat(matcher.in("FR-7-Nachfolger")).isEmpty();
        assertThat(LabelMentions.of(List.of("FR-1"), id -> id).in("siehe FR-12")).isEmpty();
        assertThat(matcher.in("fr-7")).isEmpty();
    }

    /**
     * A shorter mention lying completely inside a longer one is dropped, also when it does not
     * sit at the longer label's start.
     */
    @Test
    void aMentionEnclosedInALongerMentionIsDropped() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("term-1", "term-2"),
                id -> "term-1".equals(id) ? "Konto Auszug Import" : "Auszug");

        assertThat(matcher.in("Der Konto Auszug Import l\u00e4uft"))
                .containsExactly(new Mention<>(4, 23, "term-1"));
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
}
