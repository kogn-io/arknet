// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.Objects;

/**
 * One resource an accepted document changed.
 *
 * @param kind whether the resource was created, updated or deleted
 * @param iri  the resource's IRI; for a created resource the one the store minted
 * @param code the resource's business code, {@code null} for a type without codes
 */
public record SubmittedChange(Kind kind, String iri, String code) {

    /**
     * Checks the required fields.
     *
     * @param kind the kind of change
     * @param iri  the resource's IRI
     * @param code the business code or {@code null}
     */
    public SubmittedChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(iri, "iri");
    }

    /** The kind of a change. */
    public enum Kind {
        /** The document created the resource. */
        CREATED,
        /** The document changed fields or children of the resource. */
        UPDATED,
        /** The document deleted the resource; its history and its code stay. */
        DELETED
    }
}
