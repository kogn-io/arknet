// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.BoundedContextReferencedException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: removes a bounded context and its triples from the project entirely
 * (kogn-io/arknet#566), mirroring {@code DeleteConstraint} exactly.
 *
 * <p>Unlike {@link UpdateBoundedContext}, there is no field-level correction here - the whole
 * resource goes away, including the edges it owns ({@code arkddd:ubiquitousLanguageTerm},
 * {@code arkddd:partOf}, {@code arkddd:ownedBy}, {@code arkddd:domainVision}). The typical case is
 * a boundary that turned out not to be one: a context drawn too early, or two contexts that
 * collapsed into one.</p>
 *
 * <p><strong>No status gate.</strong> A bounded context carries no lifecycle status, so - unlike
 * {@code DeleteAdr} - nothing about the resource's own state can forbid the delete. The only
 * protection is the incoming-reference check: rejected outright, rather than silently orphaning an
 * edge, while anything still points at the context - see
 * {@link BoundedContextReferencedException}.</p>
 *
 * <p>A {@link de.hauschel.arknet.bc.domain.ContextRelationship} is a resource of its own, not a
 * field on either context it connects; it is therefore never deleted along with a context, and its
 * {@code arkddd:upstream}/{@code arkddd:downstream} edge is exactly what blocks the delete until
 * {@code bc_unlink_context} removes it.</p>
 */
public interface DeleteBoundedContext {

    /**
     * Deletes the bounded context identified by {@code code} from {@code projectId}.
     *
     * @param projectId the project (architecture model) the bounded context lives in
     * @param code      the bounded-context code, e.g. {@code BC-1}
     * @throws BoundedContextNotFoundException   if no bounded context with this code exists
     * @throws BoundedContextReferencedException if anything still references the bounded context
     */
    void delete(ProjectId projectId, BoundedContextCode code);
}
