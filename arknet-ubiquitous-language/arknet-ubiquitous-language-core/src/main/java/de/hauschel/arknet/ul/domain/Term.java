// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

/**
 * A single term of the ubiquitous language under management - one entry of a
 * glossary.
 *
 * <p>Value object of the ubiquitous-language component. Maps onto a W3C SKOS
 * concept: the {@link #id} carries the stable, opaque identity (the concept IRI it wraps,
 * minted once and never derived from a business string), {@link #code} carries the
 * human-readable running label ({@code TERM-1}, stored as {@code dcterms:identifier}),
 * {@link #prefLabel} maps to {@code skos:prefLabel} (the term itself) and {@link #definition}
 * to {@code skos:definition}. All invariants are enforced in the compact constructor; instances
 * are immutable.</p>
 *
 * @param id         stable, opaque identity (the concept IRI), independent of both the code and
 *                   the label
 * @param code       human-readable business label (e.g. {@code TERM-1}); maps to
 *                   {@code dcterms:identifier}
 * @param prefLabel  the preferred label, i.e. the term itself; maps to
 *                   {@code skos:prefLabel}
 * @param definition the meaning of the term; maps to {@code skos:definition}
 * @param broader    optional {@code skos:broader} reference to the term it specializes
 *                   (its superordinate, single-valued term); maps to {@code skos:broader}.
 *                   Optional (may be {@code null}). Only this forward direction is ever
 *                   asserted as a triple - {@code skos:narrower} is left to a reader, never
 *                   written a second time by hand, mirroring the ADR bounded context's
 *                   {@code supersedes}/{@code supersededBy} pair
 */
public record Term(TermId id, TermCode code, String prefLabel, String definition, TermCode broader) {

    public Term {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(prefLabel, "prefLabel");
        Objects.requireNonNull(definition, "definition");
        if (prefLabel.isBlank()) {
            throw new IllegalArgumentException("prefLabel must not be blank");
        }
        if (definition.isBlank()) {
            throw new IllegalArgumentException("definition must not be blank");
        }
        if (broader != null && broader.equals(code)) {
            throw new IllegalArgumentException("a term must not be its own broader term: " + code.value());
        }
    }

    /**
     * Convenience constructor for a term with no {@code skos:broader} relation - equivalent to
     * passing {@code null} for {@link #broader} explicitly. Kept so the many call sites that
     * predate {@code broader} (issue #252) do not all have to restate a trailing {@code null}.
     */
    public Term(TermId id, TermCode code, String prefLabel, String definition) {
        this(id, code, prefLabel, definition, null);
    }
}
