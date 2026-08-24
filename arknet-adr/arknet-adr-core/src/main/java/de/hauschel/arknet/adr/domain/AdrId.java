// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Opaque, unchanging identity of an {@link Adr}.
 *
 * <p>Thin newtype over the shared-kernel {@link ResourceId} for type safety within the ADR bounded
 * context. Identity is deliberately independent of the human-readable {@link AdrCode}
 * ({@code ADR-1}): the code may be relabelled, this identity never changes.</p>
 *
 * <p>Doubles as the reference type of the self-referential {@code arkarch:supersededBy} edge (see
 * {@link Adr#supersededBy()}). Unlike {@link RequirementRef}/{@link BoundedContextRef}, which wrap a
 * bare {@link ResourceId} precisely because their target lives in a <em>neighbour</em> hexagon this
 * one must not depend on, the superseding ADR is this hexagon's own resource - so its own identity
 * type is the honest one to hold, and no separate {@code AdrRef} newtype earns its place.</p>
 *
 * @param value the wrapped resource identity, never {@code null}
 */
public record AdrId(ResourceId value) {

    public AdrId {
        Objects.requireNonNull(value, "value");
    }
}
