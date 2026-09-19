// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code uc_unlink_constraint} names a constraint that the use case does not
 * currently link.
 *
 * <p>An expected domain outcome, not a programming error, the exact counterpart of
 * {@link TermNotLinkedException} for {@code oslc_rm:constrainedBy}: an unlink that removes
 * nothing must fail loudly, or a typo in the constraint code reads as a successful removal while
 * the edge stays exactly where the caller believes it is gone. Linking an already-linked
 * constraint remains an idempotent no-op.</p>
 *
 * <p>Surfaces as the tool call's own error message rather than a stack trace; no driving adapter
 * has to catch and translate it.</p>
 */
public class ConstraintNotLinkedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient UseCaseCode code;
    private final transient String constraintCode;

    /**
     * Creates the exception.
     *
     * @param projectId      the project that was searched
     * @param code           the use-case code the caller named
     * @param constraintCode the constraint code the caller named
     */
    public ConstraintNotLinkedException(ProjectId projectId, UseCaseCode code, String constraintCode) {
        super("use case " + Objects.requireNonNull(code, "code").value() + " is not constrained by "
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

    /** @return the use-case code the caller named */
    public UseCaseCode code() {
        return code;
    }

    /** @return the constraint code the caller named */
    public String constraintCode() {
        return constraintCode;
    }
}
