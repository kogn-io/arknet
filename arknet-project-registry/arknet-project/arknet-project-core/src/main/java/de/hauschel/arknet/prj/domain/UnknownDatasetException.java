// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when adoption names a dataset the store does not hold.
 *
 * <p>Adoption exists to attach an anchor to data that is already there; naming a dataset that is
 * not would create a registry entry pointing at nothing, and the caller would then write into a
 * freshly created empty dataset believing it had recovered its model. Rejecting the name outright
 * keeps that from looking like success. The message names {@code project_add} because a caller who
 * has no existing dataset does not want adoption at all - it wants a new project.</p>
 */
public class UnknownDatasetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId datasetId;

    /**
     * Creates the exception.
     *
     * @param datasetId the dataset identity that is not present in the store
     */
    public UnknownDatasetException(ProjectId datasetId) {
        super("No dataset '" + Objects.requireNonNull(datasetId, "datasetId").value()
                + "' exists in the store, so there is nothing to adopt. project_list shows which "
                + "datasets are available for adoption. To start a new, empty project instead, use "
                + "project_add.");
        this.datasetId = datasetId;
    }

    /** @return the dataset identity that is not present in the store */
    public ProjectId datasetId() {
        return datasetId;
    }
}
