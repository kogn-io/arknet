// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: correct the title, description and/or acceptance criteria of an already-created
 * requirement.
 *
 * <p>Backs the MVP tool {@code req_update} (issue #162). Requirements elicited during an
 * interview are sometimes sharpened afterwards - e.g. a domain fact only surfaces once the
 * conversation continues - and until this port existed there was no way to correct a requirement
 * already in the store short of duplicating it under a new code. Unlike {@code req_add}'s
 * required arguments, every field here is optional: {@code null} leaves that field unchanged, so
 * a caller can correct only the description without having to restate the title.</p>
 */
public interface UpdateRequirement {

    /**
     * Updates the requirement identified by {@code code} within a workspace, leaving any
     * {@code null} argument unchanged.
     *
     * @param workspaceId         the workspace (architecture model) the requirement lives in
     * @param code                the requirement code, e.g. {@code FR-1}
     * @param title               the new title, or {@code null} to leave it unchanged
     * @param description         the new normative statement, or {@code null} to leave it unchanged
     * @param acceptanceCriteria  the new "Done when ..." criteria, or {@code null} to leave them
     *                            unchanged
     * @return the updated requirement
     */
    Requirement update(WorkspaceId workspaceId, RequirementCode code, String title, String description,
            List<String> acceptanceCriteria);
}
