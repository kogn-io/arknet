// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.Comparator;

/**
 * Orders business codes ({@code FR-1}, {@code ADR-10}, {@code BC-2}, ...) by their prefix and
 * parsed running number, not by {@link String}'s natural (lexicographic) order - {@code "ADR-10"}
 * sorts before {@code "ADR-2"} under natural order once a project passes ten decisions. Falls
 * back to the plain lexicographic order for a code whose suffix does not parse as a number, so
 * every code still sorts somewhere instead of the comparator breaking.
 *
 * <p>Shared by every card builder in this package; {@code arknet-adr} carries its own,
 * independent copies of the same idea ({@code AdrService}/{@code KognioRdfAdrRepository}) since
 * neither hexagon this package sits above may depend on the other.</p>
 */
final class BusinessCodes {

    static final Comparator<String> ORDER = Comparator.comparing(BusinessCodes::prefix)
            .thenComparing(BusinessCodes::runningNumber, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(Comparator.naturalOrder());

    private BusinessCodes() {
    }

    /**
     * Everything before the trailing run of digits (e.g. {@code "ADR-"} for {@code "ADR-7"},
     * {@code "UC"} for {@code "UC10"} - unlike ADR/FR/NFR/BC, a use-case code carries no
     * separator before its running number). The whole code if it carries no trailing digit at
     * all.
     */
    private static String prefix(final String code) {
        return code.substring(0, digitRunStart(code));
    }

    /** @return the parsed running number, or {@code null} if the code has no trailing digit run. */
    private static Integer runningNumber(final String code) {
        final int start = digitRunStart(code);
        if (start == code.length()) {
            return null;
        }
        try {
            return Integer.parseInt(code.substring(start));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static int digitRunStart(final String code) {
        int i = code.length();
        while (i > 0 && Character.isDigit(code.charAt(i - 1))) {
            i--;
        }
        return i;
    }
}
