// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;

/**
 * Renders a resource's change history for {@code resource_history} (issue #251): the PROV-O
 * revisions {@link StoreReader#history} read back, oldest first, as compact text - one line per
 * revision, numbered, with its {@code prov:generatedAtTime} and the current
 * {@code arkprov:head} marked.
 *
 * <p>Pure and domain-agnostic, the same shape as {@link ResourceRenderer}: it renders whatever
 * {@link StoreReader#history} returns and takes no position on whether the resource itself
 * exists - {@code resource_history} decides that beforehand, the same way {@code resource_get}
 * does via {@link ResourceRenderer#notFoundMessage}, so an unknown handle is reported
 * identically by both tools.</p>
 */
public final class HistoryRenderer {

    private final Prefixes prefixes;

    /**
     * @param prefixes the CURIE resolver used to shorten IRIs for display
     */
    public HistoryRenderer(Prefixes prefixes) {
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    /**
     * Renders the history view of an existing resource.
     *
     * @param iri       the resource's IRI
     * @param revisions the resource's revisions, oldest first (as {@link StoreReader#history}
     *                  returns them); empty for a resource no write has ever gone through the
     *                  shared write funnel for
     * @return the history text
     */
    public String render(String iri, List<Revision> revisions) {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(revisions, "revisions");

        StringBuilder out = new StringBuilder();
        out.append(prefixes.toCurie(iri)).append("\n<").append(iri).append(">\n\n");

        if (revisions.isEmpty()) {
            out.append("# History (0)\n")
                    .append("- no revision recorded - no write has gone through the shared write funnel"
                            + " for this resource yet\n");
            return out.toString();
        }

        out.append("# History (").append(revisions.size()).append(")\n");
        int index = 1;
        for (Revision revision : revisions) {
            out.append(index).append(". ").append(revision.generatedAtTime())
                    .append("  ").append(prefixes.toCurie(revision.iri()));
            if (revision.current()) {
                out.append("  (current)");
            }
            out.append('\n');
            index++;
        }
        return out.toString();
    }
}
