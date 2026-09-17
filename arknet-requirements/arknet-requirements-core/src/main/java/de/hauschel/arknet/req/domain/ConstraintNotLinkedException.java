// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code req_unlink_constraint} names a constraint that the requirement is not
 * currently bound by.
 *
 * <p>An expected domain outcome, not a programming error, and the counterpart of {@link
 * TermNotLinkedException} for the {@code oslc_rm:constrainedBy} edge (kogn-io/arknet#598): an
 * unlink that removes nothing must fail loudly, or a typo in the constraint code reads as a
 * successful removal and the edge stays where the caller believes it is gone. Linking an
 * already-linked constraint remains an idempotent no-op - adding what is already there leaves the
 * model in the state the caller asked for, removing what is not there does not. Distinct from
 * {@link ConstraintNotFoundException}: that one fires when the constraint code itself is unknown,
 * this one when the constraint exists but is not linked to this requirement.</p>
 *
 * <p>Surfaces as the tool call's own error message rather than a stack trace; no driving adapter
 * has to catch and translate it.</p>
 */
public class ConstraintNotLinkedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RequirementCode code;
    private final transient String constraintCode;

    /**
     * Creates the exception.
     *
     * @param projectId      the project that was searched
     * @param code           the requirement code the caller named
     * @param constraintCode the constraint code the caller named
     */
    public ConstraintNotLinkedException(ProjectId projectId, RequirementCode code, String constraintCode) {
        super("requirement " + Objects.requireNonNull(code, "code").value() + " is not constrained by "
                + Objects.requireNonNull(constraintCode, "constraintCode") + " in project "
                + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
        this.constraintCode = constraintCode;
    }

    /** @return the project that was searched */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the requirement code the caller named */
    public RequirementCode code() {
        return code;
    }

    /** @return the constraint code the caller named */
    public String constraintCode() {
        return constraintCode;
    }
}
