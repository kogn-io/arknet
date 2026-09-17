// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.search;

import java.util.Objects;
import java.util.Optional;

/**
 * One {@code text_search} match (kogn-io/arknet#594 Part 1), resolved to the resource it is
 * reported under.
 *
 * @param ownerHandle the display handle of the resource the hit is grouped under - either the
 *                     matched subject itself, or, for a subject with no {@code dcterms:identifier}
 *                     of its own (an {@code arkreq:Step}, an {@code arkreq:AcceptanceCriterion} and
 *                     similar aggregate-internal value objects), the nearest owner found by
 *                     climbing incoming edges (see {@link SearchHitResolver})
 * @param field        the matched predicate, as a CURIE
 * @param viaEdge      the CURIE of the edge climbed from {@link #ownerHandle} to reach the field
 *                     that actually matched, or empty if the match sits directly on the owner
 * @param languageTag  the matched literal's language tag, or empty if it carries none
 * @param snippet      a short excerpt of the matched literal around the match
 */
record SearchHit(String ownerHandle, String field, Optional<String> viaEdge, Optional<String> languageTag,
        String snippet) {

    SearchHit {
        Objects.requireNonNull(ownerHandle, "ownerHandle");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(viaEdge, "viaEdge");
        Objects.requireNonNull(languageTag, "languageTag");
        Objects.requireNonNull(snippet, "snippet");
    }
}
