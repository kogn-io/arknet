package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.WorkspaceId;

/**
 * Driving port: register a new requirement.
 *
 * <p>Backs the MVP tool {@code req_add}. Identity assignment
 * ({@code FR-N}/{@code NFR-N}) and initial status are policy of the
 * implementing application service.</p>
 */
public interface AddRequirement {

    /**
     * Adds a new requirement.
     *
     * @param workspaceId the workspace (architecture model) to add the requirement to
     * @param command     the data describing the requirement to create
     * @return the persisted requirement including its assigned identity
     */
    Requirement add(WorkspaceId workspaceId, NewRequirement command);

    /**
     * Input data for {@link #add(WorkspaceId, NewRequirement)}.
     *
     * @param title       short human-readable summary
     * @param description the normative statement ("The system shall ...")
     * @param type        functional vs. non-functional classification
     */
    record NewRequirement(String title, String description, RequirementType type) {
    }
}
