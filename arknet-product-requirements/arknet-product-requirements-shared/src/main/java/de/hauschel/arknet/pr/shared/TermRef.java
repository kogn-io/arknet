// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.pr.shared;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from a resource of the Product &amp; Requirements bounded context - a requirement or a
 * use case - to a glossary term of the ubiquitous language it uses, carried as the term's opaque
 * subject identity, not as a business label and not as a value derived from any other predicate on
 * the term.
 *
 * <p><strong>Vocabulary of this bounded context, not of one component.</strong> Both components of
 * the context reference a glossary term, and they mean the same thing by it, so the type lives in
 * the context's vocabulary module rather than once per component (ADR-57). It deliberately does
 * <em>not</em> live in the shared kernel of all contexts: the reference to a glossary term is the
 * language of the referencing context, so every context that points at the glossary holds its own
 * reference type.</p>
 *
 * <p><strong>Deliberately not a link to the domain-modelling bounded context.</strong> No module of
 * this context may depend on {@code arknet-ubiquitous-language-core}. This value object therefore
 * holds only the shared-kernel {@link ResourceId} - the same opaque-identity newtype the context's
 * own identities wrap - never a {@code ul}-specific {@code TermId} or {@code TermCode}. Resolving a
 * human-typed term code (e.g. {@code TERM-1}) to this identity - and rejecting an unknown or
 * ambiguous code - is the job of a driven lookup port against the shared store, not of this pure
 * domain type.</p>
 *
 * <p><strong>Identity, not a re-derived value.</strong> The reference used to carry the term's
 * {@code dcterms:identifier} as a bare string, resolved to the term's IRI on write and re-derived
 * from the IRI on read via an inner join back into the terms graph - a join that silently dropped
 * the edge whenever its target carried no identifier. Carrying the subject identity itself instead
 * means the edge <em>is</em> the store's own edge: no join is needed to read it back, and it
 * survives relabelling - or even removing the {@code dcterms:identifier} of - the term it points
 * at.</p>
 *
 * @param value the term's opaque subject identity, never {@code null}
 */
public record TermRef(ResourceId value) {

    public TermRef {
        Objects.requireNonNull(value, "value");
    }
}
