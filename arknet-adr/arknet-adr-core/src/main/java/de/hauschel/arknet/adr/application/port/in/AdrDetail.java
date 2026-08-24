// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;

/**
 * What every driving port of this hexagon hands back: the decision itself plus both directions of
 * the self-referential {@code arkarch:supersedes} relation and the merged view of the equally
 * self-referential {@code arkarch:relatedTo} relation, already rendered as business codes.
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
 * <p><strong>Why {@code relatedTo} is one merged list and not two directions.</strong>
 * {@code arkarch:relatedTo} is written forward-only for the very same reason
 * {@code supersededBy} is never materialised, so its backward direction needs the very same reverse
 * read. What it must <em>not</em> get is {@code supersedes}' two-field treatment: the property is
 * an {@code owl:SymmetricProperty}, so "relates to" and "is related from" name one and the same
 * relation, and splitting it would present the direction the edge happened to be typed in as if it
 * carried meaning. {@link #relatedTo} is therefore the union of the decision's own forward edges
 * and the edges pointing at it, deduplicated and ordered by running number - which also makes a
 * cycle ({@code A relatedTo B}, {@code B relatedTo A}) render once per peer rather than twice.
 * Merging is a single reverse read, not a traversal: nothing here follows a peer's own
 * {@code relatedTo} edges onwards, which is what keeps a legitimate cycle harmless (and why
 * {@code TraceabilityGraph} leaves the predicate out of {@code impact_analysis} entirely).</p>
 *
 * @param adr          the decision itself, with its opaque references intact
 * @param supersedes   the business codes of the decisions {@code adr} replaces, in the order
 *                     {@link Adr#supersedes()} holds them; an identity that no longer resolves
 *                     (deleted store-first, ADR-005) is simply absent rather than an error
 * @param supersededBy the business codes of the decisions that replace {@code adr}, sorted; empty
 *                     if none do
 * @param relatedTo    the business codes of every decision {@code adr} is cross-referenced with -
 *                     the union of its own forward edges and those pointing at it, deduplicated and
 *                     sorted by running number; empty if it has none
 */
public record AdrDetail(Adr adr, List<AdrCode> supersedes, List<AdrCode> supersededBy,
        List<AdrCode> relatedTo) {

    public AdrDetail {
        Objects.requireNonNull(adr, "adr");
        supersedes = supersedes == null ? List.of() : List.copyOf(supersedes);
        supersededBy = supersededBy == null ? List.of() : List.copyOf(supersededBy);
        relatedTo = relatedTo == null ? List.of() : List.copyOf(relatedTo);
    }
}
