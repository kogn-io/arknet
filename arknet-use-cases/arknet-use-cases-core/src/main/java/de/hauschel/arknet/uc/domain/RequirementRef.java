// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from a {@link Step} to a functional requirement it realises, carried as the
 * requirement's opaque subject identity - not as a business label and not as a value derived
 * from any other predicate on the requirement.
 *
 * <p><strong>Deliberately not a link to the requirements bounded context.</strong> The use-cases
 * component must not depend on {@code arknet-requirements-core}; the two BCs stay decoupled.
 * This value object therefore holds only the shared-kernel {@link ResourceId} - the same
 * opaque-identity newtype the requirements BC's own {@code RequirementId} wraps - never a
 * {@code req}-specific {@code RequirementId} or {@code RequirementCode}. Resolving a human-typed
 * requirement code (e.g. {@code FR-5}) to this identity - and rejecting an unknown or ambiguous
 * code - is the job of a driven lookup port against the shared store, not of this pure domain
 * type.</p>
 *
 * <p><strong>Identity, not a re-derived value (issue #89, the use-cases analogue of
 * requirements' #77).</strong> The reference used to carry the requirement's
 * {@code dcterms:identifier} as a bare string, resolved to the requirement's IRI on write and
 * re-derived from the IRI on read via an inner join back into the requirements graph - a join
 * that silently dropped the edge whenever its target carried no identifier. Carrying the subject
 * identity itself instead means the edge <em>is</em> the store's own edge: no join is needed to
 * read it back, and it survives relabelling - or even removing the {@code dcterms:identifier}
 * of - the requirement it points at.</p>
 *
 * @param value the requirement's opaque subject identity, never {@code null}
 */
public record RequirementRef(ResourceId value) {

    public RequirementRef {
        Objects.requireNonNull(value, "value");
    }
}
