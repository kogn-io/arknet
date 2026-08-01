// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when an operation refers to a term that does not exist in the targeted
 * project.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP
 * tools - translate it into a user-facing "unknown term" message rather than a stack trace.</p>
 *
 * <p>Lookup by a human is by {@link TermCode} (e.g. {@code TERM-1}), not by the opaque
 * {@link TermId} - that is what the user actually typed.</p>
 */
public class TermNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient TermCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project that was searched
     * @param code        the term code that was not found
     */
    public TermNotFoundException(ProjectId projectId, TermCode code) {
        super("no term " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project that was searched */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the term code that was not found */
    public TermCode code() {
        return code;
    }
}
