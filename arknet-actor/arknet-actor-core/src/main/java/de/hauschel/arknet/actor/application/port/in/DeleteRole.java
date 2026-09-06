// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleNotFoundException;
import de.hauschel.arknet.actor.domain.RoleReferencedException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: removes a role and its triples from the project entirely - mirrors
 * {@link DeleteActor} exactly, including the referenced-resource guard (see
 * {@link RoleReferencedException}'s own javadoc for why that guard is built ahead of a real
 * consumer today). Backs the tool {@code role_delete}.
 */
public interface DeleteRole {

    /**
     * Deletes the role identified by {@code code} from {@code projectId}.
     *
     * @param projectId the project (architecture model) the role lives in
     * @param code      the role code, e.g. {@code ROLE-1}
     * @throws RoleNotFoundException   if no role with this identity exists
     * @throws RoleReferencedException if anything else in the project still references the role
     */
    void delete(ProjectId projectId, RoleCode code);
}
