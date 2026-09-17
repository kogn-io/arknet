// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.search;

import java.util.Locale;
import java.util.Objects;

/**
 * Builds the short excerpt a {@code text_search} hit shows around its match (kogn-io/arknet#594
 * Part 1): about {@link #RADIUS} characters before and after, newlines collapsed to spaces so a
 * multi-paragraph field still renders as one report line.
 */
final class Snippet {

    /** How many characters of context to keep on each side of the match. */
    static final int RADIUS = 40;

    private static final String ELLIPSIS = "...";

    private Snippet() {
    }

    /**
     * @param text   the full literal text the match was found in
     * @param needle the search string that matched, case-insensitively
     * @return an excerpt around the first case-insensitive occurrence of {@code needle} in
     *         {@code text}, or a leading excerpt of {@code text} itself if the two backends'
     *         case-folding ever disagree (the SPARQL engine's {@code LCASE} vs. this method's
     *         {@link String#toLowerCase(Locale)}) so this method never throws over a match the
     *         query itself already found
     */
    static String around(String text, String needle) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(needle, "needle");
        String normalized = collapseNewlines(text);
        int index = normalized.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return excerpt(normalized, 0, Math.min(normalized.length(), 2 * RADIUS + needle.length()));
        }
        int start = Math.max(0, index - RADIUS);
        int end = Math.min(normalized.length(), index + needle.length() + RADIUS);
        return excerpt(normalized, start, end);
    }

    private static String excerpt(String normalized, int start, int end) {
        String body = normalized.substring(start, end);
        String prefix = start > 0 ? ELLIPSIS : "";
        String suffix = end < normalized.length() ? ELLIPSIS : "";
        return prefix + body + suffix;
    }

    private static String collapseNewlines(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
    }
}
