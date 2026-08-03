// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when an operation refers to a constraint that does not exist in the targeted project -
 * mirrors {@link RequirementNotFoundException} exactly.
 *
 * <p>An expected domain outcome (not a programming error): both {@code constraint_get} and
 * {@link de.hauschel.arknet.req.application.RequirementService#linkConstraint} (resolving a
 * {@code req_link_constraint} call's human-typed constraint code) let this propagate as a
 * user-facing "unknown constraint" message rather than a stack trace.</p>
 *
 * <p>Lookup by a human is by {@link ConstraintCode} (e.g. {@code TCON-1}), not by the opaque
 * {@link ConstraintId} - that is what the user actually typed.</p>
 */
public class ConstraintNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient ConstraintCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project that was searched
     * @param code        the constraint code that was not found
     */
    public ConstraintNotFoundException(ProjectId projectId, ConstraintCode code) {
        super("no constraint " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project that was searched */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the constraint code that was not found */
    public ConstraintCode constraintCode() {
        return code;
    }
}
