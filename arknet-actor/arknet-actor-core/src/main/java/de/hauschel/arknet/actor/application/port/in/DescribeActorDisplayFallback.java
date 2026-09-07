// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.Map;

import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorDisplayFallback;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: for every actor of a project whose {@code name}/{@code description} had to fall
 * back past the requested/project-default display language, the tag of the variant actually shown
 * (kogn-io/arknet#520). Backs the fallback-visibility line {@code actor_list} appends to an actor
 * whose gap would otherwise be invisible - mirrors {@code DescribeRoleDisplayFallback} exactly.
 *
 * <p>Deliberately a separate, single-method port rather than a second method on {@link ListActors},
 * for the same reason {@code DescribeRoleDisplayFallback} is kept out of {@link ListActors}'s
 * sibling {@code ListRoles}.</p>
 */
public interface DescribeActorDisplayFallback {

    /**
     * @param projectId     the project (architecture model) to list actors from
     * @param displayLocale the BCP-47 language tag {@code actor_list} resolved for this call
     *                      (explicit tool argument, else the project's own configured default), or
     *                      {@code null}
     * @return an actor's code maps to a non-{@linkplain ActorDisplayFallback#isEmpty() empty}
     *         fallback only when at least one of its two fields actually fell back; an actor
     *         showing both fields in the requested language is simply absent from the map
     */
    Map<ActorCode, ActorDisplayFallback> describe(ProjectId projectId, String displayLocale);
}
