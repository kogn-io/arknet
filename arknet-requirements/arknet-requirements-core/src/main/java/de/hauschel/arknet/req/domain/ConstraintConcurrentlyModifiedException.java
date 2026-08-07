// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code constraint_update}'s read-modify-write round trip keeps losing the
 * optimistic-concurrency race against other writers of the same constraint (see {@link
 * de.hauschel.arknet.req.application.port.out.ConstraintRepository#compareAndUpdate}) across
 * every retry attempt the application service allows.
 *
 * <p>An expected-but-rare domain outcome, not a programming error. Distinct from {@link
 * ConstraintNotFoundException} - the constraint exists throughout, it is just never observed to
 * still match the caller's stale read for long enough to commit.</p>
 *
 * <p>A constraint has exactly one caller-reachable write path after creation
 * ({@code constraint_update}, issue #313), so unlike {@link
 * RequirementConcurrentlyModifiedException} - whose requirement is written by
 * {@code req_update}/{@code req_set_status}/{@code req_link_term}/{@code req_link_constraint}
 * alike - the only way to reach this is two concurrent {@code constraint_update} calls naming the
 * same code.</p>
 */
public class ConstraintConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient ConstraintCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the constraint lives in
     * @param code      the constraint code whose update kept losing the race
     */
    public ConstraintConcurrentlyModifiedException(ProjectId projectId, ConstraintCode code) {
        super("constraint " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the constraint lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the constraint code whose update kept losing the race */
    public ConstraintCode constraintCode() {
        return code;
    }
}
