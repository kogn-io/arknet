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
     * @param projectId     the project (architecture model) to list constraints from
     * @param displayLocale the BCP-47 language tag the caller wants every listed constraint's
     *                      {@code title}/{@code statement} shown in (issue #313), or {@code null}
     *                      to leave the choice to the out-adapter's own configured
     *                      display-language preference and its fallback chain - mirrors
     *                      {@link ListRequirements}'s own {@code displayLocale}
     * @return all constraints, never {@code null}
     */
    List<Constraint> list(ProjectId projectId, String displayLocale);
}
