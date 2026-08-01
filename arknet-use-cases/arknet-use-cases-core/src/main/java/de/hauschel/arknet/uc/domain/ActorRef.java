// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Reference from a {@link UseCase} to an actor participating in it, carried as the actor
 * term's opaque subject identity - not as a business label and not as a value derived from
 * any other predicate on the term.
 *
 * <p><strong>Deliberately not a link to the ubiquitous-language bounded context.</strong> Actors
 * are modelled there (as a facet on glossary terms); the use-cases component must not depend on
 * {@code arknet-ubiquitous-language-core}. This value object therefore holds only the
 * shared-kernel {@link ResourceId}, never a {@code ul}-specific {@code TermId} or
 * {@code TermCode}. Resolving a human-typed actor name (e.g. {@code Customer}) to this identity -
 * and rejecting an unknown or ambiguous name - is the job of a driven lookup port against the
 * shared store, not of this pure domain type.</p>
 *
 * <p><strong>Identity, not a re-derived value, the use-cases analogue of
 * requirements' own equivalent.</strong> The reference used to carry the actor's {@code skos:prefLabel} as
 * a bare string, resolved to the actor's IRI on write and re-derived from the IRI on read via an
 * inner join back into the terms graph - a join that silently dropped the whole use case whenever
 * its primary actor's label was missing or renamed. Carrying the subject identity itself instead
 * means the edge <em>is</em> the store's own edge: no join is needed to read it back, and it
 * survives relabelling - or even removing the {@code skos:prefLabel} of - the actor it points
 * at.</p>
 *
 * @param value the actor term's opaque subject identity, never {@code null}
 */
public record ActorRef(ResourceId value) {

    public ActorRef {
        Objects.requireNonNull(value, "value");
    }
}
