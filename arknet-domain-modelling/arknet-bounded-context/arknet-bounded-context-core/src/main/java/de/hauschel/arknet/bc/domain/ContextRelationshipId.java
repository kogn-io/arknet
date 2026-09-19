// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Opaque, unchanging identity of a {@link ContextRelationship}.
 *
 * <p>Thin newtype over the shared-kernel {@link ResourceId} for type safety within the
 * bounded-context bounded context - the same wrapping pattern {@link BoundedContextId} already
 * uses. A {@link ContextRelationship} carries no separate human-readable business code (unlike
 * {@link BoundedContext}'s {@link BoundedContextCode}): it is pure CRUD between two already-coded
 * bounded contexts, so this opaque identity is its only identity.</p>
 *
 * @param value the wrapped resource identity, never {@code null}
 */
public record ContextRelationshipId(ResourceId value) {

    public ContextRelationshipId {
        Objects.requireNonNull(value, "value");
    }
}
