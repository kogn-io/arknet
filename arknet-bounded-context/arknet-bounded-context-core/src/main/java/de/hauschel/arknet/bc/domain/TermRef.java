// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from a {@link BoundedContext} to a glossary term of the ubiquitous language it
 * names, carried as the term's opaque subject identity - not as a business label and not as a
 * value derived from any other predicate on the term.
 *
 * <p><strong>Deliberately not a link to the ubiquitous-language bounded context.</strong> The
 * bounded-context component must not depend on {@code arknet-ubiquitous-language-core}; the two
 * BCs stay decoupled. This value object therefore holds only the shared-kernel
 * {@link ResourceId} - the same opaque-identity newtype {@link BoundedContextId} already wraps -
 * never a {@code ul}-specific {@code TermId} or {@code TermCode}. Resolving a human-typed term
 * code (e.g. {@code TERM-1}) to this identity - and rejecting an unknown or ambiguous code - is
 * the job of a driven lookup port against the shared store, not of this pure domain type.</p>
 *
 * <p><strong>Direction is deliberate.</strong> The edge
 * ({@code arkddd:ubiquitousLanguageTerm}) is owned by the naming bounded context, so the
 * dependency points bounded-context -&gt; ubiquitous-language, never the other way round -
 * structurally the same choice requirements made for {@code arkreq:usesTerm}. It also lives
 * <em>inside</em> the {@link BoundedContext} aggregate rather than beside it: the out-adapter
 * persists a bounded context by replacing it wholesale, so a link kept outside this record would
 * be silently dropped by the next write.</p>
 *
 * @param value the term's opaque subject identity, never {@code null}
 */
public record TermRef(ResourceId value) {

    public TermRef {
        Objects.requireNonNull(value, "value");
    }
}
