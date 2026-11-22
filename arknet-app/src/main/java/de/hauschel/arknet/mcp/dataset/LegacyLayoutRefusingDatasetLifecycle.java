// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.kogn.rdf.dataset.SparqlQuery;
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
 * <p>{@link LegacyLayoutMigration} moves every such dataset to the content graph at daemon start,
 * before this lifecycle hands out a handle; a dataset still in the old layout here is one whose
 * migration failed and was rolled back, and the refusal points to the daemon log that names why.</p>
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
            legacy = holdsLegacyLayout(handle.sparqlQuery());
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

    /**
     * Tells whether a dataset still holds a triple in one of the {@link #LEGACY_GRAPHS}.
     *
     * @param query the dataset's query port
     * @return {@code true} for a dataset in the earlier layout
     */
    static boolean holdsLegacyLayout(SparqlQuery query) {
        return query.ask(LEGACY_ASK);
    }

    private static String message(DatasetId id) {
        return "The project dataset '" + id.value() + "' still uses the storage layout of an earlier arknet"
                + " version: one named graph per component and a provenance graph. The daemon migrates that layout"
                + " to the one versioned content graph at startup, but the migration of this dataset failed and was"
                + " rolled back, so the old layout is unchanged; this version refuses to read or write it, so the"
                + " project is neither shown as empty nor overwritten. The daemon log names the reason under"
                + " 'legacy layout migration'. Keep a copy of the storage directory, fix the named cause or keep"
                + " running the arknet version that wrote it, and restart the daemon to retry the migration.";
    }
}
