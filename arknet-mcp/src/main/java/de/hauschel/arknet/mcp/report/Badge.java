// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.Objects;

/**
 * A short, enum-like fact shown as a pill next to a card's headline: a requirement's status
 * and priority, a bounded context's subdomain, a glossary term's actor kind.
 *
 * @param kind  the family this badge belongs to (e.g. {@code status}, {@code priority}); the
 *              renderer derives the pill's colour from it, so an unknown kind still renders,
 *              just in the neutral style
 * @param value the value to show (e.g. {@code Accepted}, {@code Must})
 */
public record Badge(String kind, String value) {

    public Badge {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
    }
}
