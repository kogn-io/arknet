// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: fetch a single role by its business code.
 *
 * <p>Backs the tool {@code role_get}. Unlike {@link GetActor}, {@code displayLocale} is a real
 * argument here - a role's {@code name}/{@code description} are language-tagged literals, mirroring
 * {@code GetConstraint} exactly (see {@link de.hauschel.arknet.actor.domain.Role}'s own javadoc for
 * why).</p>
 */
public interface GetRole {

    /**
     * Looks up a role by its business code within a project.
     *
     * @param projectId     the project (architecture model) to look up the role in
     * @param code          the role code, e.g. {@code ROLE-1}
     * @param displayLocale the BCP-47 language tag the caller wants {@code name}/
     *                      {@code description} shown in, overriding this repository's own
     *                      configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged
     * @return the role if present, otherwise {@link Optional#empty()}
     */
    Optional<RoleDetail> get(ProjectId projectId, RoleCode code, String displayLocale);
}
