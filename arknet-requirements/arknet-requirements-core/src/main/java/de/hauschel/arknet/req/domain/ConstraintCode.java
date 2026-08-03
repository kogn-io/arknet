// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * Human-readable business label of a {@link Constraint}, e.g. {@code TCON-1}, {@code BCON-3} or
 * {@code RCON-1}.
 *
 * <p>Value object wrapping the business identifier, mirroring {@link RequirementCode} exactly.
 * Generation of the running number is a policy concern of the application layer, not of this
 * type. Deliberately separate from {@link ConstraintId}: the code is what a human types (into
 * {@code constraint_get}, {@code req_link_constraint}, ...) and what {@code dcterms:identifier}
 * carries in the store; it may in principle be relabelled without touching the constraint's
 * underlying identity.</p>
 *
 * @param value the non-blank code string
 */
public record ConstraintCode(String value) {

    public ConstraintCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ConstraintCode must not be blank");
        }
    }
}
