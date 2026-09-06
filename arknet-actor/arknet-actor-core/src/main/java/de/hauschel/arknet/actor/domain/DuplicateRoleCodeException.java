// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@link de.hauschel.arknet.actor.application.port.out.RoleRepository#create} is
 * called with a {@link RoleCode} that already labels a different role in the targeted project - or
 * when {@link de.hauschel.arknet.actor.application.port.out.RoleRepository#compareAndUpdate} changes
 * an existing role's code to one that already labels a different role. Mirrors
 * {@link DuplicateActorCodeException} exactly.
 *
 * <p>Distinct from {@link ResourceAlreadyExistsException}: that one flags an opaque-identity
 * collision (a programming error), while this one flags a business-label collision, e.g. two roles
 * both claiming {@code ROLE-1}.</p>
 */
public class DuplicateRoleCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RoleCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the code collided in
     * @param code      the role code that already exists
     */
    public DuplicateRoleCodeException(ProjectId projectId, RoleCode code) {
        super("role code " + Objects.requireNonNull(code, "code").value()
                + " already exists in project " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the code collided in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the role code that already exists */
    public RoleCode code() {
        return code;
    }
}
