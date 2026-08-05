// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a {@code req_update} acceptance-criterion text correction names a
 * {@link AcceptanceCriterion#position() position} that does not match any existing criterion of
 * the targeted requirement.
 *
 * <p>An expected, rejectable caller input (a typo'd or stale position), not a programming error:
 * a text patch must correct an existing criterion rather than silently being dropped or
 * accidentally inserting a new one. Mirrors
 * {@code de.hauschel.arknet.uc.domain.StepPositionNotFoundException}.</p>
 */
public class AcceptanceCriterionPositionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RequirementCode code;
    private final int position;

    /**
     * Creates the exception.
     *
     * @param projectId the project the requirement lives in
     * @param code      the requirement code the correction targeted
     * @param position  the position named by the patch that matched no existing criterion
     */
    public AcceptanceCriterionPositionNotFoundException(ProjectId projectId, RequirementCode code, int position) {
        super("requirement " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " has no acceptance criterion at position " + position);
        this.projectId = projectId;
        this.code = code;
        this.position = position;
    }

    /** @return the project the requirement lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the requirement code the correction targeted */
    public RequirementCode requirementCode() {
        return code;
    }

    /** @return the position named by the patch that matched no existing criterion */
    public int position() {
        return position;
    }
}
