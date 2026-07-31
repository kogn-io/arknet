// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from an {@link Adr} to the requirement the decision addresses, carried as the
 * requirement's opaque subject identity - not as a business label and not as a value derived from
 * any other predicate on the requirement.
 *
 * <p><strong>Deliberately not a link to the requirements bounded context.</strong> The ADR
 * component must not depend on {@code arknet-requirements-core}; the two BCs stay decoupled. This
 * value object therefore holds only the shared-kernel {@link ResourceId} - the same opaque-identity
 * newtype {@link AdrId} already wraps - never a {@code req}-specific {@code RequirementId} or
 * {@code RequirementCode}. Resolving a human-typed code (e.g. {@code FR-1}) to this identity - and
 * rejecting an unknown or ambiguous one - is the job of a driven lookup port against the shared
 * store, not of this pure domain type.</p>
 *
 * <p><strong>Direction is deliberate.</strong> The edge ({@code arkarch:addressesRequirement}) is
 * owned by the deciding side, so the dependency points adr -&gt; requirements, never the other way
 * round - structurally the same choice requirements made for {@code arkreq:usesTerm} and
 * bounded-context for {@code arkddd:ubiquitousLanguageTerm}. It also lives <em>inside</em> the
 * {@link Adr} aggregate rather than beside it: the out-adapter persists a decision by replacing it
 * wholesale, so a link kept outside this record would be silently dropped by the next write.</p>
 *
 * @param value the requirement's opaque subject identity, never {@code null}
 */
public record RequirementRef(ResourceId value) {

    public RequirementRef {
        Objects.requireNonNull(value, "value");
    }
}
