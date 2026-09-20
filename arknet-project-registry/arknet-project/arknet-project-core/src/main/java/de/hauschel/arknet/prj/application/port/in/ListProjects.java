// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import java.util.List;

import de.hauschel.arknet.prj.domain.Project;

/**
 * Driving port: list every registered project.
 *
 * <p>Backs the tool {@code project_list}. This is also the query a surface without a client
 * working directory of its own - a web UI - falls back to when it has no anchor context to
 * resolve against.</p>
 */
public interface ListProjects {

    /**
     * Returns all registered projects.
     *
     * @return all projects, never {@code null}
     */
    List<Project> list();
}
