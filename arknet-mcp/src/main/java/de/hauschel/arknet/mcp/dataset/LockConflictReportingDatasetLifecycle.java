// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

/**
 * Decorates a {@link DatasetLifecycle}, translating an {@link #acquire(DatasetId)} failure that
 * the injected {@code isLockConflict} predicate recognises as a file-lock conflict into a
 * {@link DatasetLockConflictException} carrying a clear, actionable message instead of letting the
 * wrapped lifecycle's raw failure surface. Every other {@code acquire} failure - a
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
 *
 * <p><strong>Why {@link AutoCloseable} (issue #140).</strong> Neither {@link DatasetLifecycle} nor
 * the RDF4J-backed implementation it wraps expose a no-arg shutdown - only {@code close(DatasetId)}
 * per dataset. Without one, stopping the daemon (a {@code systemctl stop}, a container stop) tears
 * down the Spring context without ever telling the underlying store to release its datasets in an
 * orderly way, leaving it to crash recovery on the next start instead. {@link #close()} closes
 * every dataset {@link #list()} reports through the same neutral {@code close(DatasetId)} the
 * interface already declares - no RDF4J-specific bulk-shutdown method is called, so this stays free
 * of the dependency {@link #acquire} already avoids. A failure closing one dataset is logged and
 * does not abort the loop - the remaining datasets are still worth an orderly close even if one of
 * them fails (issue #302); {@code close(DatasetId)}'s own contract does not promise to be
 * exception-free. {@code ArknetMcpConfiguration#datasetLifecycle} registers this as the bean's
 * destroy method, so it runs once, automatically, when the context closes. This is best-effort, not
 * a guarantee: {@code close(DatasetId)} is a silent no-op while a concurrently in-flight
 * {@link #acquire} still holds an open {@link DatasetHandle} lease on that dataset, and
 * {@link #close()} does not wait for such a lease to be released - a dataset caught that way still
 * falls back to crash recovery, merely logged rather than left unnoticed.</p>
 *
 * <p><strong>How "still open" is actually detected (issue #294).</strong> {@link #list()} names
 * every dataset the delegate <em>knows</em> - per its own contract "both currently open and those
 * persisted but not currently held in memory" - so it says nothing about which of them still have
 * an in-flight lease after the closing loop above: a dataset that closed cleanly is just as much a
 * member of {@code list()} as one that did not, because closing never deletes its on-disk storage.
 * Comparing {@code list()} before and after the loop can therefore never tell the two apart. This
 * class tracks its own open leases instead: {@link #acquire} wraps the returned
 * {@link DatasetHandle} so that closing it decrements a per-{@link DatasetId} counter, and
 * {@link #close()} warns about exactly the ids whose counter is still above zero once the loop has
 * run. Since this decorator's whole reason to exist is being the <em>only</em> lifecycle any
 * consumer acquires a dataset through (see above), a lease this instance has not itself issued
 * cannot exist - the tracked count is a complete account of what is actually still open, not an
 * approximation of it.</p>
 */
