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
 */
public class RequirementNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient RequirementId id;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace that was searched
     * @param id          the requirement identity that was not found
     */
    public RequirementNotFoundException(WorkspaceId workspaceId, RequirementId id) {
        super("no requirement " + Objects.requireNonNull(id, "id").value()
                + " in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value());
        this.workspaceId = workspaceId;
        this.id = id;
    }

    /** @return the workspace that was searched */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the requirement identity that was not found */
    public RequirementId requirementId() {
        return id;
    }
}
