// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.bc.domain.BoundedContext;

/**
 * What {@link GetBoundedContext} and {@link ListBoundedContexts} hand back since issue #565: the
 * bounded context itself plus every {@code arkddd:ContextRelationship} it carries, in either
 * direction, already rendered with the peer's business code.
 *
 * <p><strong>Why this exists.</strong> Before this record, a context relationship was write-only:
 * {@code bc_link_context} recorded it, but neither {@code bc_get} nor {@code bc_list} showed it -
 * inspecting one meant falling back to the generic {@code resource_get}/{@code store_overview}
 * read path (kogn-io/arknet#565). {@link BoundedContext} itself stays free of this - a
 * {@link de.hauschel.arknet.bc.domain.ContextRelationship} references two independent bounded
 * contexts and is never a field on either one (see that type's javadoc), so the aggregate cannot
 * carry it without breaking the very reason it is its own resource. This record is the
 * read-side projection that reunites the two without touching the aggregate, the same shape
 * {@code AdrDetail} already gives the ADR hexagon's own self-referential relations
 * ({@code supersededBy}/{@code relatedTo}).</p>
 *
 * @param context       the bounded context itself, with its opaque reference intact
 * @param relationships every relationship {@code context} carries, in either direction; empty if
 *                       it has none
 */
public record BoundedContextDetail(BoundedContext context, List<RelatedContext> relationships) {

    public BoundedContextDetail {
        Objects.requireNonNull(context, "context");
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }
}
