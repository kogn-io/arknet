// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a read-modify-write round trip (today {@code role_update}) keeps losing the
 * optimistic-concurrency race against other writers of the same role (see
 * {@link de.hauschel.arknet.actor.application.port.out.RoleRepository#compareAndUpdate}) across
 * every retry attempt the application service allows - mirrors
 * {@link ActorConcurrentlyModifiedException} exactly.
 *
 * <p>An expected-but-rare domain outcome, not a programming error: sustained, high-frequency
 * concurrent writes to the very same role. Distinct from {@link RoleNotFoundException} - the role
 * exists throughout, it is just never observed to still match the caller's stale read for long
 * enough to commit.</p>
 */
public class RoleConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RoleCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the role lives in
     * @param code      the role code whose update kept losing the race
     */
    public RoleConcurrentlyModifiedException(ProjectId projectId, RoleCode code) {
        super("role " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the role lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the role code whose update kept losing the race */
    public RoleCode roleCode() {
        return code;
    }
}
