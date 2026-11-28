// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import java.util.Locale;
import java.util.Objects;

/**
 * The short excerpt a text hit shows around its match: about {@link #RADIUS} characters before and
 * after, newlines collapsed to spaces so a multi-paragraph field still renders as one report line.
 */
final class Snippet {

    /** How many characters of context to keep on each side of the match. */
    static final int RADIUS = 40;

    private static final String ELLIPSIS = "...";

    private Snippet() {
    }

    /**
     * Cuts the excerpt.
     *
     * @param text   the full literal text the match was found in
     * @param needle the search string that matched, case-insensitively
     * @return an excerpt around the first case-insensitive occurrence of {@code needle} in
     *         {@code text}, or a leading excerpt of {@code text} itself if the case-folding of the
     *         store and of this method ever disagree, so it never throws over a match the store
     *         already found
     */
    static String around(final String text, final String needle) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(needle, "needle");
        String normalized = text.replace('\n', ' ').replace('\r', ' ');
        int index = normalized.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return excerpt(normalized, 0, Math.min(normalized.length(), 2 * RADIUS + needle.length()));
        }
        int start = Math.max(0, index - RADIUS);
        int end = Math.min(normalized.length(), index + needle.length() + RADIUS);
        return excerpt(normalized, start, end);
    }

    private static String excerpt(final String normalized, final int start, final int end) {
        String prefix = start > 0 ? ELLIPSIS : "";
        String suffix = end < normalized.length() ? ELLIPSIS : "";
        return prefix + normalized.substring(start, end) + suffix;
    }
}
