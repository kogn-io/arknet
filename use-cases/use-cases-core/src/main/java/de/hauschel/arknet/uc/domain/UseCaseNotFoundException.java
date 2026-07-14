package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Thrown when an operation refers to a use case that does not exist in the
 * targeted workspace.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters -
 * e.g. the MCP tools - translate it into a user-facing "unknown use case"
 * message rather than a stack trace.</p>
 */
public class UseCaseNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient UseCaseId id;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace that was searched
     * @param id          the use-case identity that was not found
     */
    public UseCaseNotFoundException(WorkspaceId workspaceId, UseCaseId id) {
        super("no use case " + Objects.requireNonNull(id, "id").value()
                + " in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value());
        this.workspaceId = workspaceId;
        this.id = id;
    }

    /** @return the workspace that was searched */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the use-case identity that was not found */
    public UseCaseId useCaseId() {
        return id;
    }
}
