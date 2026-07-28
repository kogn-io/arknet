// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a {@code uc_update} step-text correction names a {@link Step#position() position}
 * that does not match any existing main-flow step of the targeted use case.
 *
 * <p>An expected, rejectable caller input (a typo'd or stale position), not a programming error:
 * a text patch must correct an existing step rather than silently being dropped or accidentally
 * inserting a new one.</p>
 */
public class StepPositionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient UseCaseCode code;
    private final int position;

    /**
     * Creates the exception.
     *
     * @param projectId the project the use case lives in
     * @param code      the use-case code the correction targeted
     * @param position  the position named by the patch that matched no existing step
     */
    public StepPositionNotFoundException(ProjectId projectId, UseCaseCode code, int position) {
        super("use case " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " has no step at position " + position);
        this.projectId = projectId;
        this.code = code;
        this.position = position;
    }

    /** @return the project the use case lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the use-case code the correction targeted */
    public UseCaseCode useCaseCode() {
        return code;
    }

    /** @return the position named by the patch that matched no existing step */
    public int position() {
        return position;
    }
}
