// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a read-modify-write round trip (today {@code actor_update}) keeps losing the
 * optimistic-concurrency race against other writers of the same actor (see
 * {@link de.hauschel.arknet.actor.application.port.out.ActorRepository#compareAndUpdate}) across
 * every retry attempt the application service allows.
 *
 * <p>An expected-but-rare domain outcome, not a programming error: sustained, high-frequency
 * concurrent writes to the very same actor. Distinct from {@link ActorNotFoundException} - the
 * actor exists throughout, it is just never observed to still match the caller's stale read for
 * long enough to commit.</p>
 */
public class ActorConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient ActorCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the actor lives in
     * @param code      the actor code whose update kept losing the race
     */
    public ActorConcurrentlyModifiedException(ProjectId projectId, ActorCode code) {
        super("actor " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the actor lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the actor code whose update kept losing the race */
    public ActorCode actorCode() {
        return code;
    }
}
