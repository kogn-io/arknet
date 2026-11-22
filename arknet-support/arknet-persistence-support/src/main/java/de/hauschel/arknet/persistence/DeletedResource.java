// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.List;
import java.util.Objects;

/**
 * A resource whose latest history entry is a tombstone: what the library versioning still records
 * of it - when it was deleted, the comment of that entry and the statements of the last version it
 * published. Backend-neutral, like {@link VersionEntry}.
 *
 * <p>The statements are the root statements of the last published version, re-keyed to the
 * resource's own IRI. They are empty when the resource was never published (only a store-first
 * resource, deleted without a prior publish, has no content behind its tombstone).</p>
 *
 * @param iri            the deleted resource's IRI
 * @param deletedAt      the instant the tombstone was recorded, ISO-8601
 * @param comment        the comment recorded with the tombstone, or {@code null}
 * @param lastStatements the root statements of the last published version; empty if none exists
 */
public record DeletedResource(String iri, String deletedAt, String comment, List<Triple> lastStatements) {

    public DeletedResource {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(deletedAt, "deletedAt");
        lastStatements = List.copyOf(Objects.requireNonNull(lastStatements, "lastStatements"));
    }

    /**
     * @return the comment recorded with the tombstone, if any
     */
    public java.util.Optional<String> optionalComment() {
        return java.util.Optional.ofNullable(comment);
    }

    /**
     * @return the business code the resource carried ({@code dcterms:identifier}) in its last
     *         version, if the version is known
     */
    public java.util.Optional<String> identifier() {
        return new StoreResource(iri, lastStatements).identifier();
    }
}
