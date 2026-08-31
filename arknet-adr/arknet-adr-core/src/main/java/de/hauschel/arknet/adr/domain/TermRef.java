// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from an {@link Adr} to a glossary term it uses, carried as the term's opaque subject
 * identity - not as a business label and not as a value derived from any other predicate on the
 * term.
 *
 * <p>Structurally identical to {@link RequirementRef}/{@link BoundedContextRef}, for the same
 * reasons: the ADR component must not depend on {@code arknet-ubiquitous-language-core}, so this
 * value object holds only the shared-kernel {@link ResourceId}; resolving a human-typed code
 * (e.g. {@code TERM-1}) to it - and rejecting an unknown or ambiguous one - happens behind a
 * driven lookup port.</p>
 *
 * <p><strong>Own property, not the shared {@code arkreq:usesTerm} domain (kogn-io/arknet#393).</strong>
 * {@code arknet-requirements.ttl} widens {@code arkreq:usesTerm}'s domain to a union of
 * {@code arkreq:Requirement} and {@code arkreq:UseCase} because both live in the same
 * {@code arkreq} namespace/bounded context; an ADR lives in its own namespace/bounded context
 * ({@code arkarch}/{@code arknet-adr}), so that justification does not carry over. This edge is
 * therefore {@code arkarch:usesTerm}, a property of its own - the same choice
 * {@code arkddd:ubiquitousLanguageTerm} already made for the bounded-context component. The edge
 * lives <em>inside</em> the {@link Adr} aggregate rather than beside it, exactly like
 * {@link RequirementRef}/{@link BoundedContextRef}: the out-adapter persists a decision by
 * replacing it wholesale, so a link kept outside this record would be silently dropped by the
 * next write.</p>
 *
 * @param value the term's opaque subject identity, never {@code null}
 */
public record TermRef(ResourceId value) {

    public TermRef {
        Objects.requireNonNull(value, "value");
    }
}
