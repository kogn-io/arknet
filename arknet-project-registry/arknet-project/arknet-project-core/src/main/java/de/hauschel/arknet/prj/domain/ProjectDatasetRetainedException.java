// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Objects;

/**
 * Thrown when a project's registry entry was removed but its dataset could not be, because a
 * concurrent call still held the dataset open.
 *
 * <p>Deletion runs registry first, dataset second, and the two live in different datasets with no
 * transaction spanning both. This is the one state that order can leave behind: the project is no
 * longer registered, and its model sits in the store as an unregistered dataset. The message says
 * exactly that and how to finish the job - adopt the dataset under a project again and delete that
 * one - because the dataset is otherwise reachable by no tool.</p>
 *
 * <p>The store's own refusal is attached as a suppressed exception, not as the cause: the MCP
 * layer renders the deepest exception of a cause chain, and that one names the dataset only, not
 * the registry entry that is already gone.</p>
 */
public class ProjectDatasetRetainedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Project project;

    /**
     * Creates the exception.
     *
     * @param project the project whose registry entry is gone but whose dataset remains
     * @param inUse   the store's refusal to remove the dataset
     */
    public ProjectDatasetRetainedException(Project project, DatasetInUseException inUse) {
        super("Project '" + Objects.requireNonNull(project, "project").label() + "' is no longer registered, "
                + "but its dataset '" + project.id().value() + "' is still in use by a concurrent call and was "
                + "kept. project_list now shows it as an unregistered dataset. To remove it, adopt it with "
                + "project_adopt and call project_delete on that project again.");
        this.project = project;
        addSuppressed(Objects.requireNonNull(inUse, "inUse"));
    }

    /** @return the project whose registry entry is gone but whose dataset remains */
    public Project project() {
        return project;
    }
}
