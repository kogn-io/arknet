// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application;

import java.util.LinkedHashSet;
import java.util.Set;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.application.port.out.DatasetInventory;

/**
 * In-memory test double for {@link DatasetInventory}: a mutable set of dataset identities standing
 * in for what the store physically holds.
 *
 * <p>A hand-rolled fake rather than a mock, like {@link InMemoryProjectRegistry}, and deliberately
 * <em>independent</em> of it. That independence is the whole point of the port: adoption is about
 * datasets the registry has never heard of, so a test double that derived its answer from the
 * registry could not express the only situation adoption exists for.</p>
 */
final class InMemoryDatasetInventory implements DatasetInventory {

    private final Set<ProjectId> present = new LinkedHashSet<>();

    /**
     * Declares that a dataset with this identity exists in the store.
     *
     * @param id the dataset identity to add
     * @return this inventory, for chaining in a test's arrange step
     */
    InMemoryDatasetInventory with(ProjectId id) {
        present.add(id);
        return this;
    }

    @Override
    public Set<ProjectId> existingDatasets() {
        return Set.copyOf(present);
    }
}
