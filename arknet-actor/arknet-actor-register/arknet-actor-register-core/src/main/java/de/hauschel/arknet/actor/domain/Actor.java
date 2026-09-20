// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

/**
 * Someone or something that can act on the system under description, hold an interest in it, or
 * both ({@code arkproc:Actor} and its four concrete subclasses).
 *
 * <p>Value object of the actor component. All invariants are enforced in the compact constructor;
 * instances are immutable.</p>
 *
 * <p><strong>An actor is a resource, not a facet.</strong> Being an actor used to be expressible
 * only by setting a second {@code rdf:type} on an existing glossary term, which made every actor a
 * glossary entry with a mandatory definition and a {@code TERM-N} code, and made "the term Kunde"
 * and "the actor Kunde" collide on one {@code skos:prefLabel}. This aggregate carries its own
 * opaque identity and its own {@code ACTOR-N} code instead, so an actor exists on its own terms. A
 * resource may still be both an actor and a glossary term - multi-typing stays legal, it is merely
 * no longer required.</p>
 *
 * <p><strong>Multilingual, mirroring {@link Role}'s policy (kogn-io/arknet#520).</strong>
 * {@link #name()}/{@link #description()} carry language-tagged literals (SHACL
 * {@code sh:uniqueLang}), the same mechanism {@link Role}'s own {@code name}/{@code description}
 * use. This record itself stays a plain, already-selected projection (the value one
 * {@link de.hauschel.arknet.kernel.DisplayLocale} resolved a candidate set down to) - the
 * multilingual storage and selection live in the out-adapter, not here. Unlike {@link Role}'s
 * name, an actor's name is a proper noun (an actor is a resource with a name, not a function
 * description) - {@code term_update}'s FR-10 label-equality guard therefore does not apply here
 * either: an actor's name is free to differ per language, the same choice kogn-io/arknet#520 made
 * for a bounded context's own name. Before kogn-io/arknet#520, both fields were untagged literals,
 * the same choice {@code BoundedContext} used to make for its own {@code arknet:name}; both
 * resource types carry the same reasoning now.</p>
 *
 * @param id          opaque, unchanging identity of this actor (never a business label); minted
 *                    once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory} and stable
 *                    across relabelling
 * @param code        human-readable business label (e.g. {@code ACTOR-1}); maps to
 *                    {@code dcterms:identifier}. Fixed at creation
 * @param type        which of the four kinds this actor is; decides the concrete
 *                    {@code arkproc:*Actor} type the out-adapter writes. Fixed at creation - see
 *                    {@link ActorType}
 * @param name        what this actor is called (e.g. {@code Sachbearbeiter}); maps to
 *                    {@code arknet:name} and is required by the actor SHACL shape
 * @param description free-text description of the actor; maps to {@code arknet:description}.
 *                    Optional (may be {@code null}), but never blank when present
 */
public record Actor(
        ActorId id,
        ActorCode code,
        ActorType type,
        String name,
        String description) {

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (description != null && description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank when present");
        }
    }

    /**
     * Returns a new actor with {@code name}/{@code description} replaced where a non-{@code null}
     * argument is given, leaving {@link #id()}/{@link #code()}/{@link #type()} untouched - the
     * derivation step behind {@code ActorService#updateWithOptimisticRetry}, isolated here so that
     * retry loop reads only "read, derive, compare, write" instead of also inlining the field-level
     * null handling.
     *
     * @param name        the new name, or {@code null} to keep {@link #name()} unchanged
     * @param description the new description, or {@code null} to keep {@link #description()}
     *                    unchanged
     * @return a new actor reflecting the requested correction
     */
    public Actor withUpdates(String name, String description) {
        return new Actor(id, code, type,
                name != null ? name : this.name,
                description != null ? description : this.description);
    }
}
