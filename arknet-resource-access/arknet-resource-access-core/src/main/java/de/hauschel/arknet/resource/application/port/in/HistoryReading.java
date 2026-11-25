// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The history of one resource.
 *
 * @param handle       the handle as written
 * @param iri          the resource's IRI
 * @param publishState whether its draft was ever published and whether it changed since
 *                     ({@code NEVER_PUBLISHED}, {@code PUBLISHED_UNCHANGED},
 *                     {@code PUBLISHED_WITH_UNPUBLISHED_CHANGES} or {@code DELETED})
 * @param entries      the entries, oldest first
 * @param deleted      the deletion, for a deleted resource
 */
public record HistoryReading(String handle, String iri, String publishState, List<Entry> entries,
        Optional<ResourceReading.Deleted> deleted) {

    /** Checks the components. */
    public HistoryReading {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(publishState, "publishState");
        entries = List.copyOf(entries);
        Objects.requireNonNull(deleted, "deleted");
    }

    /**
     * When the resource was created: its first entry.
     *
     * @return the instant, ISO-8601, empty without entries
     */
    public Optional<String> created() {
        return entries.stream().findFirst().map(Entry::created);
    }

    /**
     * When the resource was last changed: its latest entry, a deletion included.
     *
     * @return the instant, ISO-8601, empty without entries
     */
    public Optional<String> lastChanged() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1).created());
    }

    /**
     * One entry: a published version or the tombstone of a deletion.
     *
     * @param sequence  its running number
     * @param tombstone whether it records a deletion
     * @param created   when it was recorded, ISO-8601
     * @param version   the content-addressed version it names, if any
     * @param comment   its comment, if any
     */
    public record Entry(long sequence, boolean tombstone, String created, Optional<String> version,
            Optional<String> comment) {

        /** Checks the components. */
        public Entry {
            Objects.requireNonNull(created, "created");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(comment, "comment");
        }
    }
}
