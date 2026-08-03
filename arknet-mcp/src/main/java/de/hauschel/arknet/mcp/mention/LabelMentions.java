// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.mention;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds literal, case-insensitive, whole-word mentions of a fixed set of labels inside a text -
 * the matching engine shared by two independent callers that each attach a label to a different
 * kind of thing: {@code de.hauschel.arknet.mcp.report.Glossary} labels a {@code
 * de.hauschel.arknet.ul.domain.Term}, {@code de.hauschel.arknet.mcp.trace.TraceabilityRenderer}
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
                built.add(new Entry<>(item, text, pattern(text)));
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
            if (hit.start() >= taken) {
                kept.add(hit);
                taken = hit.end();
            }
        }
        return kept;
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
     * A whole-word, case-insensitive matcher for one label.
     *
     * <p>The boundary assertion is only added on a side where the label itself starts or ends
     * with a word character - {@code \b} before a label like {@code (draft)} would demand the
     * opposite of what is meant and match nothing.</p>
     */
    private static Pattern pattern(final String label) {
        final String prefix = isWordChar(label.charAt(0)) ? "\\b" : "";
        final String suffix = isWordChar(label.charAt(label.length() - 1)) ? "\\b" : "";
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
