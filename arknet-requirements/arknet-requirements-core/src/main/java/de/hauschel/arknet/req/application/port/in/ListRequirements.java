// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Driving port: list all managed requirements.
 *
 * <p>Backs the MVP tool {@code req_list}.</p>
 */
public interface ListRequirements {

    /**
     * Returns all requirements currently under management in the given workspace.
     *
     * @param workspaceId the workspace (architecture model) to list requirements from
     * @return all requirements, never {@code null}
     */
    List<Requirement> list(WorkspaceId workspaceId);
}
