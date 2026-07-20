// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Thrown when an operation refers to a bounded context that does not exist in the targeted
 * workspace.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP
 * tools - translate it into a user-facing "unknown bounded context" message rather than a stack
 * trace.</p>
 *
 * <p>Lookup by a human is by {@link BoundedContextCode} (e.g. {@code BC-1}), not by the opaque
 * {@link BoundedContextId} - that is what the user actually typed.</p>
 */
public class BoundedContextNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient BoundedContextCode code;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace that was searched
     * @param code        the bounded-context code that was not found
     */
    public BoundedContextNotFoundException(WorkspaceId workspaceId, BoundedContextCode code) {
        super("no bounded context " + Objects.requireNonNull(code, "code").value()
                + " in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value());
        this.workspaceId = workspaceId;
        this.code = code;
    }

    /** @return the workspace that was searched */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the bounded-context code that was not found */
    public BoundedContextCode boundedContextCode() {
        return code;
    }
}
