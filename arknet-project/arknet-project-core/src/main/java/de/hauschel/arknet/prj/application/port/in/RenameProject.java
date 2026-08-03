// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;
import de.hauschel.arknet.prj.domain.StaleProjectException;

/**
 * Driving port: change a project's human-readable label.
 *
 * <p>Backs the tool {@code project_rename}. Renaming never touches the project's identity or its
 * anchors (ADR-016 decision 5: identity and label are deliberately separate) - it only replaces
 * the label a human reads and types. Renaming to the label the project already has is idempotent
 * and performs no write.</p>
 */
public interface RenameProject {

    /**
     * Renames the project identified by {@code projectId}.
     *
     * @param projectId the project to rename
     * @param newLabel  the new label
     * @return the project with the new label (or unchanged, if {@code newLabel} equals the
     *         current one)
     * @throws ProjectNotFoundException       if no project is registered under {@code projectId}
     * @throws DuplicateProjectLabelException if {@code newLabel} already labels a different
     *                                        project
     * @throws StaleProjectException          if the underlying read-modify-write keeps losing
     *                                        the compare-and-set race against a concurrent writer
     *                                        across every retry attempt
     */
    Project rename(ProjectId projectId, String newLabel);
}
