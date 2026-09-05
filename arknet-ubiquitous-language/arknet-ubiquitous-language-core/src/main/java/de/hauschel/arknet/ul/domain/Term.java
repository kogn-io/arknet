// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 *                   {@code relatedTo} pair (a decision's own edge plus a reverse read for the
 *                   other direction)
 * @param related    the terms this one is associatively (non-hierarchically) related to
 *                   ({@code skos:related}, kogn-io/arknet#420) - never {@code null}, possibly
 *                   empty, never holding duplicates or this term's own code. Multi-valued, unlike
 *                   {@link #broader}, and symmetric: {@code skos:related} is an
 *                   {@code owl:SymmetricProperty}, so only one direction is ever asserted as a
 *                   triple while every read path merges the reverse direction back in - what this
 *                   list holds therefore depends on where the record comes from. Off a driving
 *                   in-port ({@code term_get}/{@code term_list}/{@code term_update}) it is the
 *                   merged, symmetric view; off the driven {@link
 *                   de.hauschel.arknet.ul.application.port.out.TermRepository} it is the forward
 *                   direction alone, which is also the only direction a write ever asserts. Same
 *                   split the ADR bounded context draws between its {@code Adr#relatedTo} and its
 *                   {@code AdrDetail#relatedTo}
 */
public record Term(TermId id, TermCode code, String prefLabel, String definition, TermCode broader,
        List<TermCode> related) {

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
        related = related == null ? List.of() : List.copyOf(related);
        Set<TermCode> distinct = new HashSet<>(related);
        if (distinct.size() != related.size()) {
            throw new IllegalArgumentException("related must not name the same term twice: " + related);
        }
        if (distinct.contains(code)) {
            throw new IllegalArgumentException("a term must not be related to itself: " + code.value());
        }
    }

    /**
     * Convenience constructor for a term with no {@code skos:related} relation - equivalent to
     * passing an empty list for {@link #related} explicitly. Kept so the call sites that predate
     * {@code related} (kogn-io/arknet#420) do not all have to restate a trailing empty list.
     */
    public Term(TermId id, TermCode code, String prefLabel, String definition, TermCode broader) {
        this(id, code, prefLabel, definition, broader, List.of());
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
