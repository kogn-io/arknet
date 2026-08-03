// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from a {@link Requirement} to a {@link Constraint} it is bound by, carried as the
 * constraint's opaque subject identity - not as a business label and not as a value derived from
 * any other predicate on the constraint.
 *
 * <p>Mirrors {@link TermRef} exactly, for the same reason: identity, not a re-derived value, so
 * the edge survives relabelling the constraint it points at and needs no join to read back. Unlike
 * {@link TermRef} - a genuinely cross-bounded-context reference resolved through the driven
 * {@code TermLookup} port - {@link Constraint} lives inside this same bounded context
 * ({@code arknet-requirements-core}), so resolving a human-typed {@link ConstraintCode} to this
 * identity is a direct, same-module lookup against {@code ConstraintRepository}, not a cross-BC
 * lookup port.</p>
 *
 * <p><strong>Part of the requirement's own state, not a side edge.</strong> Same reasoning as
 * {@link Requirement#usesTerms()}: the out-adapter persists a requirement by replacing it
 * wholesale, so a link kept outside this record would be silently dropped by the next status
 * change.</p>
 *
 * @param value the constraint's opaque subject identity, never {@code null}
 */
public record ConstraintRef(ResourceId value) {

    public ConstraintRef {
        Objects.requireNonNull(value, "value");
    }
}
