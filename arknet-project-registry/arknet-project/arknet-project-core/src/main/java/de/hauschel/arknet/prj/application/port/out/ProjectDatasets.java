// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.out;

import java.io.OutputStream;
import java.util.Map;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.DatasetInUseException;

/**
 * Driven port: what a project's own dataset holds, its removal and its backup - what deleting and
 * exporting a project need to know and do beyond the registry.
 *
 * <p><strong>Why not {@link DatasetInventory}.</strong> That port is deliberately read-only and
 * returns identities only, so it can never become a second way into other projects' data. Deleting
 * a project needs both more and something else: a look at what the dataset contains, so the
 * caller can be told what would be lost, and the destructive removal itself. Folding either into
 * the inventory would widen exactly the port that promises to stay narrow, so the capability gets
 * a port of its own, consulted by one use case only.</p>
 *
 * <p>Counting and deleting never create a dataset that is not there. That matters because the
 * store's own way into a dataset creates it on first access: a count or a delete against a project
 * that never wrote anything must leave the store as it found it, not with an empty dataset that
 * then shows up as adoptable. {@link #export} reads the dataset like any other read; it is only
 * asked for registered projects, whose dataset registration created with the
 * self-description.</p>
 */
public interface ProjectDatasets {

    /**
     * Counts the model resources in a project's dataset, by resource type.
     *
     * <p>A model resource is anything the bounded contexts wrote into the project - requirements,
     * use cases, terms, decisions and their typed parts. The project's own infrastructure is not:
     * its self-description and its version history go with the dataset, but nobody wrote them as
     * part of the model. The history is counted separately, by {@link #historyEntryCount}.</p>
     *
     * @param projectId the project whose dataset is inspected
     * @return the number of resources per type, keyed by a short, human-readable type name and
     *         ordered by it; empty if the dataset holds no model resource or does not exist
     */
    Map<String, Long> modelResourceCounts(ProjectId projectId);

    /**
     * Counts the entries in a project's version history - publishes and tombstones. Every write that
     * changes a resource leaves one, so a dataset without model resources can still hold history that
     * a deletion would destroy.
     *
     * @param projectId the project whose dataset is inspected
     * @return the number of history entries, {@code 0} if the dataset holds none or does not exist
     */
    long historyEntryCount(ProjectId projectId);

    /**
     * Removes a project's dataset with everything in it, including its on-disk storage. A dataset
     * that does not exist is not an error - there is nothing to remove.
     *
     * @param projectId the project whose dataset is removed
     * @throws DatasetInUseException if a concurrent call still holds the dataset open after a
     *                               short wait; the dataset is then left untouched
     */
    void delete(ProjectId projectId);

    /**
     * Writes every statement of every graph in a project's dataset to {@code out} as TriG text -
     * the version history and the self-description included, since a backup exists to restore
     * them too - followed by an export-metadata graph that is output only and never stored.
     *
     * @param projectId the project whose dataset is backed up
     * @param out       the stream the TriG text is written to; not closed by this method
     */
    void export(ProjectId projectId, OutputStream out);
}
