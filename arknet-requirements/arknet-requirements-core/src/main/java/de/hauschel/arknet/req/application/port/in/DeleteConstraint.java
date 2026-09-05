// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
import de.hauschel.arknet.req.domain.ConstraintReferencedException;

/**
 * Driving port: removes a constraint and its triples from the project entirely (kogn-io/arknet#481),
 * mirroring {@code DeleteActor} exactly.
 *
 * <p>Unlike {@link UpdateConstraint}, there is no field-level correction here - the whole resource
 * goes away. The typical case is a record that turned out not to be a constraint at all: something
 * the project decided itself, which belongs in {@code adr_add} (or {@code req_add}), not here - see
 * the "imposed from outside" test {@code constraint_add}'s own description states. Rejected outright,
 * rather than silently orphaning an edge, if a requirement or use case still references the
 * constraint via {@code oslc_rm:constrainedBy} - see {@link ConstraintReferencedException}. Backs
 * the MVP tool {@code constraint_delete}, the closing counterpart of {@link AddConstraint} this
 * resource type lacked until now.</p>
 */
public interface DeleteConstraint {

    /**
     * Deletes the constraint identified by {@code code} from {@code projectId}.
     *
     * @param projectId the project (architecture model) the constraint lives in
     * @param code      the constraint code, e.g. {@code TCON-1}
     * @throws ConstraintNotFoundException   if no constraint with this identity exists
     * @throws ConstraintReferencedException if a requirement or use case still references the
     *                                        constraint
     */
    void delete(ProjectId projectId, ConstraintCode code);
}
