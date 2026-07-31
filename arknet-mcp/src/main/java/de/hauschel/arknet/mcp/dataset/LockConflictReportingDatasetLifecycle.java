// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

/**
 * Decorates a {@link DatasetLifecycle}, translating an {@link #acquire(DatasetId)} failure that
 * the injected {@code isLockConflict} predicate recognises as a file-lock conflict into a
 * {@link DatasetLockConflictException} carrying a clear, actionable message instead of letting the
 * wrapped lifecycle's raw failure surface (issue #64). Every other {@code acquire} failure - a
 * permissions problem, a full disk, a corrupted store - is rethrown unchanged.
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
 * <p><strong>Why an injected predicate, not a narrower RDF4J catch.</strong> The composition root
 * deliberately stays free of any direct RDF4J dependency (see
 * {@code KognioRdfRequirementRepositoryFactory#persistentLifecycle}) so that swapping the RDF4J
 * backend for another {@link DatasetLifecycle} implementation never touches arknet-mcp. Catching
 * {@code org.eclipse.rdf4j.repository.RepositoryLockedException} by name here would reintroduce
 * exactly that dependency. Instead {@code isLockConflict} is a constructor parameter -
 * {@code KognioRdfRequirementRepositoryFactory} supplies {@code DEFAULT_LOCK_CONFLICT}, the one
 * place RDF4J is allowed to be named - and an {@code acquire} failure the predicate does not
 * recognise is rethrown as-is. That matters beyond keeping the dependency out: a permissions,
 * disk-space or store-corruption failure must never be misdiagnosed as a lock conflict just
 * because it, too, is a {@link RuntimeException} out of {@code acquire}.</p>
 */
public final class LockConflictReportingDatasetLifecycle implements DatasetLifecycle {

    private final DatasetLifecycle delegate;
    private final Path storageDir;
    private final Predicate<RuntimeException> isLockConflict;

    /**
     * @param delegate       the wrapped lifecycle every call except a failing {@link #acquire} is
     *                       forwarded to unchanged
     * @param storageDir     the storage directory {@code delegate} was constructed over, named in
     *                       the translated message so the operator knows which directory is
     *                       contended
     * @param isLockConflict recognises an {@link #acquire(DatasetId)} failure as a file-lock
     *                       conflict rather than some other cause; an unrecognised failure is
     *                       rethrown unchanged
     */
    public LockConflictReportingDatasetLifecycle(
            DatasetLifecycle delegate, Path storageDir, Predicate<RuntimeException> isLockConflict) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir");
        this.isLockConflict = Objects.requireNonNull(isLockConflict, "isLockConflict");
    }

    @Override
    public DatasetHandle acquire(DatasetId id) {
        Objects.requireNonNull(id, "id");
        try {
            return delegate.acquire(id);
        } catch (RuntimeException e) {
            if (isLockConflict.test(e)) {
                throw new DatasetLockConflictException(lockConflictMessage(id, e), e);
            }
            throw e;
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
                + cause.getMessage() + ". Another process already holds this directory's file lock for this "
                + "dataset/project - e.g. a second arknet daemon instance, or a client/subagent MCP config that "
                + "spawns its own local server (stdio) instead of pointing at the one running daemon's HTTP "
                + "endpoint. Stop the other process, or point every client at the shared daemon instead.";
    }
}
