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
 * <p>Backs the MVP tool {@code actor_get}. {@code displayLocale} is a real argument since
 * kogn-io/arknet#520: an actor's {@code name}/{@code description} are language-tagged literals,
 * mirroring {@code GetRole} exactly.</p>
 */
public interface GetActor {

    /**
     * Looks up an actor by its business code within a project.
     *
     * @param projectId     the project (architecture model) to look up the actor in
     * @param code          the actor code, e.g. {@code ACTOR-1}
     * @param displayLocale the BCP-47 language tag the caller wants {@code name}/
     *                      {@code description} shown in, overriding this repository's own
     *                      configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged
     * @return the actor if present, otherwise {@link Optional#empty()}
     */
    Optional<Actor> get(ProjectId projectId, ActorCode code, String displayLocale);
}
