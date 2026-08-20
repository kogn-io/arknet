// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.List;

import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: list all managed actors.
 *
 * <p>Backs the MVP tool {@code actor_list}.</p>
 */
public interface ListActors {

    /**
     * Returns all actors currently under management in the given project.
     *
     * @param projectId the project (architecture model) to list actors from
     * @return all actors, never {@code null}
     */
    List<Actor> list(ProjectId projectId);
}
