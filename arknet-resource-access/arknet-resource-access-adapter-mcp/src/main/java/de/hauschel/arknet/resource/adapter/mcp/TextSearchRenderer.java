// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hauschel.arknet.resource.application.port.in.TextHit;
import de.hauschel.arknet.resource.application.port.in.TextSearchResult;

/**
 * Renders what {@code FindText} returns as the text of {@code text_search}: the matches grouped
 * under the resource each belongs to - never as a flat list, since a match on a title and one on an
 * acceptance criterion of the same resource would read as two unrelated resources - and, if the
 * result was cut, how many are left.
 */
final class TextSearchRenderer {

    private TextSearchRenderer() {
    }

    /**
     * Renders a search result.
     *
     * @param result the result
     * @return the text
     */
    static String render(final TextSearchResult result) {
        if (result.total() == 0) {
            return "No hits for \"" + result.query() + "\".\n";
        }
        Map<String, List<TextHit>> byOwner = new LinkedHashMap<>();
        result.hits().forEach(hit -> byOwner.computeIfAbsent(hit.handle(), owner -> new ArrayList<>()).add(hit));
        StringBuilder out = new StringBuilder();
        out.append(result.hits().size()).append(" hit(s) for \"").append(result.query()).append("\" across ")
                .append(byOwner.size()).append(" resource(s):\n\n");
        byOwner.forEach((owner, hits) -> {
            out.append("## ").append(owner).append('\n');
            hits.forEach(hit -> out.append("- ").append(field(hit)).append(": ").append(hit.snippet()).append('\n'));
            out.append('\n');
        });
        if (result.notShown() > 0) {
            out.append("NOT SHOWN: ").append(result.notShown()).append(" more of ").append(result.total())
                    .append(" matching statements (at most ").append(result.limit())
                    .append(" per call). Narrow the query or pass a type to see them.\n");
        }
        return out.toString();
    }

    private static String field(final TextHit hit) {
        StringBuilder field = new StringBuilder(hit.field());
        hit.viaField().ifPresent(edge -> field.append(" (via ").append(edge).append(')'));
        hit.language().ifPresent(tag -> field.append(" [").append(tag).append(']'));
        return field.toString();
    }
}
