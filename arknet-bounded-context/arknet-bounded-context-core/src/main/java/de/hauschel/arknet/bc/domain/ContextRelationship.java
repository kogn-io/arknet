// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

/**
 * A single directed DDD context-mapping relationship between two bounded contexts
 * ({@code arkddd:ContextRelationship}): {@code upstream} names the context whose model/protocol
 * prevails, {@code downstream} the context that consumes it, and {@code relationshipType}
 * classifies the integration pattern (Evans, Vernon).
 *
 * <p>Value object of the bounded-context component, but its own aggregate/resource - not a field
 * on {@link BoundedContext}. Unlike {@link BoundedContext#usesTerms()}, which lives inside its
 * owning aggregate because that aggregate's out-adapter replaces its triples wholesale by
 * identity, a {@link ContextRelationship} references <em>two</em> independent
 * {@link BoundedContext} identities; folding it into either side's record would have that side's
 * unrelated replace-by-identity write silently corrupt or drop it.</p>
 *
 * <p>Pure CRUD: linking two bounded contexts is a fact the interviewing agent or user asserts
 * deliberately, not a judgement this type or its out-adapter makes. All invariants are enforced
 * in the compact constructor; instances are immutable.</p>
 *
 * @param id               opaque, unchanging identity of this relationship; minted once by a
 *                         {@link de.hauschel.arknet.kernel.ResourceIdFactory}
 * @param upstream         the bounded context whose model/protocol this relationship treats as
 *                         upstream; maps to {@code arkddd:upstream}
 * @param downstream       the bounded context that consumes {@code upstream}'s model/protocol;
 *                         maps to {@code arkddd:downstream}. Must differ from {@code upstream} - a
 *                         bounded context cannot be upstream and downstream of itself
 * @param relationshipType the DDD context-mapping pattern classifying this relationship; maps to
 *                         {@code arkddd:relationshipType}
 */
public record ContextRelationship(
        ContextRelationshipId id,
        BoundedContextId upstream,
        BoundedContextId downstream,
        RelationshipType relationshipType) {

    public ContextRelationship {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(downstream, "downstream");
        Objects.requireNonNull(relationshipType, "relationshipType");
        if (upstream.equals(downstream)) {
            throw new IllegalArgumentException("upstream and downstream must not be the same bounded context");
        }
    }
}
