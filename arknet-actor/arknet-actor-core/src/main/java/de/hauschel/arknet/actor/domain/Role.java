// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.List;
import java.util.Objects;

/**
 * A named function in which someone or something acts or holds an interest, named independently
 * of who fills it ({@code arkproc:Role}, ADR-37/kogn-io/arknet#405).
 *
 * <p>Value object of the actor component, mirroring {@link Actor}'s own shape: all invariants are
 * enforced in the compact constructor; instances are immutable. Unlike {@link Actor}, a role is
 * <strong>anti-rigid</strong>: an actor is a person or a system whether or not anyone is looking at
 * it, but a role (Requirements Engineer, Payment Processor) exists only as long as it is a role
 * someone could fill - and it is a different thing entirely from whoever currently fills it. That is
 * why {@link Role} does not subclass {@link Actor} in the ontology and does not reuse
 * {@link ActorType}: a role has no type of its own to fix at creation, only a name.</p>
 *
 * <p><strong>Multilingual, unlike {@link Actor}.</strong> {@link #name()}/{@link #description()}
 * carry language-tagged literals (SHACL {@code sh:uniqueLang}) - the same mechanism
 * {@code Constraint}'s {@code title}/{@code statement} use, not {@link Actor}'s untagged ones. This
 * record itself stays a plain, already-selected projection (the value one {@link
 * de.hauschel.arknet.kernel.DisplayLocale} resolved a candidate set down to), exactly like
 * {@code Constraint}'s own {@code title}/{@code statement} fields - the multilingual storage and
 * selection live in the out-adapter, not here. See {@code arknet-actor/CLAUDE.md} for why this
 * hexagon's two resource types deliberately disagree on this: an actor's name is a proper noun, a
 * role's name is a function description that translates, and the roles due to migrate in from the
 * glossary (today {@code TERM-6}..{@code TERM-9}) are already maintained bilingually - an untagged
 * literal would destroy one language on that migration.</p>
 *
 * <p><strong>{@link #filledBy()} carries opaque identity, not a business code.</strong> The
 * occupancy edge ({@code arkproc:filledBy}) survives an occupant being relabelled, and reading it
 * back needs no join against a business-label index. It reuses {@link ActorId} directly rather than
 * introducing a distinct {@code ActorRef} wrapper (the shape {@code ConstraintRef} adds over
 * {@code ConstraintId} in the requirements component): {@link Actor} is this hexagon's only other
 * aggregate, so a second type with the identical shape and no distinct behaviour would be pure
 * duplication, not a meaningful distinction. A role may be filled by zero, one or several actors,
 * and unfilled is a legitimate, common state (TERM-21/FR-7) - the specification itself never reads
 * this edge, only evaluating views display it.</p>
 *
 * @param id          opaque, unchanging identity of this role (never a business label); minted once
 *                    by a {@link de.hauschel.arknet.kernel.ResourceIdFactory} and stable across
 *                    relabelling
 * @param code        human-readable business label (e.g. {@code ROLE-1}); maps to
 *                    {@code dcterms:identifier}. Fixed at creation
 * @param name        what this role is called (e.g. {@code Requirements Engineer}); maps to
 *                    {@code arknet:name} and is required by the role SHACL shape
 * @param description free-text description of the role; maps to {@code arknet:description}.
 *                    Optional (may be {@code null}), but never blank when present
 * @param filledBy    the actors currently occupying this role, in no particular order; maps to
 *                    {@code arkproc:filledBy}. Never {@code null}, may be empty - an unfilled role
 */
public record Role(
        RoleId id,
        RoleCode code,
        String name,
        String description,
        List<ActorId> filledBy) {

    public Role {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (description != null && description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank when present");
        }
        Objects.requireNonNull(filledBy, "filledBy");
        if (filledBy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("filledBy must not contain a null actor identity");
        }
        // Deduplicated (not merely defensively copied): arkproc:filledBy is an RDF edge, so two
        // identical triples read back as one - the aggregate's own equality should not depend on
        // whether a caller happened to name the same actor twice.
        filledBy = filledBy.stream().distinct().toList();
    }
}
