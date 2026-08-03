// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Opaque, unchanging identity of a {@link Constraint}.
 *
 * <p>Thin newtype over the shared-kernel {@link ResourceId} for type safety within the
 * requirements bounded context - mirrors {@link RequirementId} exactly. Identity is deliberately
 * independent of the human-readable {@link ConstraintCode} ({@code TCON-1}): the code may be
 * relabelled, this identity never changes.</p>
 *
 * @param value the wrapped resource identity, never {@code null}
 */
public record ConstraintId(ResourceId value) {

    public ConstraintId {
        Objects.requireNonNull(value, "value");
    }
}
