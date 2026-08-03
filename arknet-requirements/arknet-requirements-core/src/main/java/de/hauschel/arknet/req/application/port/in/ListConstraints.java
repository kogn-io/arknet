// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Constraint;

/**
 * Driving port: list all managed constraints.
 *
 * <p>Backs the MVP tool {@code constraint_list}.</p>
 */
public interface ListConstraints {

    /**
     * Returns all constraints currently under management in the given project.
     *
     * @param projectId the project (architecture model) to list constraints from
     * @return all constraints, never {@code null}
     */
    List<Constraint> list(ProjectId projectId);
}
