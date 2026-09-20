// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

/**
 * Human-readable business label of an {@link Actor}, e.g. {@code ACTOR-1}.
 *
 * <p>Value object wrapping the business identifier. Generation of the running number is a policy
 * concern of the application layer, not of this type. This is deliberately separate from
 * {@link ActorId}: the code is what a human types (into {@code actor_get}, {@code actor_update},
 * ...) and what {@code dcterms:identifier} carries in the store; it may in principle be relabelled
 * without touching the actor's underlying identity.</p>
 *
 * <p>One prefix for all four {@link ActorType}s, not one per type - see {@link ActorType} for why
 * this differs from {@code ConstraintCode}'s {@code TCON-}/{@code BCON-}/{@code RCON-}.</p>
 *
 * @param value the non-blank code string
 */
public record ActorCode(String value) {

    public ActorCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ActorCode must not be blank");
        }
    }
}
