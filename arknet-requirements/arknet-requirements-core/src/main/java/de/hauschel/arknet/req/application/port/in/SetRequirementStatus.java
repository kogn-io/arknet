// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementStatus;

/**
 * Driving port: change the lifecycle status of a requirement.
 *
 * <p>Backs the MVP tool {@code req_set_status}. Which transitions are legal is
 * policy of the implementing application service.</p>
 */
public interface SetRequirementStatus {

    /**
     * Sets a new status on the requirement identified by {@code code} within a workspace.
     *
     * @param projectId the workspace (architecture model) the requirement lives in
     * @param code        the requirement code, e.g. {@code FR-1}
     * @param status      the target status
     * @return the updated requirement
     */
    Requirement setStatus(ProjectId projectId, RequirementCode code, RequirementStatus status);
}
