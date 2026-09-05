// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import java.util.List;
import java.util.Locale;

/**
 * The checks {@code store_check} can run. One tool with a selector rather than one tool per check
 * (kogn-io/arknet#412), for the same reason {@code store_overview}/{@code resource_get} are two
 * generic tools rather than one per bounded context: a check is a way of reading the
 * store, not a bounded context of its own, and a tool per check would grow the tool surface every
 * agent pays for on every call by one entry per rule.
 *
 * <p>Only {@link #LANGUAGE} exists today. The remaining check-shaped tools ({@code orphan_check},
 * and whatever follows) are folded in separately (kogn-io/arknet#473); this enum is the seam they
 * arrive at, not a placeholder for them.</p>
 */
public enum StoreCheckKind {

    /**
     * Which fields do not carry every language the project undertakes to maintain
     * ({@code arkprj:maintainedLanguage}, kogn-io/arknet#412).
     */
    LANGUAGE;

    /**
     * Parses one caller-supplied selector, case-insensitively.
     *
     * @param value the raw tool argument
     * @return the matching check
     * @throws IllegalArgumentException naming every allowed value - an agent that guessed wrong
     *                                  must be told what it may pass, not merely that it failed
     */
    public static StoreCheckKind parse(final String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown check '" + value + "': expected one of "
                    + String.join(", ", names()) + ".", e);
        }
    }

    /** @return every check name, for an error message or a tool description. */
    public static List<String> names() {
        return List.of(values()).stream().map(Enum::name).toList();
    }
}
