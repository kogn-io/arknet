// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.nio.file.Path;
import java.util.List;

import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

/**
 * The backup a migration takes of a project dataset before it changes anything in it.
 *
 * <p>A migration calls {@link #backUp} once per dataset, after it found a step due and before the
 * first step runs, and migrates the dataset only if the call returned. A backup that throws leaves
 * the dataset unmigrated: the caller reports the failure and the dataset stays in its earlier form.
 * A dataset with no step due is never backed up.</p>
 *
 * <p>The implementation decides the form of the backup and whether the dataset has to be closed
 * for it; the caller holds no lease on the dataset while it calls.</p>
 */
public interface MigrationBackup {

    /**
     * Backs up one dataset.
     *
     * @param lifecycle the lifecycle the dataset is open in; the caller holds no lease on it
     * @param id        the dataset
     * @param dueSteps  the migration steps due for the dataset, recorded with the backup
     * @return where the backup lies
     * @throws MigrationBackupException if no complete backup could be written
     */
    Copy backUp(DatasetLifecycle lifecycle, DatasetId id, List<String> dueSteps);

    /**
     * Removes what an interrupted earlier backup left behind. Called once before the first
     * {@link #backUp} of a run; a complete backup is never removed.
     */
    void discardIncomplete();

    /**
     * A finished backup.
     *
     * @param directory the directory holding the backup
     * @param bytes     the size of the backed-up data in bytes
     */
    record Copy(Path directory, long bytes) {
    }
}
