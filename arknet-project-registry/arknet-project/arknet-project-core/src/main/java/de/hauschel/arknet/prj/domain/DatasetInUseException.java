// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown by a {@link de.hauschel.arknet.prj.application.port.out.ProjectDatasets} implementation
 * when a project's dataset cannot be removed because a concurrent call still holds it open.
 *
 * <p>A store that tore down a dataset under an in-flight read or write would corrupt that call
 * rather than refuse it, so the store refuses the removal instead. The signal is technology
 * neutral on purpose: the core must not know which store raised it, only that the dataset is
 * still there.</p>
 */
public class DatasetInUseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId datasetId;

    /**
     * Creates the exception.
     *
     * @param datasetId the dataset that is still held open
     * @param cause     the store's own refusal
     */
    public DatasetInUseException(ProjectId datasetId, Throwable cause) {
        super("Dataset '" + Objects.requireNonNull(datasetId, "datasetId").value()
                + "' is still in use by a concurrent call and was not removed.", cause);
        this.datasetId = datasetId;
    }

    /** @return the dataset that is still held open */
    public ProjectId datasetId() {
        return datasetId;
    }
}
