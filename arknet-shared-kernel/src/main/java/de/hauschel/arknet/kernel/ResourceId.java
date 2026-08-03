// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.regex.Pattern;

/**
 * Opaque identity of a resource stored in the shared Linked Data substrate.
 *
 * <p>Identity is a bare, opaque LD {@link ResourceId}: the {@code https://} form it wraps is a
 * kernel detail (how it is minted, see {@link UuidResourceIdFactory}); the domain sees only the
 * object, never a string it must parse or reconstruct. Bounded contexts wrap this newtype in
 * their own identity type (e.g. a requirements {@code RequirementId}) for type safety; a
 * human-readable business label ({@code FR-1}) is a separate concern, carried alongside the
 * identity rather than derived from it.</p>
 *
 * <p>Sealed to {@link DefaultResourceId}: {@link #of(String)} is the only way to obtain an
 * instance, so every {@link ResourceId} in the system was either minted by a
 * {@link ResourceIdFactory} or wraps an IRI actually read back from the store.</p>
 */
public sealed interface ResourceId permits DefaultResourceId {

    /** {@code https://<host>/...} with no interior whitespace, checked by {@link #of(String)}. */
    Pattern VALID_IRI = Pattern.compile("^https://\\S+$");

    /**
     * Characters an RFC 3987 IRI excludes and that every RDF surface (Turtle, N-Triples, SPARQL)
     * rejects alike, checked one-by-one by {@link #of(String)} on top of {@link #VALID_IRI}: any
     * codepoint {@code <= 0x20} (control characters and space - a superset of the whitespace
     * {@link #VALID_IRI} already excludes) plus {@code < > " { } | ^ ` \}.
     */
    String FORBIDDEN_IRI_CHARACTERS = "<>\"{}|^`\\";

    /**
     * Wraps an already-existing IRI as a {@link ResourceId}, e.g. one read back from the store.
     *
     * @param iri the candidate IRI string; must be non-blank, start with {@code https://},
     *            contain no whitespace and none of {@link #FORBIDDEN_IRI_CHARACTERS} or control
     *            characters
     * @return the wrapping {@link ResourceId}
     * @throws IllegalArgumentException if {@code iri} does not satisfy the above shape
     * @throws NullPointerException if {@code iri} is null
     */
    static ResourceId of(String iri) {
        return new DefaultResourceId(iri);
    }

    /** @return the wrapped IRI string */
    String value();
}
