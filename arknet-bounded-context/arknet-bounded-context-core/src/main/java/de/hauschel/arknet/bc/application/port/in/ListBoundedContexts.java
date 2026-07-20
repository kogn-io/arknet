// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import java.util.List;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Driving port: list all managed bounded contexts.
 *
 * <p>Backs the MVP tool {@code bc_list}.</p>
 */
public interface ListBoundedContexts {

    /**
     * Returns all bounded contexts currently under management in the given workspace.
     *
     * @param workspaceId the workspace (architecture model) to list bounded contexts from
     * @return all bounded contexts, never {@code null}
     */
    List<BoundedContext> list(WorkspaceId workspaceId);
}
