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
 * <p><strong>Plain literals, no language tags.</strong> {@link #name()} and {@link #description()}
 * are written as untagged literals, unlike a glossary term's {@code skos:prefLabel} or a
 * requirement's {@code dcterms:title}. An actor is a structural identity resource - the same choice
 * {@code BoundedContext} makes for its own {@code arknet:name} - not a carrier of prose whose
 * wording is itself the deliverable, and inventing a per-field language mechanism for a name nobody
 * translates would cost every read path a {@code DisplayLocale} hop for nothing.</p>
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
}