public final class LockConflictReportingDatasetLifecycle implements DatasetLifecycle, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LockConflictReportingDatasetLifecycle.class);

    private final DatasetLifecycle delegate;
    private final Path storageDir;
    private final Predicate<RuntimeException> isLockConflict;

    /**
     * Counts this instance's own open leases per {@link DatasetId} - incremented in {@link
     * #acquire}, decremented when the {@link DatasetHandle} {@link #acquire} returned is closed.
     * See the class javadoc ("How 'still open' is actually detected") for why this, and not
     * {@link #list()}, is what {@link #close()} checks.
     */
    private final Map<DatasetId, LongAdder> openLeases = new ConcurrentHashMap<>();

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
            DatasetHandle handle = delegate.acquire(id);
            openLeases.computeIfAbsent(id, key -> new LongAdder()).increment();
            return new LeaseTrackingHandle(id, handle);
        } catch (RuntimeException e) {
            if (isLockConflict.test(e)) {
                // Not chained as this exception's cause: Spring AI's MCP tool callback renders the
                // deepest exception in the getCause() chain, not this one - chaining e here would
                // let RDF4J's raw lock exception win over the composed message naming the actual
                // cause and remedy (#137). Kept as a suppressed exception instead, so the original
                // is still on the stack trace without being able to win the walk.
                DatasetLockConflictException translated = new DatasetLockConflictException(lockConflictMessage(id, e));
                translated.addSuppressed(e);
                throw translated;
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

    /**
     * Closes every dataset {@link #list()} currently reports, so a daemon shutdown releases them
     * in an orderly way instead of relying on crash recovery. Safe to call for a dataset this
     * instance never {@link #acquire}d - {@code close(DatasetId)} is a no-op for one the delegate
     * does not have open.
     *
     * <p>A failure closing one dataset is logged and does not abort the loop (issue #302): the
     * delegate's {@code close(DatasetId)} does not promise to be exception-free, and the remaining
     * datasets are still worth an orderly close even if one of them throws.</p>
     *
     * <p>Best-effort: does not wait for an in-flight {@link #acquire} call elsewhere to release its
     * lease. A dataset still open after the loop below - most likely because such a lease was still
     * held at shutdown time - is only logged, not retried, since retrying/waiting here risks
     * hanging the daemon's shutdown. "Still open" is this instance's own tracked lease count, not
     * another {@link #list()} call - see the class javadoc for why {@link #list()} cannot answer
     * that question (issue #294).</p>
     */
    @Override
    public void close() {
        for (DatasetId id : delegate.list()) {
            try {
                delegate.close(id);
            } catch (RuntimeException e) {
                LOG.warn("close(): failed to close dataset '{}' during shutdown, continuing with the "
                        + "remaining datasets: {}", id, e.toString(), e);
            }
        }
        final Set<DatasetId> stillOpen = openLeaseIds();
        if (!stillOpen.isEmpty()) {
            LOG.warn(
                    "close(): {} dataset(s) still open after the shutdown attempt, most likely because "
                            + "an in-flight request held an open lease at shutdown time: {}. These will fall "
                            + "back to crash recovery on the next start instead of an orderly close.",
                    stillOpen.size(), stillOpen);
        }
    }

    /** The ids currently carrying at least one open lease issued through {@link #acquire}. */
    private Set<DatasetId> openLeaseIds() {
        return openLeases.entrySet().stream()
                .filter(entry -> entry.getValue().sum() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String lockConflictMessage(DatasetId id, RuntimeException cause) {
        return "Failed to open the arknet RDF store at " + storageDir + " for project '" + id.value() + "': "
                + cause.getMessage() + ". Another process already holds this directory's file lock for this "
                + "dataset/project - e.g. a second arknet daemon instance, or a client/subagent MCP config that "
                + "spawns its own local server (stdio) instead of pointing at the one running daemon's HTTP "
                + "endpoint. Stop the other process, or point every client at the shared daemon instead.";
    }

    /**
     * Wraps a {@link DatasetHandle} {@link #acquire} obtained from the delegate so that closing it
     * decrements this instance's own {@link #openLeases} count for {@code id} - the signal
     * {@link #close()} checks instead of {@link #list()} (see the class javadoc). Every accessor
     * simply forwards to the wrapped handle; only {@link #close()} carries the bookkeeping, guarded
     * against double-counting a caller that closes the same handle more than once.
     */
    private final class LeaseTrackingHandle implements DatasetHandle {

        private final DatasetId id;
        private final DatasetHandle delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        LeaseTrackingHandle(DatasetId id, DatasetHandle delegate) {
            this.id = id;
            this.delegate = delegate;
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public SparqlQuery sparqlQuery() {
            return delegate.sparqlQuery();
        }

        @Override
        public SparqlUpdate sparqlUpdate() {
            return delegate.sparqlUpdate();
        }

        @Override
        public DatasetExport datasetExport() {
            return delegate.datasetExport();
        }

        @Override
        public DatasetTransactor transactor() {
            return delegate.transactor();
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } finally {
                if (closed.compareAndSet(false, true)) {
                    LongAdder count = openLeases.get(id);
                    if (count != null) {
                        count.decrement();
                    }
                }
            }
        }
    }
}
