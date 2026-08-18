// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from a {@link UseCase} to a constraint it is bound by, carried as the constraint's
 * opaque subject identity - not as a business label and not as a value derived from any other
 * predicate on the constraint.
 *
 * <p><strong>A cross-BC reference, unlike the sibling requirements bounded context's own {@code
 * ConstraintRef}.</strong> There, {@code Constraint} lives inside the very same module, so
 * resolving a constraint code is a direct, same-module lookup. Here, {@code Constraint} lives in
 * the neighbouring requirements bounded context - the use-cases component must not depend on
 * {@code arknet-requirements-core}, so it cannot look a constraint up as a domain object. This
 * value object therefore holds only the shared-kernel {@link ResourceId}, mirroring
 * {@link TermRef}/{@link RequirementRef} exactly: resolving a human-typed constraint code (e.g.
 * {@code TCON-1}) to this identity - and rejecting an unknown or ambiguous code - is the job of a
 * driven lookup port against the shared store, not of this pure domain type.</p>
 *
 * <p><strong>Part of the use case's own state, not a side edge.</strong> Same reasoning as
 * {@link TermRef}: the out-adapter persists a use case by replacing it wholesale, so a link kept
 * outside this record would be silently dropped by the next {@code uc_update}.</p>
 *
 * @param value the constraint's opaque subject identity, never {@code null}
 */
public record ConstraintRef(ResourceId value) {

    public ConstraintRef {
        Objects.requireNonNull(value, "value");
    }
}
