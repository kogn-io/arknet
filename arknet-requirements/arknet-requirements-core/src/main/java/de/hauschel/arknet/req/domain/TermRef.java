// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from a {@link Requirement} to a glossary term of the ubiquitous language it uses,
 * carried as the term's opaque subject identity - not as a business label and not as a value
 * derived from any other predicate on the term.
 *
 * <p><strong>Deliberately not a link to the ubiquitous-language bounded context.</strong> The
 * requirements component must not depend on {@code arknet-ubiquitous-language-core}; the two
 * BCs stay decoupled. This value object therefore holds only the shared-kernel
 * {@link ResourceId} - the same opaque-identity newtype {@link RequirementId} already wraps -
 * never a {@code ul}-specific {@code TermId} or {@code TermCode}. Resolving a human-typed term
 * code (e.g. {@code TERM-1}) to this identity - and rejecting an unknown or ambiguous code - is
 * the job of a driven lookup port against the shared store, not of this pure domain type.</p>
 *
 * <p><strong>Identity, not a re-derived value.</strong> The reference used to carry
 * the term's {@code dcterms:identifier} as a bare string, resolved to the term's IRI on write
 * and re-derived from the IRI on read via an inner join back into the terms graph - a join that
 * silently dropped the edge whenever its target carried no identifier. Carrying the subject
 * identity itself instead means the edge <em>is</em> the store's own edge: no join is needed to
 * read it back, and it survives relabelling - or even removing the {@code dcterms:identifier}
 * of - the term it points at.</p>
 *
 * @param value the term's opaque subject identity, never {@code null}
 */
public record TermRef(ResourceId value) {

    public TermRef {
        Objects.requireNonNull(value, "value");
    }
}
