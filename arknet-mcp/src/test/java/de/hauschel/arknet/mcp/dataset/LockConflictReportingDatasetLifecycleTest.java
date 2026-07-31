// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;

/**
 * Tests {@link LockConflictReportingDatasetLifecycle}'s two responsibilities separately: which
 * {@code acquire} failures it recognises as a lock conflict and translates (a hand-rolled stub
 * predicate, plus the real RDF4J failure the decorator exists to translate, issue #64), and which
 * failures it must leave alone because they are not a lock conflict at all.
 *
 * <p>{@link #aSecondLifecycleOverTheSameStorageDirectoryFailsWithADidacticMessage()} is the proof
 * that matters for the translated case - it does not mock the underlying failure, it reproduces
 * it. The first lifecycle opens (and, by acquiring, locks) a real {@code NativeStore} directory; a
 * second, independently constructed lifecycle over that same directory is then handed to the
 * decorator, together with {@link KognioRdfRequirementRepositoryFactory#DEFAULT_LOCK_CONFLICT}, and
 * made to acquire the same {@link DatasetId}. This does trigger a genuine RDF4J lock conflict even
 * though both lifecycles live in the same JVM/process - {@code DatasetLifecycleRdf4j}'s own class
 * documentation states that an instance owns its storage root exclusively and that a second one
 * fails with RDF4J's {@code RepositoryLockedException} as soon as it touches the same dataset,
 * because the lock is a {@code NativeStore} file lock, not an in-JVM object lock. Running it
 * against the real default predicate is itself the proof that the default recognises the real
 * RDF4J type it names.</p>
 *
 * <p>{@link #delegatesEveryOtherCallAndAnAcquireThatSucceedsUnchanged()},
 * {@link #translatesAnAcquireFailureThePredicateRecognises()} and
 * {@link #rethrowsAnAcquireFailureThePredicateDoesNotRecognise()} cover the decorator's own logic
 * in isolation against a hand-rolled stub, as a fallback/addition to the real-lock proof above -
 * they pin down delegation, message/cause composition and the pass-through path without needing a
 * second RDF4J store per case. {@link #aNonWritableStorageDirectoryIsNotMisdiagnosedAsALockConflict()}
 * is a second real-store proof for the pass-through path: a storage directory the process cannot
 * write to fails {@code acquire} for an entirely different reason RDF4J does not translate into
 * {@code RepositoryLockedException}, and the default predicate must not catch it anyway.</p>
 */
class LockConflictReportingDatasetLifecycleTest {

    @TempDir
    Path storageDir;

    @Test
    void aSecondLifecycleOverTheSameStorageDirectoryFailsWithADidacticMessage() {
        final DatasetId id = new DatasetId("lock-conflict-test-project");
        final DatasetLifecycle first = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        final DatasetHandle firstHandle = first.acquire(id);
        try {
            final DatasetLifecycle second = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
            final LockConflictReportingDatasetLifecycle guarded = new LockConflictReportingDatasetLifecycle(
                    second, storageDir, KognioRdfRequirementRepositoryFactory.DEFAULT_LOCK_CONFLICT);

            assertThatThrownBy(() -> guarded.acquire(id))
                    .isInstanceOf(DatasetLockConflictException.class)
                    .hasMessageContaining(storageDir.toString())
                    .hasMessageContaining(id.value())
                    .hasMessageContaining("second arknet daemon instance")
                    .hasMessageContaining("stdio")
                    .hasMessageContaining("shared daemon")
                    .cause().isNotNull();
        } finally {
            firstHandle.close();
            first.close(id);
        }
    }

    @Test
    void delegatesEveryOtherCallAndAnAcquireThatSucceedsUnchanged() {
        final DatasetId id = new DatasetId("delegation-test");
        final DatasetHandle handle = new StubHandle();
        final FakeLifecycle delegate = new FakeLifecycle(handle);
        final LockConflictReportingDatasetLifecycle guarded =
                new LockConflictReportingDatasetLifecycle(delegate, storageDir, e -> true);

        assertThat(guarded.acquire(id)).isSameAs(handle);

        guarded.close(id);
        guarded.delete(id);
        assertThat(guarded.list()).isEqualTo(Set.of(id));

        assertThat(delegate.closed).containsExactly(id);
        assertThat(delegate.deleted).containsExactly(id);
    }

