// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when an operation refers to a role that does not exist in the targeted project.
 *
 * <p>An expected domain outcome (not a programming error), mirroring {@link ActorNotFoundException}
 * exactly: driving adapters translate it into a user-facing "unknown role" message rather than a
 * stack trace. Lookup by a human is by {@link RoleCode} (e.g. {@code ROLE-1}), not by the opaque
 * {@link RoleId} - that is what the user actually typed.</p>
 */
public class RoleNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RoleCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project that was searched
     * @param code      the role code that was not found
     */
    public RoleNotFoundException(ProjectId projectId, RoleCode code) {
        super("no role " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project that was searched */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the role code that was not found */
    public RoleCode roleCode() {
        return code;
    }
}
