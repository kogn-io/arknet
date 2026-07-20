// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: fetch a single requirement by its business code.
 *
 * <p>Backs the MVP tool {@code req_get}.</p>
 */
public interface GetRequirement {

    /**
     * Looks up a requirement by its business code within a workspace.
     *
     * @param workspaceId the workspace (architecture model) to look up the requirement in
     * @param code        the requirement code, e.g. {@code FR-1}
     * @return the requirement if present, otherwise {@link Optional#empty()}
     */
    Optional<Requirement> get(WorkspaceId workspaceId, RequirementCode code);
}
