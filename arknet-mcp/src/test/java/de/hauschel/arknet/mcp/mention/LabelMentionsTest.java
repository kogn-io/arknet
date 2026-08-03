// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.mention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.mcp.mention.LabelMentions.Mention;

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
