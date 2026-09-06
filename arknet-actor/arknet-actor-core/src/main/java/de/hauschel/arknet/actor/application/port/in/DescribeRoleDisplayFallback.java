// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.Map;

import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleDisplayFallback;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: for every role of a project whose {@code name}/{@code description} had to fall
 * back past the requested/project-default display language, the tag of the variant actually shown
 * (kogn-io/arknet#475/kogn-io/arknet#516). Backs the fallback-visibility line {@code role_list}
 * appends to a role whose gap would otherwise be invisible - mirrors
 * {@code DescribeConstraintDisplayFallback} exactly.
 *
 * <p>Deliberately a separate, single-method port rather than a second method on {@link ListRoles},
 * for the same reason {@code DescribeConstraintDisplayFallback} is kept out of
 * {@code ListConstraints}.</p>
 */
public interface DescribeRoleDisplayFallback {

    /**
     * @param projectId     the project (architecture model) to list roles from
     * @param displayLocale the BCP-47 language tag {@code role_list} resolved for this call
     *                      (explicit tool argument, else the project's own configured default), or
     *                      {@code null}
     * @return a role's code maps to a non-{@linkplain RoleDisplayFallback#isEmpty() empty} fallback
     *         only when at least one of its two fields actually fell back; a role showing both
     *         fields in the requested language is simply absent from the map
     */
    Map<RoleCode, RoleDisplayFallback> describe(ProjectId projectId, String displayLocale);
}
