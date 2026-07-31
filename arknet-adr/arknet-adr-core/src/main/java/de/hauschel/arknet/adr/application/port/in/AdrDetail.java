// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;

/**
 * What every driving port of this hexagon hands back: the decision itself plus both directions of
 * the self-referential {@code arkarch:supersedes} relation, already rendered as business codes.
 *
 * <p><strong>Why the codes are resolved here and not by the caller.</strong> {@link Adr#supersedes}
 * carries opaque {@link de.hauschel.arknet.adr.domain.AdrId}s, and a human who typed
 * {@code ADR-1} expects to see {@code ADR-1} again rather than an IRI they cannot re-type. Unlike
 * the {@code addressesRequirement}/{@code affectsContext} references - whose targets live in
 * <em>neighbour</em> hexagons and are therefore resolved by the in-adapter through those hexagons'
 * own driving ports (ADR-008) - a superseded decision is this hexagon's own resource. Answering
 * "which code names this identity" for it is this hexagon's own job, so the application service
 * does it and no caller needs a second mechanism.</p>
 *
 * <p><strong>Why the backward direction is derived rather than stored.</strong> Nothing in this
 * codebase materialises an {@code owl:inverseOf} pair as two physical triples - the write gate does
 * no reasoning, and two hand-maintained triples are exactly the drift risk the project avoids
 * elsewhere. {@link #supersededBy} is therefore the result of a reverse read ("which decisions point
 * their {@code supersedes} at this one"), structurally the same backward traversal
 * {@code TraceabilityGraph#dependents} performs, never a second asserted edge.</p>
 *
 * @param adr          the decision itself, with its opaque references intact
 * @param supersedes   the business codes of the decisions {@code adr} replaces, in the order
 *                     {@link Adr#supersedes()} holds them; an identity that no longer resolves
 *                     (deleted store-first, ADR-005) is simply absent rather than an error
 * @param supersededBy the business codes of the decisions that replace {@code adr}, sorted; empty
 *                     if none do
 */
public record AdrDetail(Adr adr, List<AdrCode> supersedes, List<AdrCode> supersededBy) {

    public AdrDetail {
        Objects.requireNonNull(adr, "adr");
        supersedes = supersedes == null ? List.of() : List.copyOf(supersedes);
        supersededBy = supersededBy == null ? List.of() : List.copyOf(supersededBy);
    }
}
