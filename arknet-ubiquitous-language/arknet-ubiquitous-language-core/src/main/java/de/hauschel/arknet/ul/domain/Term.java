// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

/**
 * A single term of the ubiquitous language under management - one entry of a
 * glossary.
 *
 * <p>Entity of the ubiquitous-language component. Maps onto a W3C SKOS concept: the
 * {@link #id} carries the stable, opaque identity (the concept IRI it wraps, minted once and
 * never derived from a business string), {@link #code} carries the human-readable running label
 * ({@code TERM-1}, stored as {@code dcterms:identifier}), {@link #prefLabel()} maps to
 * {@code skos:prefLabel} (the term itself) and {@link #definition()} to
 * {@code skos:definition}.</p>
 *
 * <h2>Why an interface and not a record (spike, issue #168)</h2>
 *
 * <p>This type used to be a {@code record} whose compact constructor enforced every invariant
 * and whose instances were immutable. It is an interface now so that an out-adapter can supply
 * an implementation that holds no fields at all but an RDF graph, reading and writing triples on
 * every accessor - "the object <em>is</em> the graph". That removes the double translation
 * (object to candidate graph on write, result rows to object on read) the out-adapter otherwise
 * carries twice over.</p>
 *
 * <p><strong>The hexagonal boundary is unchanged.</strong> Every member below is a plain Java
 * term - no RDF type appears here, and there is deliberately no {@code asGraph()}. A core that
 * could hand out a graph would have left the domain. The graph-backed implementation and its
 * factory live in the out-adapter; the adapter reaches its own graph by casting to its own
 * implementation type, not through this interface.</p>
 *
 * <p><strong>What the interface costs.</strong> A record's compact constructor cannot be
 * bypassed; a static checker can. {@link #requireLabel}/{@link #requireDefinition} keep the
 * invariants' <em>text</em> in the core, but their <em>enforcement</em> now depends on every
 * implementation calling them - which is a downgrade, and one this spike records rather than
 * hides. The second cost is mutability: the setters exist because they are what makes a
 * graph-backed update collapse into "read, mutate, replace", but they also mean a caller can
 * mutate a term returned from a read path and see nothing happen in the store.</p>
 */
public interface Term {

    /** The stable, opaque identity (the concept IRI), independent of both code and label. */
    TermId id();

    /** The human-readable business label (e.g. {@code TERM-1}); maps to {@code dcterms:identifier}. */
    TermCode code();

    /** The preferred label, i.e. the term itself; maps to {@code skos:prefLabel}. */
    String prefLabel();

    /**
     * Replaces the preferred label.
     *
     * @param prefLabel the new label; must not be {@code null} or blank
     */
    void prefLabel(String prefLabel);

    /** The meaning of the term; maps to {@code skos:definition}. */
    String definition();

    /**
     * Replaces the definition.
     *
     * @param definition the new definition; must not be {@code null} or blank
     */
    void definition(String definition);

    /**
     * The optional Actor facette (Weg A aus #45): if set, the same {@code skos:Concept} is
     * additionally an {@code arkproc:Actor}. May be {@code null}.
     */
    ActorFacet actorFacet();

    /**
     * Replaces the Actor facette wholesale.
     *
     * @param actorFacet the new facette, or {@code null} to remove it entirely
     */
    void actorFacet(ActorFacet actorFacet);

    /**
     * Creates the core's own plain, field-holding term - the implementation every caller gets
     * that has no reason to care about persistence (tests, in-memory fakes, the composition
     * root's default).
     *
     * @param id         the opaque identity, must not be {@code null}
     * @param code       the business code, must not be {@code null}
     * @param prefLabel  the preferred label, must not be {@code null} or blank
     * @param definition the definition, must not be {@code null} or blank
     * @param actorFacet the optional Actor facette, may be {@code null}
     * @return a plain term carrying exactly these values
     */
    static Term of(final TermId id, final TermCode code, final String prefLabel, final String definition,
            final ActorFacet actorFacet) {
        return new PlainTerm(id, code, prefLabel, definition, actorFacet);
    }

    /**
     * The {@code prefLabel} invariant, callable from any implementation - including one living
     * in an out-adapter, which is why this is part of the core's published surface rather than
     * hidden in a package-private helper.
     *
     * @param prefLabel the candidate label
     * @return {@code prefLabel} unchanged, for use in an assignment
     * @throws NullPointerException     if {@code prefLabel} is {@code null}
     * @throws IllegalArgumentException if {@code prefLabel} is blank
     */
    static String requireLabel(final String prefLabel) {
        if (prefLabel == null) {
            throw new NullPointerException("prefLabel");
        }
        if (prefLabel.isBlank()) {
            throw new IllegalArgumentException("prefLabel must not be blank");
        }
        return prefLabel;
    }

    /**
     * The {@code definition} invariant; see {@link #requireLabel} for why it is public.
     *
     * @param definition the candidate definition
     * @return {@code definition} unchanged, for use in an assignment
     * @throws NullPointerException     if {@code definition} is {@code null}
     * @throws IllegalArgumentException if {@code definition} is blank
     */
    static String requireDefinition(final String definition) {
        if (definition == null) {
            throw new NullPointerException("definition");
        }
        if (definition.isBlank()) {
            throw new IllegalArgumentException("definition must not be blank");
        }
        return definition;
    }

    /**
     * The value equality the {@code record} used to generate: two terms are equal when identity,
     * code, label, definition and Actor facette match, regardless of which implementation holds
     * them.
     *
     * <p>Every implementation has to call this from its own {@code equals} - an interface cannot
     * force it, and an implementation that forgets is simply back to identity comparison without
     * anything turning red until a caller compares two terms. That is the shape of the loss: the
     * semantics can be restored, the guarantee cannot.</p>
     *
     * @param self  the term being compared, must not be {@code null}
     * @param other the object compared against, may be {@code null}
     * @return whether both are terms carrying the same values
     */
    static boolean equal(final Term self, final Object other) {
        if (self == other) {
            return true;
        }
        if (!(other instanceof Term that)) {
            return false;
        }
        return self.id().equals(that.id())
                && self.code().equals(that.code())
                && self.prefLabel().equals(that.prefLabel())
                && self.definition().equals(that.definition())
                && Objects.equals(self.actorFacet(), that.actorFacet());
    }

    /**
     * The hash code matching {@link #equal}; every implementation has to call it from its own
     * {@code hashCode}.
     *
     * @param self the term to hash, must not be {@code null}
     * @return a hash consistent with {@link #equal} across implementations
     */
    static int hash(final Term self) {
        return Objects.hash(self.id(), self.code(), self.prefLabel(), self.definition(),
                self.actorFacet());
    }
}
