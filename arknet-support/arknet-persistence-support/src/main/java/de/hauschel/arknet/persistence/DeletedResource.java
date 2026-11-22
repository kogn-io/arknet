// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A deleted resource, as far as the store still records it. Backend-neutral, like {@link VersionEntry}.
 *
 * <p>A resource deleted through the library versioning has a tombstone as its latest history entry:
 * the record carries when it was deleted, the comment of that entry and the root statements of the
 * last version it published, re-keyed to the resource's own IRI. The statements are empty when the
 * resource was never published (only a store-first resource, deleted without a prior publish, has
 * no content behind its tombstone).</p>
 *
 * <p>A resource deleted before the library versioning existed has no tombstone: the startup
 * migration kept only its business code, on the IRI of its last revision in the earlier layout.
 * Its record carries that IRI and the code and nothing else - no deletion instant, no comment, no
 * statements, because the store does not hold them.</p>
 *
 * @param iri            the deleted resource's IRI, or for a deletion before versioning the IRI its
 *                       retained code is recorded on
 * @param deletedAt      the instant the tombstone was recorded, ISO-8601, or {@code null} for a
 *                       deletion before versioning
 * @param comment        the comment recorded with the tombstone, or {@code null}
 * @param retainedCode   the business code recorded for a deletion before versioning, or {@code null}
 * @param lastStatements the root statements of the last published version; empty if none exists
 */
public record DeletedResource(String iri, String deletedAt, String comment, String retainedCode,
        List<Triple> lastStatements) {

    public DeletedResource {
        Objects.requireNonNull(iri, "iri");
        if (deletedAt == null && retainedCode == null) {
            throw new IllegalArgumentException("a deletion record needs its deletion instant or its retained code");
        }
        lastStatements = List.copyOf(Objects.requireNonNull(lastStatements, "lastStatements"));
    }

    /**
     * A resource deleted through the library versioning, read from its tombstone.
     *
     * @param iri            the deleted resource's IRI
     * @param deletedAt      the instant the tombstone was recorded, ISO-8601
     * @param comment        the comment recorded with the tombstone, or {@code null}
     * @param lastStatements the root statements of the last published version; empty if none exists
     * @return the deletion record
     */
    public static DeletedResource fromTombstone(String iri, String deletedAt, String comment,
            List<Triple> lastStatements) {
        return new DeletedResource(iri, Objects.requireNonNull(deletedAt, "deletedAt"), comment, null,
                lastStatements);
    }

    /**
     * A resource deleted before the library versioning existed, of which only its code was kept.
     *
     * @param holderIri the IRI the retained code is recorded on
     * @param code      the retained business code
     * @return the deletion record
     */
    public static DeletedResource beforeVersioning(String holderIri, String code) {
        return new DeletedResource(holderIri, null, null, Objects.requireNonNull(code, "code"), List.of());
    }

    /**
     * @return {@code true} if the resource was deleted before the library versioning existed, so
     *         neither its deletion instant nor its content is recorded
     */
    public boolean deletedBeforeVersioning() {
        return deletedAt == null;
    }

    /**
     * @return the instant the tombstone was recorded, empty for a deletion before versioning
     */
    public Optional<String> optionalDeletedAt() {
        return Optional.ofNullable(deletedAt);
    }

    /**
     * @return the comment recorded with the tombstone, if any
     */
    public Optional<String> optionalComment() {
        return Optional.ofNullable(comment);
    }

    /**
     * @return the business code the resource carried: the retained code for a deletion before
     *         versioning, otherwise {@code dcterms:identifier} of its last version, if that is known
     */
    public Optional<String> identifier() {
        if (retainedCode != null) {
            return Optional.of(retainedCode);
        }
        return new StoreResource(iri, lastStatements).identifier();
    }
}
