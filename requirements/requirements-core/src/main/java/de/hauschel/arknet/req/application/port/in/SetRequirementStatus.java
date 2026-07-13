package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.WorkspaceId;

/**
 * Driving port: change the lifecycle status of a requirement.
 *
 * <p>Backs the MVP tool {@code req_set_status}. Which transitions are legal is
 * policy of the implementing application service.</p>
 */
public interface SetRequirementStatus {

    /**
     * Sets a new status on the requirement identified by {@code id} within a workspace.
     *
     * @param workspaceId the workspace (architecture model) the requirement lives in
     * @param id          the requirement identity
     * @param status      the target status
     * @return the updated requirement
     */
    Requirement setStatus(WorkspaceId workspaceId, RequirementId id, RequirementStatus status);
}
