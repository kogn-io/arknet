// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@link de.hauschel.arknet.actor.application.port.out.ActorRepository#create} is
 * called with an {@link ActorCode} that already labels a different actor in the targeted project -
 * or when
 * {@link de.hauschel.arknet.actor.application.port.out.ActorRepository#compareAndUpdate} changes an
 * existing actor's code to one that already labels a different actor.
 *
 * <p>Distinct from {@link ResourceAlreadyExistsException}: that one flags an opaque-identity
 * collision (a programming error - identities are minted once and never reused), while this one
 * flags a business-label collision, e.g. two actors both claiming {@code ACTOR-1}. Since
 * {@code dcterms:identifier} is how a human addresses an actor, this is an expected, rejectable
 * outcome - not a stack trace.</p>
 */
public class DuplicateActorCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient ActorCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the code collided in
     * @param code      the actor code that already exists
     */
    public DuplicateActorCodeException(ProjectId projectId, ActorCode code) {
        super("actor code " + Objects.requireNonNull(code, "code").value()
                + " already exists in project " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the code collided in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the actor code that already exists */
    public ActorCode code() {
        return code;
    }
}