    @Test
    void translatesAnAcquireFailureThePredicateRecognises() {
        final DatasetId id = new DatasetId("translation-test");
        final IllegalStateException original = new IllegalStateException("boom");
        final LockConflictReportingDatasetLifecycle guarded = new LockConflictReportingDatasetLifecycle(
                new FailingLifecycle(original), storageDir, e -> true);

        assertThatThrownBy(() -> guarded.acquire(id))
                .isInstanceOf(DatasetLockConflictException.class)
                .hasMessageContaining(storageDir.toString())
                .hasMessageContaining(id.value())
                .hasMessageContaining("boom")
                .hasCause(original);
    }

    @Test
    void rethrowsAnAcquireFailureThePredicateDoesNotRecognise() {
        final DatasetId id = new DatasetId("passthrough-test");
        final IllegalStateException original = new IllegalStateException("unable to create data directory");
        final LockConflictReportingDatasetLifecycle guarded = new LockConflictReportingDatasetLifecycle(
                new FailingLifecycle(original), storageDir, e -> false);

        assertThatThrownBy(() -> guarded.acquire(id)).isSameAs(original);
    }

    @Test
    void aNonWritableStorageDirectoryIsNotMisdiagnosedAsALockConflict() {
        storageDir.toFile().setWritable(false);
        try {
            final DatasetId id = new DatasetId("unwritable-storage-test");
            final DatasetLifecycle delegate = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
            final LockConflictReportingDatasetLifecycle guarded = new LockConflictReportingDatasetLifecycle(
                    delegate, storageDir, KognioRdfRequirementRepositoryFactory.DEFAULT_LOCK_CONFLICT);

            assertThatThrownBy(() -> guarded.acquire(id)).isNotInstanceOf(DatasetLockConflictException.class);
        } finally {
            storageDir.toFile().setWritable(true);
        }
    }

    /** Never actually opened - only ever compared by reference above. */
    private static final class StubHandle implements DatasetHandle {

        @Override
        public io.kogn.rdf.dataset.GraphStore graphStore() {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.kogn.rdf.dataset.SparqlQuery sparqlQuery() {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.kogn.rdf.dataset.SparqlUpdate sparqlUpdate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.kogn.rdf.dataset.DatasetExport datasetExport() {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.kogn.rdf.dataset.DatasetTransactor transactor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            // no-op: never opened
        }
    }

    /** Records every {@link #close(DatasetId)}/{@link #delete(DatasetId)} call it forwards. */
    private static final class FakeLifecycle implements DatasetLifecycle {

        private final DatasetHandle handle;
        private final java.util.List<DatasetId> closed = new java.util.ArrayList<>();
        private final java.util.List<DatasetId> deleted = new java.util.ArrayList<>();

        FakeLifecycle(DatasetHandle handle) {
            this.handle = handle;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return handle;
        }

        @Override
        public void close(DatasetId id) {
            closed.add(id);
        }

        @Override
        public void delete(DatasetId id) {
            deleted.add(id);
        }

        @Override
        public Set<DatasetId> list() {
            return Set.of(new DatasetId("delegation-test"));
        }
    }

    /** Always fails {@link #acquire(DatasetId)} with the given exception. */
    private record FailingLifecycle(RuntimeException failure) implements DatasetLifecycle {

        @Override
        public DatasetHandle acquire(DatasetId id) {
            throw failure;
        }

        @Override
        public void close(DatasetId id) {
            // unused in this test
        }

        @Override
        public void delete(DatasetId id) {
            // unused in this test
        }

        @Override
        public Set<DatasetId> list() {
            return Set.of();
        }
    }
}
