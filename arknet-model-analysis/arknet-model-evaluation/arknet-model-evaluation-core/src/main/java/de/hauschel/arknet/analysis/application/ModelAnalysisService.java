// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.application;

import java.util.Objects;

import de.hauschel.arknet.analysis.application.port.in.ReadModelSnapshot;
import de.hauschel.arknet.analysis.application.port.in.ReadTraceabilityGraph;
import de.hauschel.arknet.analysis.application.port.in.ResolveResourceHandle;
import de.hauschel.arknet.analysis.application.port.out.ModelSnapshots;
import de.hauschel.arknet.analysis.application.port.out.ResourceHandleLookup;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.StoreSnapshot;

/**
 * The application service of the model-analysis hexagon: turns a snapshot of the published
 * language into the read models its driving adapters render.
 *
 * <p>It carries no state and no schedule of its own. Every call reads the store once through
 * {@link ModelSnapshots} and builds the answer from that read, because an analysis that cached
 * would answer about a model that has since been written.</p>
 */
public final class ModelAnalysisService
        implements ReadModelSnapshot, ReadTraceabilityGraph, ResolveResourceHandle {

    private final ModelSnapshots snapshots;
    private final ResourceHandleLookup handles;

    /**
     * @param snapshots the read of the published language of every context
     * @param handles   resolves a caller's handle to the IRI the model carries
     */
    public ModelAnalysisService(final ModelSnapshots snapshots, final ResourceHandleLookup handles) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.handles = Objects.requireNonNull(handles, "handles");
    }

    @Override
    public StoreSnapshot read(final ProjectId projectId) {
        return snapshots.read(projectId);
    }

    @Override
    public TraceabilityGraph read(final ProjectId projectId, final DisplayLocale locale) {
        return TraceabilityGraph.of(snapshots.read(projectId), locale);
    }

    @Override
    public String resolve(final ProjectId projectId, final String handle) {
        return handles.resolve(projectId, handle);
    }
}
