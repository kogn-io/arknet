// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.OwnProjectDeletionException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.ProjectDatasetRetainedException;
import de.hauschel.arknet.prj.domain.UnconfirmedProjectDeletionException;
import de.hauschel.arknet.prj.domain.UnknownProjectException;

/**
 * Driving port: remove a registered project completely - its anchors, its registry entry and its
 * dataset with the whole model in it.
 *
 * <p>Backs the tool {@code project_delete}. Without it a project registered by mistake, or under a
 * wrong assumption, would stay registered forever.</p>
 *
 * <p><strong>Addressed by name, never by the caller's anchor.</strong> Every other writing project
 * tool acts on the project the call comes from. Deletion deliberately does not: the project is
 * named explicitly, by its id or its label, and the projects that cannot be named are the
 * caller's own ({@link OwnProjectDeletionException}) - every project any anchor of the call
 * resolves to. A call cannot delete the project it is
 * working in by accident, because it cannot delete it at all.</p>
 *
 * <p><strong>Registry first, dataset second.</strong> The two live in different datasets, and no
 * transaction spans both. Removing the registry entry first means the worst a failure in between
 * can leave behind is a dataset nobody points to - shown by {@code project_list} as adoptable and
 * recoverable with {@code project_adopt} - never a registered project whose model is gone.</p>
 *
 * <p>The model's revision trail goes with the dataset. The registry entry's own revisions in the
 * system dataset are tombstoned the way every delete tombstones its resource's last revision;
 * beyond that, no record that the project once existed is written.</p>
 */
public interface DeleteProject {

    /**
     * Deletes the project named by {@code project}.
     *
     * @param project      the project's opaque id or its label, exactly as {@code project_list}
     *                     shows them
     * @param callerAnchors every anchor the call itself carries - a call can carry more than one,
     *                      e.g. a transport header and an explicit override - empty if it carries
     *                      none; only used to refuse deleting a project any of them resolves to,
     *                      never to pick the project to delete
     * @param confirmed    whether the caller explicitly accepts losing the model resources the
     *                     project still holds; irrelevant for a project that holds none
     * @return the deleted project and what was removed with it
     * @throws UnknownProjectException             if no registered project has this id or label,
     *                                             including when a concurrent call deleted it first
     * @throws IllegalArgumentException            if {@code project} is blank, or is the id of one
     *                                             registered project and the label of another
     * @throws OwnProjectDeletionException         if any of {@code callerAnchors} belongs to the
     *                                             very project to be deleted
     * @throws UnconfirmedProjectDeletionException if the project still holds model resources and
     *                                             {@code confirmed} is {@code false}; nothing is
     *                                             removed
     * @throws ProjectDatasetRetainedException     if the registry entry was removed but the
     *                                             dataset was not - held open by a concurrent call
     *                                             or failing to be removed for any other reason
     */
    Deletion delete(String project, List<Anchor> callerAnchors, boolean confirmed);

    /**
     * What a {@link #delete} removed.
     *
     * @param project          the project as it was registered just before its deletion
     * @param removedResources how many model resources of which type went with it, empty if none
     */
    record Deletion(Project project, Map<String, Long> removedResources) {

        /** Validates and defensively copies, keeping the order of {@code removedResources}. */
        public Deletion {
            Objects.requireNonNull(project, "project");
            removedResources = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(removedResources, "removedResources")));
        }
    }
}
