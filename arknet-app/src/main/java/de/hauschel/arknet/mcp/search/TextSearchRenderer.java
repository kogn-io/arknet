// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders {@code text_search}'s (kogn-io/arknet#594 Part 1) resolved hits as plain text, grouped
 * by the resource each hit is reported under - never as a flat, ungrouped hit list, since a
 * multi-field match on the same resource (its title and one of its acceptance criteria, say)
 * would otherwise read as two unrelated resources.
 */
final class TextSearchRenderer {

    private TextSearchRenderer() {
    }

    /**
     * @param query          the search string, echoed back so the report is self-contained
     * @param totalHitCount  how many statements matched in the store, before the {@code hits}
     *                       list was capped
     * @param hits           the hits actually rendered (at most {@code cap} of them)
     * @param cap            the cap {@code hits} was truncated to, if it was
     * @return the rendered report
     */
    static String render(String query, int totalHitCount, List<SearchHit> hits, int cap) {
        if (totalHitCount == 0) {
            return "No hits for \"" + query + "\".\n";
        }
        Map<String, List<SearchHit>> byOwner = new LinkedHashMap<>();
        for (SearchHit hit : hits) {
            byOwner.computeIfAbsent(hit.ownerHandle(), owner -> new ArrayList<>()).add(hit);
        }
        StringBuilder out = new StringBuilder();
        out.append(hits.size()).append(" hit(s) for \"").append(query).append("\" across ")
                .append(byOwner.size()).append(" resource(s)");
        if (totalHitCount > hits.size()) {
            out.append(" (showing the first ").append(cap).append(" of ").append(totalHitCount)
                    .append(" matching statements - narrow the query for the rest)");
        }
        out.append(":\n\n");
        byOwner.forEach((owner, ownerHits) -> {
            out.append("## ").append(owner).append('\n');
            ownerHits.forEach(hit -> out.append("- ").append(renderField(hit)).append(": ")
                    .append(hit.snippet()).append('\n'));
            out.append('\n');
        });
        return out.toString();
    }

    private static String renderField(SearchHit hit) {
        StringBuilder field = new StringBuilder(hit.field());
        hit.viaEdge().ifPresent(edge -> field.append(" (via ").append(edge).append(')'));
        hit.languageTag().ifPresent(tag -> field.append(" [").append(tag).append(']'));
        return field.toString();
    }
}
