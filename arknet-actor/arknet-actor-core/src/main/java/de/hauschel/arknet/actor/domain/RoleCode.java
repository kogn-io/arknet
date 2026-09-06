// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

/**
 * Human-readable business label of a {@link Role}, e.g. {@code ROLE-1}.
 *
 * <p>Value object wrapping the business identifier - mirrors {@link ActorCode} exactly. Generation
 * of the running number is a policy concern of the application layer, not of this type. Deliberately
 * separate from {@link RoleId}: the code is what a human types (into {@code role_get},
 * {@code role_update}, ...) and what {@code dcterms:identifier} carries in the store; it may in
 * principle be relabelled without touching the role's underlying identity.</p>
 *
 * <p>Its own counter, independent of {@link ActorCode}'s {@code ACTOR-N}: a role and an actor are
 * two distinct resource types in this same hexagon (ADR-37), and {@code ROLE-1} naming an
 * unrelated thing from {@code ACTOR-1} is exactly the point - conflating the two counters would
 * make "the first thing added" ambiguous between them.</p>
 *
 * @param value the non-blank code string
 */
public record RoleCode(String value) {

    public RoleCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RoleCode must not be blank");
        }
    }
}
