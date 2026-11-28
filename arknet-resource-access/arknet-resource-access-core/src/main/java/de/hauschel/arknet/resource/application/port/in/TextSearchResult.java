// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;

/**
 * What {@link FindText} found.
 *
 * @param query the text searched for
 * @param total how many matching statements there are, before the list was cut
 * @param limit the most matches a result of this kind shows
 * @param hits  the matches shown, at most {@code limit}, in a stable order
 */
public record TextSearchResult(String query, int total, int limit, List<TextHit> hits) {

    /** Checks the components and copies the list. */
    public TextSearchResult {
        Objects.requireNonNull(query, "query");
        hits = List.copyOf(hits);
    }

    /**
     * How many matches are not shown.
     *
     * @return the number, 0 if the result is complete
     */
    public int notShown() {
        return Math.max(0, total - hits.size());
    }
}
