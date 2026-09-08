// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import java.util.Objects;

import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.ContextRelationshipId;
import de.hauschel.arknet.bc.domain.RelationshipType;

/**
 * One {@code arkddd:ContextRelationship} rendered from the point of view of the bounded context
 * that carries it in {@link BoundedContextDetail#relationships()} - the projection issue #565
 * needed because {@code arkddd:ContextRelationship} is its own resource, not a field on either
 * side's {@link de.hauschel.arknet.bc.domain.BoundedContext} (see that type's javadoc for why).
 *
 * @param relationshipId   the relationship's own opaque identity - what {@code bc_unlink_context}
 *                         and a future {@code impact_analysis} traversal address it by
 * @param direction        whether the carrying context is {@link Direction#UPSTREAM_OF} or
 *                         {@link Direction#DOWNSTREAM_OF} {@code peerId}
 * @param peerId           the other bounded context's opaque identity
 * @param peerCode         the other bounded context's business code (e.g. {@code BC-1}), resolved
 *                         by the application service - a human who typed a code expects to see a
 *                         code again, not an IRI they cannot re-type
 * @param relationshipType the DDD context-mapping pattern classifying this relationship
 */
public record RelatedContext(ContextRelationshipId relationshipId, Direction direction, BoundedContextId peerId,
        BoundedContextCode peerCode, RelationshipType relationshipType) {

    public RelatedContext {
        Objects.requireNonNull(relationshipId, "relationshipId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(peerCode, "peerCode");
        Objects.requireNonNull(relationshipType, "relationshipType");
    }

    /** Which side of the {@code arkddd:ContextRelationship} the carrying bounded context is on. */
    public enum Direction {
        /** The carrying context is {@code arkddd:upstream}; {@link RelatedContext#peerId()} is downstream. */
        UPSTREAM_OF,
        /** The carrying context is {@code arkddd:downstream}; {@link RelatedContext#peerId()} is upstream. */
        DOWNSTREAM_OF
    }
}
