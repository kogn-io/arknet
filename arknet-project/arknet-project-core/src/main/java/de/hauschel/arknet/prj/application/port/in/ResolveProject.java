// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;

/**
 * Driving port: resolve a client-supplied anchor to the project it belongs to.
 *
 * <p>Unknown or missing is a caller-visible error, never a silent default (ADR-016 decision 3) -
 * there is deliberately no "resolve or return empty" variant of this method, because a call site
 * that received an {@link java.util.Optional} would be tempted to invent exactly the kind of
 * fallback ADR-016 removes.</p>
 *
 * <p>This is the port later store-addressing work (issue #179) will route every request through
 * once the anchor stops being a project-management-only concept and starts gating the store
 * itself - it is already the right shape here because the MCP adapter of this component needs it
 * today: {@link AttachAnchor} and {@link RenameProject} both take a {@link
 * de.hauschel.arknet.prj.domain.ProjectId}, and an agent driving those tools addresses its own
 * project by the anchor from its call context, not by an id it has never been told.</p>
 */
public interface ResolveProject {

    /**
     * Resolves {@code anchor} to the project it is registered with.
     *
     * @param anchor the anchor to resolve
     * @return the owning project
     * @throws UnknownAnchorException if {@code anchor} is not registered with any project
     */
    Project resolve(Anchor anchor);
}
