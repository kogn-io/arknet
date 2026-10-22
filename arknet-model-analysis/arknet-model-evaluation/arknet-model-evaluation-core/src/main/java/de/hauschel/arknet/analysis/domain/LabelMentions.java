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
 * Finds literal, whole-word mentions of a fixed set of labels inside a text, case-insensitive
 * except for the noun-capitalisation rule below -
 * the matching engine shared by two independent callers that each attach a label to a different
 * kind of thing: {@code de.hauschel.arknet.mcp.report.Glossary} labels a {@code
 * de.hauschel.arknet.ul.domain.Term}, {@code de.hauschel.arknet.analysis.adapter.mcp.TraceabilityRenderer}
 * labels a bare term IRI read off the traceability graph. This class is generic over the payload
 * {@code T} and knows nothing about either caller's domain - that is what lets the traceability
 * read path reuse the report's matching rules without depending on the report
 * package or a bounded context's domain model.
 *
 * <p><strong>Matching is deliberately literal.</strong> Comparison is case-insensitive and only
 * at word boundaries; {@link Pattern#UNICODE_CHARACTER_CLASS} is what makes a word boundary treat
 * an umlaut as a word character. German inflections ({@code Kunden} for {@code Kunde}) are
 * therefore missed - the alternative, matching on word stems, would silently claim {@code
 * Kundendienst} mentions {@code Kunde}, and a wrong link in an architecture model costs more than
 * a missed one.</p>
 *
 * <p><strong>A hyphen is part of the word</strong> (kogn-io/arknet#696), in every language. A
 * label is not matched when the character directly before its first word character or directly
 * after its last word character is a hyphen ({@code -} U+002D, {@code U+2010} hyphen, {@code
 * U+2011} non-breaking hyphen): {@code Kontoauszug-Import}, {@code Import-Kontoauszug} and the
 * suspended hyphen of {@code Kontoauszug- und Buchungsimport} are other words and do not name
 * {@code Kontoauszug} in the sense of a {@code usesTerm} edge. En and em dashes and a spaced
 * {@code Kontoauszug - Import} stay separators. A label that contains a hyphen itself ({@code
 * E-Mail}, {@code FR-12}) matches as before, and {@code FR-1} still does not match inside
 * {@code FR-12}.</p>
 *
 * <p><strong>Nouns are capitalised in some languages</strong> (kogn-io/arknet#696). In
 * {@link #in(String, String)} and {@link #mentionedIn(Collection, String)} a text tagged with a
 * language that capitalises nouns - see {@link #capitalisesNouns(String)} - matches
 * a single-word label that starts with an uppercase letter only where the occurrence starts with an uppercase
 * letter too; the remaining characters stay case-insensitive, so {@code KUNDE} still names {@code
 * Kunde} while the modal verb {@code soll} does not name the term {@code Soll}. A multi-word label ({@code Offener Posten}), a label starting
 * with a lowercase letter and one starting with a non-letter are unchanged, and so is every text with another or no
 * language tag, and the plain {@link #in(String)}: a global rule would stop {@code the customer}
 * from naming an English label {@code Customer}. A sentence that starts with a non-noun equal to
 * a label ({@code Soll ...}) is ambiguous and still matches.</p>
 *
 * <p>Selection is left-to-right greedy over each match's <em>start</em> position, not its
 * length: of two mentions that begin at the same character, the longer one wins (entries are
 * tried longest-label-first, so a shorter match starting where a longer one already did never
 * gets recorded), but of two mentions that only partially overlap - different start, ranges
 * intersect - the earlier-starting one wins outright, however much shorter it is. A later match
 * that starts anywhere inside an already-claimed range is dropped in full, not trimmed to what is
 * left. Ties in both start and length keep the order {@code items} was given in, so a caller
 * that needs a deterministic tie-break sorts its input before calling
 * {@link #of(Collection, Function)}.</p>
 *
 * @param <T> what a label identifies
 */
public final class LabelMentions<T> {

    /**
     * The primary language subtags that capitalise nouns - the one place the case rule reads. A
     * further language joins by adding its subtag here.
     */
    private static final Set<String> NOUN_CAPITALISING_LANGUAGES = Set.of("de");

    /** Hyphen-minus, hyphen (U+2010) and non-breaking hyphen (U+2011), as regex character-class content. */
    private static final String JOINERS = "\\-\\x{2010}\\x{2011}";

    private final List<Entry<T>> entries;

    private LabelMentions(final List<Entry<T>> entries) {
        this.entries = entries;
    }

    /**
     * @param items the items to make findable, in the order length ties should break
     * @param label  each item's label to search for; a {@code null} or blank label is skipped
     * @return the matcher, with entries ordered longest-label-first
     */
    public static <T> LabelMentions<T> of(final Collection<T> items, final Function<T, String> label) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(label, "label");
        final List<Entry<T>> built = new ArrayList<>();
        for (final T item : items) {
            final String text = label.apply(item);
            if (text != null && !text.isBlank()) {
                built.add(new Entry<>(item, text, pattern(text),
                        capitalisedSingleWord(text)));
            }
        }
        built.sort(Comparator.comparingInt((Entry<T> entry) -> entry.label().length()).reversed());
        return new LabelMentions<>(List.copyOf(built));
    }

    /**
     * @param languageTag a BCP-47 language tag, or {@code null}
     * @return whether text in that language capitalises its nouns, so that a lowercase occurrence
     *         is not an uppercase label; decided on the primary subtag, case-insensitively
     *         ({@code de}, {@code de-AT} and {@code DE-ch} all count), {@code false} for
     *         {@code null}, blank and every other language
     */
    public static boolean capitalisesNouns(final String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return false;
        }
        final String trimmed = languageTag.strip();
        final int dash = Math.min(
                trimmed.indexOf('-') < 0 ? trimmed.length() : trimmed.indexOf('-'),
                trimmed.indexOf('_') < 0 ? trimmed.length() : trimmed.indexOf('_'));
        return NOUN_CAPITALISING_LANGUAGES.contains(trimmed.substring(0, dash).toLowerCase(Locale.ROOT));
    }

    /**
     * Every mention in one text of unknown language, left to right and non-overlapping, fully
     * case-insensitive.
     *
     * @param text the prose to scan
     * @return the mentions, in text order
     */
    public List<Mention<T>> in(final String text) {
        return in(text, null);
    }

    /**
     * Every mention in one text, left to right and non-overlapping.
     *
     * @param text        the prose to scan
     * @param languageTag the language the text is written in (BCP-47), or {@code null} if unknown;
     *                    in a language that {@linkplain #capitalisesNouns(String) capitalises
     *                    nouns} a single-word label starting with an uppercase letter matches only an
     *                    uppercase-initial occurrence
     * @return the mentions, in text order
     */
    public List<Mention<T>> in(final String text, final String languageTag) {
        Objects.requireNonNull(text, "text");
        final boolean matchInitialCase = capitalisesNouns(languageTag);
        final List<Mention<T>> hits = new ArrayList<>();
        for (final Entry<T> entry : entries) {
            final Matcher matcher = entry.pattern().matcher(text);
            int from = 0;
            while (from <= text.length() && matcher.find(from)) {
                if (matchInitialCase && entry.capitalised()
                        && !Character.isUpperCase(text.codePointAt(matcher.start()))) {
                    from = matcher.start() + 1;
                    continue;
                }
                hits.add(new Mention<>(matcher.start(), matcher.end(), entry.item()));
                from = matcher.end();
            }
        }
        hits.sort(Comparator.comparingInt(Mention<T>::start)
                .thenComparing(Comparator.comparingInt((Mention<T> mention) -> mention.end()).reversed()));
        final List<Mention<T>> kept = new ArrayList<>();
        int taken = 0;
        for (final Mention<T> hit : hits) {
            if (hit.start() >= taken) {
                kept.add(hit);
                taken = hit.end();
            }
        }
        return kept;
    }

    /**
     * @param texts the texts to scan, of unknown language; {@code null} entries are skipped
     * @return which items are mentioned anywhere in {@code texts}, in first-appearance order
     */
    public Set<T> mentionedIn(final Collection<String> texts) {
        return mentionedIn(texts, null);
    }

    /**
     * @param texts       the texts to scan, all in one language; {@code null} entries are skipped
     * @param languageTag that language (BCP-47), or {@code null} if unknown - see
     *                    {@link #in(String, String)}
     * @return which items are mentioned anywhere in {@code texts}, in first-appearance order
     */
    public Set<T> mentionedIn(final Collection<String> texts, final String languageTag) {
        if (texts == null) {
            return Set.of();
        }
        final Set<T> found = new LinkedHashSet<>();
        for (final String text : texts) {
            if (text != null) {
                for (final Mention<T> mention : in(text, languageTag)) {
                    found.add(mention.item());
                }
            }
        }
        return found;
    }

    /**
     * A whole-word, case-insensitive matcher for one label.
     *
     * <p>The boundary assertion is only added on a side where the label itself starts or ends
     * with a word character - a boundary before a label like {@code (draft)} would demand the
     * opposite of what is meant and match nothing. It is a lookaround rather than {@code \b} so
     * that a hyphen counts as a word character there ({@link #JOINERS}).</p>
     */
    private static Pattern pattern(final String label) {
        final String prefix = isWordChar(label.charAt(0)) ? "(?<![\\w" + JOINERS + "])" : "";
        final String suffix = isWordChar(label.charAt(label.length() - 1)) ? "(?![\\w" + JOINERS + "])" : "";
        return Pattern.compile(prefix + Pattern.quote(label) + suffix,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    /**
     * Whether the case rule applies to {@code label}: one word starting with an uppercase letter.
     * A multi-word label is excluded because a German headword capitalises its leading adjective
     * ({@code Offener Posten}) while running text writes it lowercase - requiring the capital
     * would drop the longer hit and let a shorter term inside it surface instead.
     */
    private static boolean capitalisedSingleWord(final String label) {
        return Character.isUpperCase(label.codePointAt(0))
                && label.codePoints().noneMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp));
    }

    private static boolean isWordChar(final char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * One item plus the compiled matcher for its label - compiled once, not per text.
     *
     * @param capitalised whether the label is one word starting with an uppercase letter, which is what
     *                    the case rule of a noun-capitalising language looks at
     */
    private record Entry<T>(T item, String label, Pattern pattern, boolean capitalised) {
    }

    /** One occurrence of an item's label in a text, as a half-open character range. */
    public record Mention<T>(int start, int end, T item) {
    }
}
