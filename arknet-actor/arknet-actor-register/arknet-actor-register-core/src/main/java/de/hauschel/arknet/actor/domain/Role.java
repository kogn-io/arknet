// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Comparator;
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
 * <p><strong>Multilingual.</strong> {@link #name()}/{@link #description()} carry language-tagged
 * literals (SHACL {@code sh:uniqueLang}) - the same mechanism {@code Constraint}'s
 * {@code title}/{@code statement} use, and since kogn-io/arknet#520 {@link Actor}'s own
 * {@code name}/{@code description} as well. This record itself stays a plain, already-selected
 * projection (the value one {@link de.hauschel.arknet.kernel.DisplayLocale} resolved a candidate
 * set down to), exactly like {@code Constraint}'s own {@code title}/{@code statement} fields - the
 * multilingual storage and selection live in the out-adapter, not here. A role's name is a
 * function description that translates, which is why it was language-tagged from the start
 * (ADR-37 Part B): the roles due to migrate in from the glossary (today {@code TERM-6}..
 * {@code TERM-9}) are already maintained bilingually, and an untagged literal would destroy one
 * language on that migration. Where the two resource types of this hexagon still differ is the
 * FR-10 label-equality rule, not the tagging - see {@link Actor}'s own javadoc.</p>
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
 * @param filledBy    the actors currently occupying this role, deduplicated and canonically
 *                    ordered by identity (the edge is an RDF set, so the caller's order carries no
 *                    meaning and must not reach equality); maps to {@code arkproc:filledBy}. Never
 *                    {@code null}, may be empty - an unfilled role
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
        // Deduplicated and canonically ordered (not merely defensively copied): arkproc:filledBy
        // is an RDF edge, i.e. an unordered set, so neither a repeated mention nor the order a
        // caller happened to name the occupants in may reach this record's equality - otherwise
        // re-stating the same occupancy in a different order would read as a change and cost a
        // PROV revision behind which nothing changed.
        filledBy = filledBy.stream()
                .distinct()
                .sorted(Comparator.comparing(occupant -> occupant.value().value()))
                .toList();
    }

    /**
     * Returns a new role with {@code name}/{@code description}/{@code filledBy} replaced where a
     * non-{@code null} argument is given, leaving {@link #id()}/{@link #code()} untouched -
     * mirrors {@link Actor#withUpdates(String, String)}, extended with the occupancy field this
     * aggregate carries and {@link Actor} does not. The compact constructor still canonicalises
     * {@code filledBy} (dedup + order), so callers never need to.
     *
     * @param name        the new name, or {@code null} to keep {@link #name()} unchanged
     * @param description the new description, or {@code null} to keep {@link #description()}
     *                    unchanged
     * @param filledBy    the new occupancy, or {@code null} to keep {@link #filledBy()} unchanged
     * @return a new role reflecting the requested correction
     */
    public Role withUpdates(String name, String description, List<ActorId> filledBy) {
        return new Role(id, code,
                name != null ? name : this.name,
                description != null ? description : this.description,
                filledBy != null ? filledBy : this.filledBy);
    }
}
