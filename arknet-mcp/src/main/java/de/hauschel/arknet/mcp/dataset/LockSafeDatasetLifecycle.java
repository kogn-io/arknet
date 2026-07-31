// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

/**
 * Decorates a {@link DatasetLifecycle}, translating an {@link #acquire(DatasetId)} failure that
 * looks like a file-lock conflict into a {@link DatasetLockConflictException} carrying a clear,
 * actionable message instead of letting the wrapped lifecycle's raw failure surface (issue #64).
 *
 * <p><strong>Why this exists.</strong> arknet's shared {@link DatasetLifecycle} bean is meant to
 * be the <em>only</em> one opening its storage directory; every consumer acquires datasets through
 * it (see {@code ArknetMcpConfiguration#datasetLifecycle}). That invariant can still be broken from
 * outside this process - someone starts a second daemon instance against the same
 * {@code arknet.rdf.storage} directory, or hand-writes a {@code stdio}-type MCP entry that spawns
 * arknet as a local subprocess instead of pointing at the one running daemon's HTTP endpoint. When
 * that happens, the RDF4J-backed {@code DatasetLifecycle} implementation's {@code acquire} fails
 * with RDF4J's own {@code RepositoryLockedException} as soon as it touches the same
 * {@link DatasetId} - a message that names an RDF4J internal, not the arknet-level cause. This
 * decorator is the one place that failure is translated, right where the shared lifecycle bean is
 * built.</p>
 *
 * <p><strong>Why {@link RuntimeException}, not a narrower RDF4J type.</strong> The composition
 * root deliberately stays free of any direct RDF4J dependency (see
 * {@code KognioRdfRequirementRepositoryFactory#persistentLifecycle}) so that swapping the RDF4J
 * backend for another {@link DatasetLifecycle} implementation never touches arknet-mcp. Catching
 * {@code org.eclipse.rdf4j.repository.RepositoryLockedException} by name here would reintroduce
 * exactly that dependency for the sake of a narrower catch. {@link #acquire(DatasetId)} has no
 * other plausible failure mode once {@code id} is non-null (see {@link DatasetLifecycle#acquire}),
 * so a broad catch does not risk mislabelling an unrelated failure as a lock conflict.</p>
 */
public final class LockSafeDatasetLifecycle implements DatasetLifecycle {

    private final DatasetLifecycle delegate;
    private final Path storageDir;

    /**
     * @param delegate   the wrapped lifecycle every call except a failing {@link #acquire} is
     *                   forwarded to unchanged
     * @param storageDir the storage directory {@code delegate} was constructed over, named in the
     *                   translated message so the operator knows which directory is contended
     */
    public LockSafeDatasetLifecycle(DatasetLifecycle delegate, Path storageDir) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir");
    }

    @Override
    public DatasetHandle acquire(DatasetId id) {
        Objects.requireNonNull(id, "id");
        try {
            return delegate.acquire(id);
        } catch (RuntimeException e) {
            throw new DatasetLockConflictException(lockConflictMessage(id, e), e);
        }
    }

    @Override
    public void close(DatasetId id) {
        delegate.close(id);
    }

    @Override
    public void delete(DatasetId id) {
        delegate.delete(id);
    }

    @Override
    public Set<DatasetId> list() {
        return delegate.list();
    }

    private String lockConflictMessage(DatasetId id, RuntimeException cause) {
        return "Failed to open the arknet RDF store at " + storageDir + " for project '" + id.value() + "': "
                + cause.getMessage() + ". This usually means another process already holds this directory's "
                + "file lock - e.g. a second arknet daemon instance, or a client/subagent MCP config that "
                + "spawns its own local server (stdio) instead of pointing at the one running daemon's HTTP "
                + "endpoint. Stop the other process, or point every client at the shared daemon instead.";
    }
}
