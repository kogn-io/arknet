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
 * as they would hide a real sentence end. Matching is case-insensitive, except for two case-sensitive patterns outside
 * {@link #ABBREVIATIONS}: {@code s.} is lower-case only and never follows a number ({@code 2 s.} is
 * a unit), and {@code e. g.} has a lower-case {@code g} (the legal form {@code e. G.} is none).
 * The listed abbreviations need a char in front that is neither letter, digit
 * nor apostrophe, so a word merely ending in {@code s} and the possessive {@code 's.} are not
 * {@code s.}. Inner spaces of a multi-part abbreviation are optional and may be a no-break space ({@code z.B.} and {@code z. B.}).</p>
 */
final class SentenceBoundaries {

    /** The abbreviations whose dots do not end a sentence, written with single spaces between parts. */
    static final List<String> ABBREVIATIONS = List.of(
            "lt.", "vgl.", "z. B.", "d. h.", "bzw.", "ggf.", "i. d. R.", "ca.", "evtl.",
            "i. e.", "cf.");

    private static final String BREAKS = ".!?;:\n\r";

    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?<![\\p{L}\\p{N}'\u2019])(?:"
                    + ABBREVIATIONS.stream()
                            .map(a -> Pattern.quote(a).replace(" ", "\\E[ \\t\u00A0\u202F]?\\Q"))
                            .collect(Collectors.joining("|"))
                    + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** {@code e. g.} case-sensitive on the {@code g}, so the German legal form {@code e. G.} ends a sentence. */
    private static final Pattern EXAMPLE = Pattern.compile(
            "(?<![\\p{L}\\p{N}'\u2019])[eE]\\.[ \\t\u00A0\u202F]?g\\.");

    /**
     * {@code s.} (see) is matched case-sensitively and never straight after a number: {@code 2 s.}
     * is a unit and {@code S.} may close a sentence.
     */
    private static final Pattern SEE = Pattern.compile(
            "(?<![\\p{L}\\p{N}'\u2019])(?<!\\p{N}[ \\t\u00A0\u202F])s\\.");

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
        cover(ABBREVIATION.matcher(text), covered);
        cover(SEE.matcher(text), covered);
        cover(EXAMPLE.matcher(text), covered);
        return covered;
    }

    private static void cover(final Matcher matcher, final boolean[] covered) {
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                covered[i] = true;
            }
        }
    }
}
