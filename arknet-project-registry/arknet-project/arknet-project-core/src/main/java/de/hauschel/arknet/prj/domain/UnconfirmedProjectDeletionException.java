// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a project or an unregistered dataset that still holds model resources or revision
 * history is to be deleted without an explicit confirmation.
 *
 * <p>The other {@code *_delete} tools refuse while something still references the resource; a
 * project has no such outside reference - it is the outermost container - so the guard here is a
 * different one: the caller has to say, in a parameter of its own, that losing the model is
 * intended. The message states what would be lost, resource type by resource type plus the number
 * of revisions, before anything is removed, so that confirmation is given knowing the size of it.
 * History counts: a dataset without a single model resource still needs the confirmation.</p>
 */
public class UnconfirmedProjectDeletionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId datasetId;
    private final transient Map<String, Long> resourceCounts;
    private final long revisions;

    /**
     * Creates the exception for a registered project.
     *
     * @param project        the project that was asked to be deleted
     * @param resourceCounts how many model resources of which type the project's dataset holds,
     *                       possibly empty if only history is held
     * @param revisions      how many revisions the dataset's provenance trail holds
     */
    public UnconfirmedProjectDeletionException(Project project, Map<String, Long> resourceCounts,
            long revisions) {
        this(Objects.requireNonNull(project, "project").id(), project.label(), resourceCounts, revisions);
    }

    /**
     * Creates the exception for an unregistered dataset.
     *
     * @param datasetId      the dataset that was asked to be deleted
     * @param resourceCounts how many model resources of which type the dataset holds, possibly
     *                       empty if only history is held
     * @param revisions      how many revisions the dataset's provenance trail holds
     */
    public UnconfirmedProjectDeletionException(ProjectId datasetId, Map<String, Long> resourceCounts,
            long revisions) {
        this(datasetId, null, resourceCounts, revisions);
    }

    private UnconfirmedProjectDeletionException(ProjectId datasetId, String label,
            Map<String, Long> resourceCounts, long revisions) {
        super(message(Objects.requireNonNull(datasetId, "datasetId"), label,
                Objects.requireNonNull(resourceCounts, "resourceCounts"), revisions));
        this.datasetId = datasetId;
        this.revisions = revisions;
        this.resourceCounts = Collections.unmodifiableMap(new LinkedHashMap<>(resourceCounts));
    }

    private static String message(ProjectId datasetId, String label, Map<String, Long> resourceCounts,
            long revisions) {
        String counts = Stream.concat(
                resourceCounts.entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey()),
                revisions > 0 ? Stream.of(revisions + " revisions") : Stream.empty())
                .collect(Collectors.joining(", "));
        String subject = label == null
                ? "The unregistered dataset '" + datasetId.value() + "'"
                : "Project '" + label + "' (id: " + datasetId.value() + ")";
        return subject + " still holds content that would be lost with it: " + counts
                + ". Nothing was deleted. Call project_delete again with confirm=true to delete it together "
                + "with them.";
    }

    /** @return the dataset that was asked to be deleted */
    public ProjectId datasetId() {
        return datasetId;
    }

    /** @return how many revisions the dataset's provenance trail holds */
    public long revisions() {
        return revisions;
    }

    /** @return how many model resources of which type the project's dataset holds, in the order given */
    public Map<String, Long> resourceCounts() {
        return resourceCounts;
    }
}
