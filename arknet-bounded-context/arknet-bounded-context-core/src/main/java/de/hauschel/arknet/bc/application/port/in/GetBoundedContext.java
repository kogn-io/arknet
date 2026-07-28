// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: fetch a single bounded context by its business code.
 *
 * <p>Backs the MVP tool {@code bc_get}.</p>
 */
public interface GetBoundedContext {

    /**
     * Looks up a bounded context by its business code within a project.
     *
     * @param projectId the project (architecture model) to look up the bounded context in
     * @param code        the bounded-context code, e.g. {@code BC-1}
     * @return the bounded context if present, otherwise {@link Optional#empty()}
     */
    Optional<BoundedContext> get(ProjectId projectId, BoundedContextCode code);
}
