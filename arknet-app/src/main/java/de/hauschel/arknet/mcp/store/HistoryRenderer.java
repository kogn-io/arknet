// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.persistence.DeletedResource;
import de.hauschel.arknet.persistence.Prefixes;
import de.hauschel.arknet.persistence.ResourceRenderer;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.VersionEntry;

/**
 * Renders a resource's history for {@code resource_history}: its publish state and the entries
 * the library versioning recorded for it ({@link StoreReader#history}), oldest first, as compact
 * text - one line per entry, numbered, with its creation instant, its kind (a publish or the
 * tombstone of a delete), the content-addressed version it names and its comment.
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
     * @param iri          the resource's IRI
     * @param publishState the resource's publish state, as {@link StoreReader#publishState} names it
     * @param entries      the resource's history entries, oldest first (as
     *                     {@link StoreReader#history} returns them); empty for a resource never
     *                     written through the tools
     * @return the history text
     */
    public String render(String iri, String publishState, List<VersionEntry> entries) {
        return render(iri, publishState, entries, Optional.empty());
    }

    /**
     * Renders the history view; for a deleted resource the deletion record - what it was, when it
     * went - precedes the entries, so the history of a gone resource answers "what was deleted".
     *
     * @param iri          the resource's IRI
     * @param publishState the resource's publish state, as {@link StoreReader#publishState} names it
     * @param entries      the resource's history entries, oldest first
     * @param deleted      the deletion record if the resource is currently deleted
     * @return the history text
     */
    public String render(String iri, String publishState, List<VersionEntry> entries,
            Optional<DeletedResource> deleted) {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(publishState, "publishState");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(deleted, "deleted");

        StringBuilder out = new StringBuilder();
        out.append(prefixes.toCurie(iri)).append("\n<").append(iri).append(">\n\n");
        out.append("Publish state: ").append(publishState).append("\n\n");
        deleted.ifPresent(record -> out.append(new ResourceRenderer(prefixes).renderDeletedBody(record)).append('\n'));

        if (entries.isEmpty()) {
            out.append("# History (0)\n")
                    .append("- never published - every write through the tools publishes a version, so this"
                            + " resource was only ever written store-first\n");
            return out.toString();
        }

        out.append("# History (").append(entries.size()).append(")\n");
        for (VersionEntry entry : entries) {
            out.append(entry.sequence()).append(". ").append(entry.created())
                    .append("  ").append(entry.tombstone() ? "tombstone" : "publish");
            if (entry.cid() != null) {
                out.append("  ").append(prefixes.toCurie(entry.cid()));
            }
            if (entry.comment() != null) {
                out.append("  \"").append(entry.comment()).append('"');
            }
            out.append('\n');
        }
        return out.toString();
    }
}
