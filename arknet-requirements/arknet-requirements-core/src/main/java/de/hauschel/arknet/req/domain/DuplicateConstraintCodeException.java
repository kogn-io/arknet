// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@link de.hauschel.arknet.req.application.port.out.ConstraintRepository#create} is
 * called with a {@link ConstraintCode} that already labels a different constraint in the targeted
 * project.
 *
 * <p>Mirrors {@link DuplicateRequirementCodeException} exactly, one level down: distinct from
 * {@link ResourceAlreadyExistsException} (an opaque-identity collision, a programming error),
 * this flags a business-label collision, e.g. two constraints both claiming {@code TCON-1}. Since
 * {@code dcterms:identifier} is how a human addresses a constraint, this is an expected,
 * rejectable outcome - not a stack trace, and the signal {@link
 * de.hauschel.arknet.kernel.CodeAssignment}'s retry consumes when a concurrent
 * {@code constraint_add} claims the same candidate code first.</p>
 */
public class DuplicateConstraintCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient ConstraintCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the code collided in
     * @param code        the constraint code that already exists
     */
    public DuplicateConstraintCodeException(ProjectId projectId, ConstraintCode code) {
        super("constraint code " + Objects.requireNonNull(code, "code").value()
                + " already exists in project " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the code collided in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the constraint code that already exists */
    public ConstraintCode code() {
        return code;
    }
}
