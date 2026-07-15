package de.hauschel.arknet.ul.domain;

import java.util.Objects;

/**
 * A single term of the ubiquitous language under management - one entry of a
 * glossary.
 *
 * <p>Value object of the ubiquitous-language component. Maps onto a W3C SKOS
 * concept: the {@link #id} carries the stable identity (the concept IRI is derived
 * from it), {@link #prefLabel} maps to {@code skos:prefLabel} (the term itself) and
 * {@link #definition} to {@code skos:definition}. All invariants are enforced in the
 * compact constructor; instances are immutable.</p>
 *
 * @param id         stable business identity (e.g. {@code TERM-1}), independent of
 *                   the label
 * @param prefLabel  the preferred label, i.e. the term itself; maps to
 *                   {@code skos:prefLabel}
 * @param definition the meaning of the term; maps to {@code skos:definition}
 * @param actorFacet optional Actor facette (Weg A aus #45): if set, the same
 *                   skos:Concept is additionally an {@code arkproc:Actor}.
 *                   Optional (may be {@code null})
 */
public record Term(TermId id, String prefLabel, String definition, ActorFacet actorFacet) {

    public Term {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(prefLabel, "prefLabel");
        Objects.requireNonNull(definition, "definition");
        if (prefLabel.isBlank()) {
            throw new IllegalArgumentException("prefLabel must not be blank");
        }
        if (definition.isBlank()) {
            throw new IllegalArgumentException("definition must not be blank");
        }
    }
}
