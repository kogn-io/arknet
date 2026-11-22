// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.OwnProjectDeletionException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.ProjectDatasetRetainedException;
import de.hauschel.arknet.prj.domain.UnconfirmedProjectDeletionException;
import de.hauschel.arknet.prj.domain.UnknownProjectException;

/**
 * Driving port: remove a registered project completely - its anchors, its registry entry and its
 * dataset with the whole model in it - or a dataset that no project points to.
 *
 * <p>Backs the tool {@code project_delete}. Without it a project registered by mistake, or under a
 * wrong assumption, would stay registered forever.</p>
 *
 * <p><strong>Addressed by name, never by the caller's anchor.</strong> Every other writing project
 * tool acts on the project the call comes from. Deletion deliberately does not: the project is
 * named explicitly, by its id or its label, and the projects that cannot be named are the
 * caller's own ({@link OwnProjectDeletionException}) - every project any anchor of the call
 * resolves to. A call cannot delete the project it is working in by accident, because it cannot
 * delete it at all. A value that names no registered project but equals the id of an unregistered
 * dataset - exactly the ids {@code project_list} shows as adoptable - removes that dataset
 * directly; no registry entry exists for it, and none of the call's anchors can resolve to
 * it.</p>
 *
 * <p><strong>Registry first, dataset second.</strong> The two live in different datasets, and no
 * transaction spans both. Removing the registry entry first means the worst a failure in between
 * can leave behind is a dataset nobody points to - shown by {@code project_list} as an
 * unregistered dataset and removable by calling this port with its id - never a registered project
 * whose model is gone.</p>
 *
 * <p>The model's version history goes with the dataset. The registry entry is removed from the
 * system dataset, which keeps no version history; no record that the project once existed is
 * written.</p>
 */
public interface DeleteProject {

    /**
     * Deletes the registered project, or the unregistered dataset, named by {@code project}.
     *
     * <p>A registered project is looked up by id or label first; only if none matches is the value
     * compared with the ids of unregistered datasets. A dataset that holds model resources or
     * version history entries - history alone counts - is only removed when {@code confirmed}. Resources written
     * by another context between the count and the removal are not noticed: those contexts do not
     * take this port's per-project lock, so they are removed without the confirmation they would
     * have needed, and the answer reports what was found at the time of counting.</p>
     *
     * @param project       the project's opaque id or its label, or the id of an unregistered
     *                      dataset, exactly as {@code project_list} shows them
     * @param callerAnchors every anchor the call itself carries - a call can carry more than one,
     *                      e.g. a transport header and an explicit override - empty if it carries
     *                      none; only used to refuse deleting a project any of them resolves to,
     *                      never to pick the project to delete
     * @param confirmed     whether the caller explicitly accepts losing the model resources and
     *                      history entries the dataset still holds; irrelevant for one that holds none
     * @return what was deleted and what went with it
     * @throws UnknownProjectException             if neither a registered project has this id or
     *                                             label nor an unregistered dataset this id,
     *                                             including when a concurrent call deleted the
     *                                             project first
     * @throws IllegalArgumentException            if {@code project} is blank, or is the id of one
     *                                             registered project and the label of another, or
     *                                             an unregistered dataset was registered by a
     *                                             concurrent call while it was being deleted
     * @throws OwnProjectDeletionException         if any of {@code callerAnchors} belongs to the
     *                                             very project to be deleted
     * @throws UnconfirmedProjectDeletionException if the dataset still holds model resources or
     *                                             history entries and {@code confirmed} is {@code false};
     *                                             nothing is removed
     * @throws ProjectDatasetRetainedException     if the dataset was not removed - held open by a
     *                                             concurrent call or failing to be removed for any
     *                                             other reason - after the registry entry of a
     *                                             registered project was already removed
     */
    Deletion delete(String project, List<Anchor> callerAnchors, boolean confirmed);

    /**
     * What a {@link #delete} removed.
     *
     * @param datasetId        the removed dataset, which is the project's id for a registered
     *                         project
     * @param project          the project as it was registered just before its deletion, or
     *                         {@code null} if an unregistered dataset was removed - no registry
     *                         entry existed then
     * @param removedResources how many model resources of which type the dataset held when they
     *                         were counted, empty if none
     * @param removedRevisions how many entries the dataset's version history held when they were
     *                         counted
     */
    record Deletion(ProjectId datasetId, Project project, Map<String, Long> removedResources,
            long removedRevisions) {

        /** Validates and defensively copies, keeping the order of {@code removedResources}. */
        public Deletion {
            Objects.requireNonNull(datasetId, "datasetId");
            if (project != null && !project.id().equals(datasetId)) {
                throw new IllegalArgumentException("The project " + project.id().value()
                        + " is not the dataset " + datasetId.value());
            }
            removedResources = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(removedResources, "removedResources")));
        }

        /** @return the deletion of a registered project, with its registry entry */
        public static Deletion ofProject(Project project, Map<String, Long> removedResources,
                long removedRevisions) {
            Objects.requireNonNull(project, "project");
            return new Deletion(project.id(), project, removedResources, removedRevisions);
        }

        /** @return the deletion of a dataset that no registry entry pointed to */
        public static Deletion ofUnregisteredDataset(ProjectId datasetId, Map<String, Long> removedResources,
                long removedRevisions) {
            return new Deletion(datasetId, null, removedResources, removedRevisions);
        }

        /** @return whether a registered project was deleted, not an unregistered dataset */
        public boolean registered() {
            return project != null;
        }
    }
}
