// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;

/**
 * Driving port: attach a further anchor to an already-registered project.
 *
 * <p>Backs the tool {@code project_attach_anchor}. This is how a second git worktree, a further
 * IDE directory of the same checkout, or a copy at another location joins a project that already
 * exists, instead of being guessed or falling back to a default (ADR-016 decision 4). Takes a
 * {@link ProjectId}, not an anchor the caller wants to attach <em>to</em>: the caller resolved
 * that project via {@link ResolveProject} against its own current anchor first, so this port
 * never has to disambiguate "which project does the caller mean".</p>
 *
 * <p>Attaching an anchor the project already has is idempotent and performs no write.</p>
 */
public interface AttachAnchor {

    /**
     * Attaches {@code anchor} to the project identified by {@code projectId}.
     *
     * @param projectId the project to extend
     * @param anchor    the anchor to attach
     * @return the project including the newly attached anchor (or unchanged, if already present)
     * @throws ProjectNotFoundException         if no project is registered under {@code
     *                                          projectId}
     * @throws AnchorAlreadyRegisteredException if {@code anchor} already belongs to a
     *                                          <em>different</em> project
     */
    Project attach(ProjectId projectId, Anchor anchor);
}
