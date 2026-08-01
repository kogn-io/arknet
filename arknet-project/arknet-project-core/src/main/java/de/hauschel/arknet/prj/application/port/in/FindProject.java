// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.Project;

/**
 * Driving port: look up a project's current state by its opaque identity.
 *
 * <p>Distinct from {@link ResolveProject}, which resolves a client-supplied anchor: a caller here
 * already holds a {@link ProjectId} (typically from an anchor resolved earlier in the same call)
 * and wants the project's other attributes - today, its label. The gateway role
 * ADR-008 grants an in-adapter of a neighbour bounded context is exactly this: the store report
 * borrows this port to show a project's registered label instead of its raw id.</p>
 */
public interface FindProject {

    /**
     * Finds a project by its opaque identity.
     *
     * @param id the project identity to look up
     * @return the project if registered, otherwise {@link Optional#empty()}
     */
    Optional<Project> findById(ProjectId id);
}
