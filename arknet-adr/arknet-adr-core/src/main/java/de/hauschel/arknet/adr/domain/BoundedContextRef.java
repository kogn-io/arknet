// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from an {@link Adr} to the bounded context the decision affects, carried as that
 * context's opaque subject identity - not as a business label and not as a value derived from any
 * other predicate on it.
 *
 * <p>Structurally identical to {@link RequirementRef}, for the same reasons: the ADR component must
 * not depend on {@code arknet-bounded-context-core}, so this value object holds only the
 * shared-kernel {@link ResourceId}; resolving a human-typed code (e.g. {@code BC-1}) to it - and
 * rejecting an unknown or ambiguous one - happens behind a driven lookup port. The edge
 * ({@code arkarch:affectsContext}) is owned by the deciding side and lives <em>inside</em> the
 * {@link Adr} aggregate, so a replace-by-identity write carries it along instead of dropping it.</p>
 *
 * @param value the bounded context's opaque subject identity, never {@code null}
 */
public record BoundedContextRef(ResourceId value) {

    public BoundedContextRef {
        Objects.requireNonNull(value, "value");
    }
}
