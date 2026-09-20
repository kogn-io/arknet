// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.out;

import java.util.Set;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: which datasets physically exist in the store, independent of what the registry
 * knows about them.
 *
 * <p><strong>Why the registry alone is not enough.</strong> Every other question this component
 * answers is answered from {@link ProjectRegistry}, which by construction only knows projects
 * somebody registered. Adoption is the one operation about data the registry has never heard of:
 * datasets written before the registered-anchor model, when a project's identity was derived from a directory name
 * rather than registered. Those datasets are real, hold a project's whole model, and are reachable
 * by nothing - the registry cannot list them because they were never in it, which is precisely why
 * this second, deliberately minimal port exists.</p>
 *
 * <p>It is read-only and returns identities, not handles: adoption needs to know that a dataset is
 * there and not yet spoken for, never to read or write it. Keeping the port at that width means an
 * implementation cannot quietly become a second way into other projects' data.</p>
 */
public interface DatasetInventory {

    /**
     * Returns the identity of every dataset present in the store.
     *
     * <p>Implementations exclude the reserved system dataset (see
     * {@link ProjectId#RESERVED_SYSTEM_DATASET}); it holds the registry itself, is not a project,
     * and {@link ProjectId} rejects its value outright, so it could not be represented here even
     * if a caller wanted to adopt it.</p>
     *
     * @return every present dataset's identity, never {@code null}
     */
    Set<ProjectId> existingDatasets();
}
