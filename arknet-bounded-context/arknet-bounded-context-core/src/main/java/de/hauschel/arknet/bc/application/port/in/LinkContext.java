// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: record a directed DDD context-mapping relationship between two existing bounded
 * contexts.
 *
 * <p>Backs the MVP tool {@code bc_link_context}. Structurally the strategic-design counterpart of
 * {@link LinkTerm}, but the relationship it creates is its own aggregate/resource
 * ({@link ContextRelationship}), not a field on either side's {@code BoundedContext} - see that
 * type's javadoc for why. Pure CRUD: this port never judges, infers or suggests which
 * {@link RelationshipType} applies to a given pair - that call belongs entirely to the
 * interviewing agent or user.</p>
 *
 * <p>{@code upstreamCode}/{@code downstreamCode} are exactly what a human types (e.g.
 * {@code BC-1}), never an IRI - the MCP boundary never surfaces the store-internal identity.
 * Resolving them to the bounded contexts' opaque identities - and rejecting an unknown code -
 * happens in the application service via the existing {@code BoundedContextRepository}, not here
 * and not in the driving (MCP) adapter.</p>
 */
public interface LinkContext {

    /**
     * Records a new {@link ContextRelationship} from {@code upstreamCode} to
     * {@code downstreamCode}, classified by {@code relationshipType}. Both bounded contexts must
     * already exist. Unlike {@link LinkTerm#linkTerm}, this is not idempotent: every call mints
     * and persists a brand-new relationship, even one identical to an already-recorded one.
     *
     * @param projectId       the project (architecture model) both bounded contexts live in
     * @param upstreamCode    the upstream bounded context's code, e.g. {@code BC-1}
     * @param downstreamCode  the downstream bounded context's code, e.g. {@code BC-2}; must differ
     *                        from {@code upstreamCode}
     * @param relationshipType the DDD context-mapping pattern classifying this relationship
     * @return the newly created relationship
     */
    ContextRelationship linkContext(ProjectId projectId, BoundedContextCode upstreamCode,
            BoundedContextCode downstreamCode, RelationshipType relationshipType);
}
