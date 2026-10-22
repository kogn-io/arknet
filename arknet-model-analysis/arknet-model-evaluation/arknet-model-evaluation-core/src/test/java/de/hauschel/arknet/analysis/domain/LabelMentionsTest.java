// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.analysis.domain.LabelMentions.Casing;
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
     * The qualifier the class Javadoc spells out (issue #150): between two mentions that overlap
     * only partially - "Kunde Auftrag" at 0, "Auftrag Positionen" at 6, neither containing the
     * other - the earlier-starting match wins outright, even though the later, non-selected one
     * is longer, and the longer label is dropped entirely rather than trimmed to what is left.
     */
    @Test
    void anEarlierStartingMentionWinsOverALongerLaterStartingOverlap() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("term-1", "term-2"),
                id -> "term-1".equals(id) ? "Kunde Auftrag" : "Auftrag Positionen");

        assertThat(matcher.in("Kunde Auftrag Positionen werden geprueft."))
                .containsExactly(new Mention<>(0, 13, "term-1"));
    }

    /**
     * kogn-io/arknet#696, rule 3: a shorter label inside a longer label's mention is never a
     * mention of its own - here even though the longer mention ("Auftrag Positionen") itself loses
     * to the earlier-starting "Kunde Auftrag". Before this rule the greedy pass reported
     * "Positionen" at 14, because by then the claimed range had ended at 13.
     */
    @Test
    void aLabelInsideALongerLabelsMentionIsNotReportedEvenWhenThatLongerMentionLosesToAnEarlierOne() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("term-1", "term-2", "term-3"),
                id -> switch (id) {
                    case "term-1" -> "Kunde Auftrag";
                    case "term-2" -> "Auftrag Positionen";
                    default -> "Positionen";
                });

        assertThat(matcher.in("Kunde Auftrag Positionen werden geprueft."))
                .containsExactly(new Mention<>(0, 13, "term-1"));
    }

    /** kogn-io/arknet#696, rule 3, the plain case: the shorter label starts later inside the longer one. */
    @Test
    void aLabelInsideALongerLabelsMentionIsNotReportedWhenItStartsLater() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("term-1", "term-2"),
                id -> "term-1".equals(id) ? "Position" : "Bestellung mit Position");

        assertThat(matcher.in("Eine Bestellung mit Position liegt vor."))
                .containsExactly(new Mention<>(5, 28, "term-2"));
    }

    /**
     * kogn-io/arknet#696, rule 2: a hyphen is a word character for the boundary. "Kontoauszug-Import"
     * is a compound of its own, not a mention of "Kontoauszug"; a spaced dash or a hyphen inside
     * the label itself keeps matching.
     */
    @Test
    void doesNotMatchInsideAHyphenatedCompound() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "Kontoauszug");

        assertThat(matcher.in("Der Kontoauszug-Import und der Kunden-Kontoauszug.")).isEmpty();
        assertThat(matcher.in("Der Kontoauszug - Import.")).containsExactly(new Mention<>(4, 15, "term-1"));
    }

    @Test
    void matchesALabelThatItselfContainsAHyphen() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "E-Mail");

        assertThat(matcher.in("Eine E-Mail geht raus, keine E-Mail-Adresse."))
                .containsExactly(new Mention<>(5, 11, "term-1"));
    }

    /**
     * kogn-io/arknet#696, rule 1: under {@link Casing#KEEP_INITIAL_CAPITAL} the modal verb "soll"
     * is not the term "Soll"; the rest of the label stays case-insensitive, and a label starting
     * in lower case is matched case-insensitively as before.
     */
    @Test
    void keepsALabelsInitialCapitalSignificantWhenAsked() {
        final LabelMentions<String> matcher = LabelMentions.of(
                List.of("term-1", "term-2"),
                id -> "term-1".equals(id) ? "Soll" : "offene Bestellung",
                Casing.KEEP_INITIAL_CAPITAL);

        assertThat(matcher.in("Das Soll soll stimmen, SOLL auch."))
                .containsExactly(new Mention<>(4, 8, "term-1"), new Mention<>(23, 27, "term-1"));
        assertThat(matcher.in("Eine Offene Bestellung und eine offene bestellung."))
                .containsExactly(new Mention<>(5, 22, "term-2"), new Mention<>(32, 49, "term-2"));
    }

    @Test
    void ignoresCaseUnderTheDefaultRule() {
        final LabelMentions<String> matcher = LabelMentions.of(List.of("term-1"), id -> "Soll", Casing.IGNORE);

        assertThat(matcher.in("Das Soll soll stimmen.")).hasSize(2);
    }

    /** Noun capitalisation is a German rule; every other language, and an unknown one, ignores case. */
    @Test
    void choosesTheCasingRuleByLanguage() {
        assertThat(Casing.forLocale(Locale.GERMAN)).isEqualTo(Casing.KEEP_INITIAL_CAPITAL);
        assertThat(Casing.forLocale(Locale.forLanguageTag("de-AT"))).isEqualTo(Casing.KEEP_INITIAL_CAPITAL);
        assertThat(Casing.forLocale(Locale.ENGLISH)).isEqualTo(Casing.IGNORE);
        assertThat(Casing.forLocale(null)).isEqualTo(Casing.IGNORE);
        assertThat(Casing.forLanguageTag("de")).isEqualTo(Casing.KEEP_INITIAL_CAPITAL);
        assertThat(Casing.forLanguageTag("en")).isEqualTo(Casing.IGNORE);
        assertThat(Casing.forLanguageTag(null)).isEqualTo(Casing.IGNORE);
        assertThat(Casing.forLanguageTag(" ")).isEqualTo(Casing.IGNORE);
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
