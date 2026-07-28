// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when adoption names a dataset that some project is already registered for.
 *
 * <p>Adoption is a one-time claim, not a way to take over. Letting a second caller adopt the same
 * dataset would silently give it a second label and a second set of anchors - two projects, one
 * body of data, which is the very state ADR-016 exists to make impossible. A caller that legitimately
 * works on this project from another place wants {@code project_attach_anchor} instead, which adds
 * an anchor to the existing registration rather than creating a rival one.</p>
 */
public class DatasetAlreadyAdoptedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId datasetId;

    /**
     * Creates the exception.
     *
     * @param datasetId the dataset that is already registered to a project
     * @param label     the label of the project already holding it
     */
    public DatasetAlreadyAdoptedException(ProjectId datasetId, String label) {
        super("Dataset '" + Objects.requireNonNull(datasetId, "datasetId").value()
                + "' is already registered as project '" + Objects.requireNonNull(label, "label")
                + "', so it cannot be adopted again. If you work on that project from this location "
                + "too, attach your anchor to it with project_attach_anchor instead.");
        this.datasetId = datasetId;
    }

    /** @return the dataset that is already registered to a project */
    public ProjectId datasetId() {
        return datasetId;
    }
}
