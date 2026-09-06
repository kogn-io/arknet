// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: list all managed roles.
 *
 * <p>Backs the tool {@code role_list}. {@code displayLocale} selects which language variant of each
 * role's {@code name}/{@code description} is shown, mirroring {@code ListConstraints} exactly - see
 * {@link DescribeRoleDisplayFallback} for the companion port backing the fallback-visibility line.
 * </p>
 */
public interface ListRoles {

    /**
     * Returns all roles currently under management in the given project.
     *
     * @param projectId     the project (architecture model) to list roles from
     * @param displayLocale the BCP-47 language tag the caller wants each role's {@code name}/
     *                      {@code description} shown in, overriding this repository's own
     *                      configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged
     * @return all roles, never {@code null}
     */
    List<RoleDetail> list(ProjectId projectId, String displayLocale);
}
