// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a write would carry {@link RoleRef#LEGACY_PLACEHOLDER} forward as a use case's
 * primary role: the use case was written before v0.8.0, has no {@code arkreq:primaryRole} on
 * record and is shown with the read-time placeholder. Persisting the placeholder would turn the
 * gap into a role reference that points at nothing, so every change is refused until the update
 * names a real role. The use-cases counterpart of the requirements context's
 * {@code MissingAcceptanceCriteriaException}.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP tools
 * - pass its message on as the answer to the call.</p>
 */
public class MissingPrimaryRoleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient UseCaseCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the use case lives in
     * @param code      the use case still shown with the placeholder role
     */
    public MissingPrimaryRoleException(ProjectId projectId, UseCaseCode code) {
        super("use case " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " has no primary role on record - it was written before arkreq:primaryRole existed and is "
                + "shown with the read-time placeholder " + RoleRef.LEGACY_PLACEHOLDER.value().value()
                + "; set a real role via uc_update (primaryRole: ROLE-n) before this use case can be changed "
                + "in any other way");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the use case lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the use case shown with the placeholder role */
    public UseCaseCode code() {
        return code;
    }
}
