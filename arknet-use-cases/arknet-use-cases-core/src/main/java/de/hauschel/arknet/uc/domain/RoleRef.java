// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from a {@link UseCase} to a role participating in it, carried as the role
 * resource's opaque subject identity - not as a business label and not as a value derived from
 * any other predicate on the role.
 *
 * <p><strong>Deliberately not a link to the actor bounded context.</strong> Roles are modelled
 * there, as their own resource type in {@code arknet-actor}'s register (ADR-37); the use-cases
 * component must not depend on {@code arknet-actor-core}. This value object therefore holds only
 * the shared-kernel {@link ResourceId}, never an {@code actor}-specific {@code RoleId} or
 * {@code RoleCode}. Resolving a human-typed role code (e.g. {@code ROLE-4}) to this identity - and
 * rejecting an unknown or ambiguous code - is the job of a driven lookup port against the shared
 * store, not of this pure domain type.</p>
 *
 * <p><strong>Identity, not a re-derived value, the use-cases analogue of
 * requirements' own equivalent.</strong> Before ADR-37/kogn-io/arknet#405 Part C, this reference
 * targeted an {@code arkproc:Actor} instead, resolved by the actor's human-typed, untagged name -
 * itself a replacement (issue #336) for an even older design where the reference carried the
 * actor's {@code skos:prefLabel} as a bare string, re-derived from the IRI on read via an inner
 * join back into the terms graph that silently dropped the whole use case whenever the label was
 * missing or renamed. Carrying the subject identity itself instead means the edge <em>is</em> the
 * store's own edge: no join is needed to read it back, and it survives relabelling - or even
 * removing the display name of - the role it points at.</p>
 *
 * @param value the role's opaque subject identity, never {@code null}
 */
public record RoleRef(ResourceId value) {

    public RoleRef {
        Objects.requireNonNull(value, "value");
    }
}
