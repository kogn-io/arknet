// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.List;

import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: list all managed actors.
 *
 * <p>Backs the MVP tool {@code actor_list}. {@code displayLocale} selects which language variant of
 * each actor's {@code name}/{@code description} is shown, mirroring {@code ListRoles} exactly
 * (kogn-io/arknet#520) - see {@link DescribeActorDisplayFallback} for the companion port backing
 * the fallback-visibility line.</p>
 */
public interface ListActors {

    /**
     * Returns all actors currently under management in the given project.
     *
     * @param projectId     the project (architecture model) to list actors from
     * @param displayLocale the BCP-47 language tag the caller wants each actor's {@code name}/
     *                      {@code description} shown in, overriding this repository's own
     *                      configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged
     * @return all actors, never {@code null}
     */
    List<Actor> list(ProjectId projectId, String displayLocale);
}
