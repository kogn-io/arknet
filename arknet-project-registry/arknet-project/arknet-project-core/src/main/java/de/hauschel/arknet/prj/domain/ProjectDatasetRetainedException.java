// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when the dataset of a project or of an unregistered dataset could not be removed - because
 * a concurrent call still held the dataset open, or because removing it failed for another reason.
 *
 * <p>Deleting a registered project runs registry first, dataset second, and the two live in
 * different datasets with no transaction spanning both. This is the one state that order can leave
 * behind: the project is no longer registered, and its model sits in the store as an unregistered
 * dataset. Deleting an unregistered dataset directly can fail the same way, with nothing else
 * changed. Either way the message says what is left and how to finish the job: call
 * {@code project_delete} with the dataset id.</p>
 *
 * <p>The store's own refusal is attached as a suppressed exception, not as the cause: the MCP
 * layer renders the deepest exception of a cause chain, and that one names the dataset only, not
 * the registry entry that is already gone.</p>
 */
public class ProjectDatasetRetainedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId datasetId;

    /**
     * Creates the exception for a project whose registry entry is already gone.
     *
     * @param project the project whose registry entry is gone but whose dataset remains
     * @param failure the store's refusal or failure to remove the dataset
     */
    public ProjectDatasetRetainedException(Project project, RuntimeException failure) {
        this(Objects.requireNonNull(project, "project").id(), project.label(), failure);
    }

    /**
     * Creates the exception for a dataset that was removed directly, without a registry entry.
     *
     * @param datasetId the dataset that remains
     * @param failure   the store's refusal or failure to remove the dataset
     */
    public ProjectDatasetRetainedException(ProjectId datasetId, RuntimeException failure) {
        this(datasetId, null, failure);
    }

    private ProjectDatasetRetainedException(ProjectId datasetId, String label, RuntimeException failure) {
        super(message(Objects.requireNonNull(datasetId, "datasetId"), label,
                Objects.requireNonNull(failure, "failure")));
        this.datasetId = datasetId;
        addSuppressed(failure);
    }

    private static String message(ProjectId datasetId, String label, RuntimeException failure) {
        String subject = label == null
                ? "The unregistered dataset '" + datasetId.value() + "'"
                : "Project '" + label + "' is no longer registered, but its dataset '" + datasetId.value() + "'";
        String shown = label == null ? "" : " project_list now shows it as an unregistered dataset.";
        return subject + " " + reason(failure) + " and was kept." + shown + " To remove it, call project_delete "
                + "with the dataset id '" + datasetId.value() + "' again.";
    }

    private static String reason(RuntimeException failure) {
        return failure instanceof DatasetInUseException
                ? "is still in use by a concurrent call"
                : "could not be removed (" + failure.getMessage() + ")";
    }

    /** @return the dataset that remains, as {@code project_delete} takes it */
    public ProjectId datasetId() {
        return datasetId;
    }
}
