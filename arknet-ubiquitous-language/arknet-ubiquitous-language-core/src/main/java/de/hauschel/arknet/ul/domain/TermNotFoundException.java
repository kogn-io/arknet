package de.hauschel.arknet.ul.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Thrown when an operation refers to a term that does not exist in the targeted
 * workspace.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP
 * tools - translate it into a user-facing "unknown term" message rather than a stack trace.</p>
 *
 * <p>Lookup by a human is by {@link TermCode} (e.g. {@code TERM-1}), not by the opaque
 * {@link TermId} - that is what the user actually typed.</p>
 */
public class TermNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient TermCode code;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace that was searched
     * @param code        the term code that was not found
     */
    public TermNotFoundException(WorkspaceId workspaceId, TermCode code) {
        super("no term " + Objects.requireNonNull(code, "code").value()
                + " in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value());
        this.workspaceId = workspaceId;
        this.code = code;
    }

    /** @return the workspace that was searched */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the term code that was not found */
    public TermCode termCode() {
        return code;
    }
}
