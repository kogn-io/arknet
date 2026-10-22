// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds literal, whole-word mentions of a fixed set of labels inside a text - the matching
 * engine shared by independent callers that each attach a label to a different kind of thing:
 * {@code de.hauschel.arknet.mcp.report.Glossary} labels a {@code
 * de.hauschel.arknet.ul.domain.Term}, {@link TraceabilityGraph} and {@code
 * de.hauschel.arknet.analysis.adapter.mcp.TraceabilityRenderer} label a bare term IRI read off the
 * traceability graph, {@code de.hauschel.arknet.mcp.report.CodeReferences} labels a business code.
 * This class is generic over the payload {@code T} and knows nothing about any caller's domain -
 * that is what lets the traceability read path reuse the report's matching rules without
 * depending on the report package or a bounded context's domain model.
 *
 * <p><strong>Matching is deliberately literal.</strong> Comparison is only at word boundaries;
 * {@link Pattern#UNICODE_CHARACTER_CLASS} is what makes a word boundary treat an umlaut as a word
 * character. German inflections ({@code Kunden} for {@code Kunde}) are therefore missed - the
 * alternative, matching on word stems, would silently claim {@code Kundendienst} mentions {@code
 * Kunde}, and a wrong link in an architecture model costs more than a missed one.</p>
 *
 * <p><strong>A hyphen is a word character for the boundary</strong> (kogn-io/arknet#696): {@code
 * Kontoauszug-Import} is a compound of its own and does not mention {@code Kontoauszug} the way a
 * {@code usesTerm} edge means it, so neither side of a label may touch a hyphen - the same
 * reasoning that keeps {@code Kundendienst} from mentioning {@code Kunde}, applied to the hyphen
 * German spelling puts where English closes the compound up or leaves a space.</p>
 *
 * <p><strong>Case</strong> is decided by the caller's {@link Casing}: {@link Casing#IGNORE}
 * compares case-insensitively; {@link Casing#KEEP_INITIAL_CAPITAL} - the rule for a
 * German-language text, where nouns are capitalised - keeps a label's leading capital letter
 * significant, so the modal verb {@code soll} no longer mentions the term {@code Soll}
 * (kogn-io/arknet#696). The rest of the label stays case-insensitive either way, and a label that
 * starts in lower case or with a non-letter is matched case-insensitively under both rules. The
 * price is a capitalised term at the start of a sentence written in lower case - rare in German
 * prose, where the noun keeps its capital wherever it stands.</p>
 *
 * <p>Selection: a mention that lies entirely inside another label's mention is never reported,
 * whether the two start at the same character or not ({@code Bestellung} inside {@code offene
 * Bestellung}, or inside {@code Bestellung stornieren}) - the longer label is what the text names
 * (kogn-io/arknet#696). What remains is selected left-to-right greedily over each match's
 * <em>start</em> position: of two mentions that only partially overlap - different start, ranges
 * intersect, neither contains the other - the earlier-starting one wins outright, however much
 * shorter it is, and the later one is dropped in full, not trimmed to what is left. Ties in both
 * start and length keep the order {@code items} was given in, so a caller that needs a
 * deterministic tie-break sorts its input before calling {@link #of(Collection, Function)}.</p>
 *
 * @param <T> what a label identifies
 */
public final class LabelMentions<T> {

    /**
     * How a label's letter case takes part in matching.
     */
    public enum Casing {

        /** Case-insensitive throughout - the rule for English and for a code like {@code FR-3}. */
        IGNORE,

        /**
         * A label's leading capital letter must appear as a capital in the text; everything after
         * it is case-insensitive. The rule for German, where a capital initial is what tells the
         * noun {@code Soll} from the verb {@code soll}.
         */
        KEEP_INITIAL_CAPITAL;

        /**
         * @param locale the language the matched prose is read in
         * @return {@link #KEEP_INITIAL_CAPITAL} for German, {@link #IGNORE} for every other
         *         language - noun capitalisation is a German orthography rule, and an English
         *         glossary conventionally capitalises its labels while the prose does not
         */
        public static Casing forLocale(final Locale locale) {
            return locale != null && "de".equals(locale.getLanguage()) ? KEEP_INITIAL_CAPITAL : IGNORE;
        }

        /**
         * @param languageTag a BCP-47 tag, or {@code null}/blank for "unknown"
         * @return as {@link #forLocale(Locale)}; an unknown language ignores case
         */
        public static Casing forLanguageTag(final String languageTag) {
            if (languageTag == null || languageTag.isBlank()) {
                return IGNORE;
            }
            return forLocale(Locale.forLanguageTag(languageTag));
        }
    }

    private final List<Entry<T>> entries;

    private LabelMentions(final List<Entry<T>> entries) {
        this.entries = entries;
    }

    /**
     * A case-insensitive matcher ({@link Casing#IGNORE}).
     *
     * @param items the items to make findable, in the order length ties should break
     * @param label  each item's label to search for; a {@code null} or blank label is skipped
     * @return the matcher, with entries ordered longest-label-first
     */
    public static <T> LabelMentions<T> of(final Collection<T> items, final Function<T, String> label) {
        return of(items, label, Casing.IGNORE);
    }

    /**
     * @param items  the items to make findable, in the order length ties should break
     * @param label  each item's label to search for; a {@code null} or blank label is skipped
     * @param casing how a label's letter case takes part in matching
     * @return the matcher, with entries ordered longest-label-first
     */
    public static <T> LabelMentions<T> of(
            final Collection<T> items, final Function<T, String> label, final Casing casing) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(casing, "casing");
        final List<Entry<T>> built = new ArrayList<>();
        for (final T item : items) {
            final String text = label.apply(item);
            if (text != null && !text.isBlank()) {
                built.add(new Entry<>(item, text, pattern(text, casing)));
            }
        }
        built.sort(Comparator.comparingInt((Entry<T> entry) -> entry.label().length()).reversed());
        return new LabelMentions<>(List.copyOf(built));
    }

    /**
     * Every mention in one text, left to right and non-overlapping.
     *
     * @param text the prose to scan
     * @return the mentions, in text order
     */
    public List<Mention<T>> in(final String text) {
        Objects.requireNonNull(text, "text");
        final List<Mention<T>> hits = new ArrayList<>();
        for (final Entry<T> entry : entries) {
            final Matcher matcher = entry.pattern().matcher(text);
            while (matcher.find()) {
                hits.add(new Mention<>(matcher.start(), matcher.end(), entry.item()));
            }
        }
        hits.sort(Comparator.comparingInt(Mention<T>::start)
                .thenComparing(Comparator.comparingInt((Mention<T> mention) -> mention.end()).reversed()));
        final List<Mention<T>> kept = new ArrayList<>();
        int taken = 0;
        for (final Mention<T> hit : hits) {
            if (hit.start() >= taken && !liesInsideAnother(hit, hits)) {
                kept.add(hit);
                taken = hit.end();
            }
        }
        return kept;
    }

    /**
     * Whether {@code hit} lies entirely within some strictly longer hit - checked against every
     * raw hit, not only the ones kept so far: a shorter label inside a longer label's mention is
     * not a mention of its own even when that longer mention itself loses to an earlier,
     * partially overlapping one.
     */
    private static <T> boolean liesInsideAnother(final Mention<T> hit, final List<Mention<T>> hits) {
        final int length = hit.end() - hit.start();
        for (final Mention<T> other : hits) {
            if (other.start() <= hit.start() && other.end() >= hit.end() && other.end() - other.start() > length) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param texts the texts to scan; {@code null} entries are skipped
     * @return which items are mentioned anywhere in {@code texts}, in first-appearance order
     */
    public Set<T> mentionedIn(final Collection<String> texts) {
        if (texts == null) {
            return Set.of();
        }
        final Set<T> found = new LinkedHashSet<>();
        for (final String text : texts) {
            if (text != null) {
                for (final Mention<T> mention : in(text)) {
                    found.add(mention.item());
                }
            }
        }
        return found;
    }

    /**
     * A whole-word matcher for one label.
     *
     * <p>The boundary assertion is only added on a side where the label itself starts or ends
     * with a word character - a boundary before a label like {@code (draft)} would demand the
     * opposite of what is meant and match nothing. The assertion is a look-around over word
     * characters <em>and the hyphen</em> rather than {@code \b}, so {@code Kontoauszug-Import}
     * does not mention {@code Kontoauszug}.</p>
     *
     * <p>Under {@link Casing#KEEP_INITIAL_CAPITAL} a label whose first character is an upper-case
     * letter is compiled as that character literally, followed by the rest in a case-insensitive
     * group; every other label, and every label under {@link Casing#IGNORE}, is compiled
     * case-insensitively as a whole.</p>
     */
    private static Pattern pattern(final String label, final Casing casing) {
        final String prefix = isWordChar(label.charAt(0)) ? "(?<![\\w-])" : "";
        final String suffix = isWordChar(label.charAt(label.length() - 1)) ? "(?![\\w-])" : "";
        final char first = label.charAt(0);
        if (casing == Casing.KEEP_INITIAL_CAPITAL && Character.isUpperCase(first)) {
            return Pattern.compile(prefix + Pattern.quote(String.valueOf(first))
                    + "(?iu:" + Pattern.quote(label.substring(1)) + ")" + suffix,
                    Pattern.UNICODE_CHARACTER_CLASS);
        }
        return Pattern.compile(prefix + Pattern.quote(label) + suffix,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static boolean isWordChar(final char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** One item plus the compiled matcher for its label - compiled once, not per text. */
    private record Entry<T>(T item, String label, Pattern pattern) {
    }

    /** One occurrence of an item's label in a text, as a half-open character range. */
    public record Mention<T>(int start, int end, T item) {
    }
}
