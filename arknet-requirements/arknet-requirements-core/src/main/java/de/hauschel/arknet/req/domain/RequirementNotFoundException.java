// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Thrown when an operation refers to a requirement that does not exist in the
 * targeted workspace.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters -
 * e.g. the MCP tools - translate it into a user-facing "unknown requirement"
 * message rather than a stack trace.</p>
 *
 * <p>Lookup by a human is by {@link RequirementCode} (e.g. {@code FR-1}), not by the opaque
 * {@link RequirementId} - that is what the user actually typed.</p>
 */
public class RequirementNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient RequirementCode code;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace that was searched
     * @param code        the requirement code that was not found
     */
    public RequirementNotFoundException(WorkspaceId workspaceId, RequirementCode code) {
        super("no requirement " + Objects.requireNonNull(code, "code").value()
                + " in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value());
        this.workspaceId = workspaceId;
        this.code = code;
    }

    /** @return the workspace that was searched */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the requirement code that was not found */
    public RequirementCode requirementCode() {
        return code;
    }
}
