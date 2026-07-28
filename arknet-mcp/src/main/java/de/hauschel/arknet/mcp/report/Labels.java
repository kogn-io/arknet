// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.Locale;

/**
 * Turns the {@code SCREAMING_SNAKE_CASE} of a domain enum into the sentence case a reader
 * expects ({@code MUST_HAVE} -&gt; {@code Must have}).
 *
 * <p>Presentation only. The enums stay the domain's spelling; nothing here is a mapping table
 * that a new enum constant could fall out of - an unknown constant humanises just as well as a
 * known one, which is why this is a formatter rather than a switch.</p>
 */
final class Labels {

    private Labels() {
    }

    /**
     * @param constant the enum constant name
     * @return the same name in sentence case with underscores as spaces
     */
    static String humanise(final String constant) {
        final String spaced = constant.replace('_', ' ').toLowerCase(Locale.ROOT);
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
