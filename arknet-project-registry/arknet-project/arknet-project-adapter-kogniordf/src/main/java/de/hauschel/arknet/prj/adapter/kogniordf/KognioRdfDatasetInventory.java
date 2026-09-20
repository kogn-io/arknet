// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.application.port.out.DatasetInventory;

/**
 * kognio-rdf-backed {@link DatasetInventory}: lists the datasets the store physically holds.
 *
 * <p>The whole implementation is {@link DatasetLifecycle#list()} plus a filter, and that is the
 * point - the port exists to answer one question about existence, not to become a second way into
 * other projects' data. No dataset is acquired, opened or read here.</p>
 *
 * <p>The reserved system dataset is filtered out before any {@link ProjectId} is constructed, not
 * after. It has to be that way round: {@link ProjectId} rejects that value in its constructor,
 * so mapping first and filtering second would throw on every single call
 * rather than skip one entry.</p>
 */
public final class KognioRdfDatasetInventory implements DatasetInventory {

    private final DatasetLifecycle lifecycle;

    /**
     * @param lifecycle the shared dataset lifecycle whose store is inventoried
     */
    public KognioRdfDatasetInventory(final DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public Set<ProjectId> existingDatasets() {
        return lifecycle.list().stream()
                .map(DatasetId::value)
                .filter(value -> !ProjectId.RESERVED_SYSTEM_DATASET.equals(value))
                .map(ProjectId::new)
                .collect(Collectors.toUnmodifiableSet());
    }
}
