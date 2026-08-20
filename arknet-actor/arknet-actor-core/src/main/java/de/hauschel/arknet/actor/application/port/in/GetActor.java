// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: fetch a single actor by its business code.
 *
 * <p>Backs the MVP tool {@code actor_get}. There is no {@code displayLocale} argument, unlike
 * {@code GetRequirement}/{@code GetConstraint}: an actor's name and description are plain untagged
 * literals (see {@link Actor}), so there is no language variant to choose between.</p>
 */
public interface GetActor {

    /**
     * Looks up an actor by its business code within a project.
     *
     * @param projectId the project (architecture model) to look up the actor in
     * @param code      the actor code, e.g. {@code ACTOR-1}
     * @return the actor if present, otherwise {@link Optional#empty()}
     */
    Optional<Actor> get(ProjectId projectId, ActorCode code);
}
