// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when an operation refers to an actor that does not exist in the targeted project.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP tools -
 * translate it into a user-facing "unknown actor" message rather than a stack trace.</p>
 *
 * <p>Lookup by a human is by {@link ActorCode} (e.g. {@code ACTOR-1}), not by the opaque
 * {@link ActorId} - that is what the user actually typed.</p>
 */
public class ActorNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient ActorCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project that was searched
     * @param code      the actor code that was not found
     */
    public ActorNotFoundException(ProjectId projectId, ActorCode code) {
        super("no actor " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project that was searched */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the actor code that was not found */
    public ActorCode actorCode() {
        return code;
    }
}
