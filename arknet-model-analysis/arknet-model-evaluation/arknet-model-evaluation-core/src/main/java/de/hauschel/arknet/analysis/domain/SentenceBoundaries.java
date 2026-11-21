// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sentence boundaries for the reference rule of {@link TraceabilityGraph#classifiedMentions()}.
 *
 * <p>A sentence ends at {@code .} {@code !} {@code ?} {@code ;} {@code :} or a line break, except
 * that the dots of a known abbreviation do not end it. One list serves every text, whatever
 * language tag it is stored under. It holds only reference abbreviations that stand before a
 * continuing clause; words that often close a sentence ({@code etc.}, {@code usw.}) are left out,
 * as they would hide a real sentence end. Matching is case-insensitive and needs a non-letter in
 * front, so a word merely ending in {@code s} is not {@code s.}. Inner spaces of a multi-part
 * abbreviation are optional ({@code z.B.} and {@code z. B.}).</p>
 */
final class SentenceBoundaries {

    /** The abbreviations whose dots do not end a sentence, written with single spaces between parts. */
    static final List<String> ABBREVIATIONS = List.of(
            "lt.", "vgl.", "s.", "z. B.", "d. h.", "bzw.", "ggf.", "u. a.", "i. d. R.", "ca.", "evtl.",
            "e.g.", "i.e.", "cf.");

    private static final String BREAKS = ".!?;:\n\r";

    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:"
                    + ABBREVIATIONS.stream()
                            .map(a -> Pattern.quote(a).replace(" ", "\\E[ \\t]?\\Q"))
                            .collect(Collectors.joining("|"))
                    + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private SentenceBoundaries() {
    }

    /** Whether no sentence break lies between two mentions of one text. */
    static boolean sameSentence(String text, int firstStart, int firstEnd, int secondStart, int secondEnd) {
        final int from = Math.min(firstEnd, secondEnd);
        final int to = Math.max(firstStart, secondStart);
        final boolean[] covered = abbreviationDots(text);
        for (int i = from; i < to; i++) {
            if (BREAKS.indexOf(text.charAt(i)) >= 0 && !covered[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean[] abbreviationDots(String text) {
        final boolean[] covered = new boolean[text.length()];
        final Matcher matcher = ABBREVIATION.matcher(text);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                covered[i] = true;
            }
        }
        return covered;
    }
}
