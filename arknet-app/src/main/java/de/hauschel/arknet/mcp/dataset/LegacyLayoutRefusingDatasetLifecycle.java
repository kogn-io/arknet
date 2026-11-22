// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetShutdownOutcome;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Refuses a project dataset that still holds the storage layout of an earlier arknet version.
 *
 * <p>Before the versioned write path, every component wrote a named graph of its own and every
 * write recorded a revision in a provenance graph. This version reads and writes the one content
 * graph only (ADR-63), so an old dataset would look empty to every read and a write would start a
 * second, partial model beside the old one. The first acquisition of a project dataset therefore
 * asks whether any of the {@link #LEGACY_GRAPHS} holds a triple; if one does, the handle is
 * released again and {@link LegacyDatasetLayoutException} names the dataset - nothing is read,
 * nothing is written. A dataset found clean is remembered and not asked again.</p>
 *
 * <p>The reserved system dataset of the project registry is exempt: it never held component
 * graphs, and a provenance graph left there from earlier registry writes is inert - the registry
 * reads its own graph only.</p>
 */
public final class LegacyLayoutRefusingDatasetLifecycle implements DatasetLifecycle, AutoCloseable {

    /** The named graphs of the earlier layout: one per component, plus the revision trail. */
    public static final List<String> LEGACY_GRAPHS = List.of(
            "https://w3id.org/arknet/model/requirements",
            "https://w3id.org/arknet/model/constraints",
            "https://w3id.org/arknet/model/use-cases",
            "https://w3id.org/arknet/model/ubiquitous-language",
            "https://w3id.org/arknet/model/bounded-context",
            "https://w3id.org/arknet/model/adr",
            "https://w3id.org/arknet/model/actors",
            "https://w3id.org/arknet/model/roles",
            "https://w3id.org/arknet/model/provenance");

    private static final String LEGACY_ASK = "ASK { VALUES ?g { "
            + LEGACY_GRAPHS.stream().map(graph -> "<" + graph + ">").collect(Collectors.joining(" "))
            + " } GRAPH ?g { ?s ?p ?o } }";

    private static final DatasetId SYSTEM_DATASET = new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET);

    private final DatasetLifecycle delegate;
    private final Set<DatasetId> verified = ConcurrentHashMap.newKeySet();

    /**
     * @param delegate the lifecycle every call is forwarded to
     */
    public LegacyLayoutRefusingDatasetLifecycle(DatasetLifecycle delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public DatasetHandle acquire(DatasetId id) {
        Objects.requireNonNull(id, "id");
        DatasetHandle handle = delegate.acquire(id);
        if (SYSTEM_DATASET.equals(id) || verified.contains(id)) {
            return handle;
        }
        boolean legacy;
        try {
            legacy = handle.sparqlQuery().ask(LEGACY_ASK);
        } catch (RuntimeException e) {
            handle.close();
            throw e;
        }
        if (legacy) {
            handle.close();
            throw new LegacyDatasetLayoutException(message(id));
        }
        verified.add(id);
        return handle;
    }

    @Override
    public DatasetShutdownOutcome shutDown(DatasetId id) {
        return delegate.shutDown(id);
    }

    @Override
    public void shutDownAll() {
        delegate.shutDownAll();
    }

    @Override
    public void delete(DatasetId id) {
        verified.remove(id);
        delegate.delete(id);
    }

    @Override
    public Set<DatasetId> list() {
        return delegate.list();
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private static String message(DatasetId id) {
        return "The project dataset '" + id.value() + "' still uses the storage layout of an earlier arknet"
                + " version: one named graph per component and a provenance graph. This version keeps every"
                + " model resource of a project in one versioned content graph and refuses to read or write the"
                + " old layout, so the project is neither shown as empty nor overwritten. The dataset has to be"
                + " migrated to the new layout before this version can use it; keep a copy of the storage"
                + " directory until it is.";
    }
}
