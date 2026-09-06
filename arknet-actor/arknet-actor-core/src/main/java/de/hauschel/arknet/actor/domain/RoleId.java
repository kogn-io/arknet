// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Opaque, unchanging identity of a {@link Role}.
 *
 * <p>Thin newtype over the shared-kernel {@link ResourceId} for type safety within the actor
 * bounded context - mirrors {@link ActorId} exactly. Identity is deliberately independent of the
 * human-readable {@link RoleCode} ({@code ROLE-1}): the code may be relabelled, this identity
 * never changes.</p>
 *
 * @param value the wrapped resource identity, never {@code null}
 */
public record RoleId(ResourceId value) {

    public RoleId {
        Objects.requireNonNull(value, "value");
    }
}
