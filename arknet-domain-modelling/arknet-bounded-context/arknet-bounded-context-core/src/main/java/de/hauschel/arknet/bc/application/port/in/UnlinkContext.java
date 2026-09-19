// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.ContextRelationshipNotFoundException;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: remove a previously recorded {@code arkddd:ContextRelationship} between two
 * bounded contexts.
 *
 * <p>Backs the MVP tool {@code bc_unlink_context}, the counterpart {@link LinkContext} was
 * missing before issue #565: a relationship recorded with a typo'd type or a swapped direction had
 * no way back. Addressed by the same triple {@link LinkContext#linkContext} takes - upstream code,
 * downstream code, relationship type - not by the relationship's own opaque identity, which never
 * crosses the MCP boundary. No deletion guard is needed the way {@code term_delete}/
 * {@code actor_delete} need one: nothing in this codebase references a
 * {@link de.hauschel.arknet.bc.domain.ContextRelationship} by its own identity, so removing one
 * orphans nothing.</p>
 */
public interface UnlinkContext {

    /**
     * Removes the {@code arkddd:ContextRelationship} from {@code upstreamCode} to
     * {@code downstreamCode} classified as {@code relationshipType}, if one is currently recorded.
     *
     * @param projectId        the project (architecture model) both bounded contexts live in
     * @param upstreamCode     the upstream bounded context's code, e.g. {@code BC-1}
     * @param downstreamCode   the downstream bounded context's code, e.g. {@code BC-2}
     * @param relationshipType the DDD context-mapping pattern classifying the relationship to remove
     * @throws de.hauschel.arknet.bc.domain.BoundedContextNotFoundException if either code is unknown
     * @throws ContextRelationshipNotFoundException if no relationship with this exact triple is
     *                                               currently recorded - never a silent no-op, since
     *                                               a typo'd type or swapped direction is exactly
     *                                               the mistake this tool exists to let a caller
     *                                               undo without doubt about what was removed
     */
    void unlinkContext(ProjectId projectId, BoundedContextCode upstreamCode, BoundedContextCode downstreamCode,
            RelationshipType relationshipType);
}
