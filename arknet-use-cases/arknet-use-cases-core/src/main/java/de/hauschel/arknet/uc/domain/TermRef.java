// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from a {@link UseCase} to a glossary term of the ubiquitous language it uses,
 * carried as the term's opaque subject identity - not as a business label and not as a value
 * derived from any other predicate on the term.
 *
 * <p><strong>Deliberately not a link to the ubiquitous-language bounded context.</strong> The
 * use-cases component must not depend on {@code arknet-ubiquitous-language-core}; the two BCs
 * stay decoupled. This value object therefore holds only the shared-kernel {@link ResourceId} -
 * the same opaque-identity newtype {@link UseCaseId} already wraps - never a {@code ul}-specific
 * {@code TermId} or {@code TermCode}, and never the sibling requirements bounded context's own
 * {@code TermRef} (that type must not leak across the module boundary either). Resolving a
 * human-typed term code (e.g. {@code TERM-1}) to this identity - and rejecting an unknown or
 * ambiguous code - is the job of a driven lookup port against the shared store, not of this pure
 * domain type.</p>
 *
 * <p><strong>Part of the use case's own state, not a side edge.</strong> Same reasoning as
 * {@link UseCase#primaryRole()}/{@link RequirementRef}: the out-adapter persists a use case by
 * replacing it wholesale, so a link kept outside this record would be silently dropped by the
 * next {@code uc_update}.</p>
 *
 * @param value the term's opaque subject identity, never {@code null}
 */
public record TermRef(ResourceId value) {

    public TermRef {
        Objects.requireNonNull(value, "value");
    }
}
