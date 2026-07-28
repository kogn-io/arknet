// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when an operation refers to a use case that does not exist in the
 * targeted workspace.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters -
 * e.g. the MCP tools - translate it into a user-facing "unknown use case"
 * message rather than a stack trace.</p>
 *
 * <p>Lookup by a human is by {@link UseCaseCode} (e.g. {@code UC1}), not by the opaque
 * {@link UseCaseId} - that is what the user actually typed.</p>
 */
public class UseCaseNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient UseCaseCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the workspace that was searched
     * @param code        the use-case code that was not found
     */
    public UseCaseNotFoundException(ProjectId projectId, UseCaseCode code) {
        super("no use case " + Objects.requireNonNull(code, "code").value()
                + " in workspace " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the workspace that was searched */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the use-case code that was not found */
    public UseCaseCode useCaseCode() {
        return code;
    }
}
