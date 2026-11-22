// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.Objects;

/**
 * One entry of a resource's history as the library versioning records it, read back for
 * {@code resource_history}: a publish that released a version, or the tombstone of a delete.
 * Backend-neutral, like {@link Triple}/{@link RdfNode}.
 *
 * @param iri       the entry's own IRI
 * @param sequence  the entry's position in the resource's history, starting at 1
 * @param tombstone {@code true} for the entry a delete recorded
 * @param created   the instant the entry was recorded, ISO-8601
 * @param cid       the content-addressed IRI of the version the entry names, or {@code null} for a
 *                  tombstone of a resource that was never published
 * @param comment   the comment recorded with the entry, or {@code null}
 */
public record VersionEntry(String iri, long sequence, boolean tombstone, String created, String cid,
        String comment) {

    public VersionEntry {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(created, "created");
    }
}
