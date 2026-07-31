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
 * Tests {@link LockSafeDatasetLifecycle} against the real failure it exists to translate
 * (issue #64): two separate {@code DatasetLifecycleRdf4j} instances contending for the same
 * on-disk storage directory.
 *
 * <p>{@link #aSecondLifecycleOverTheSameStorageDirectoryFailsWithADidacticMessage()} is the proof
 * that matters - it does not mock the underlying failure, it reproduces it. The first lifecycle
 * opens (and, by acquiring, locks) a real {@code NativeStore} directory; a second, independently
 * constructed lifecycle over that same directory is then handed to the decorator and made to
 * acquire the same {@link DatasetId}. This does trigger a genuine RDF4J lock conflict even though
 * both lifecycles live in the same JVM/process - {@code DatasetLifecycleRdf4j}'s own class
 * documentation states that an instance owns its storage root exclusively and that a second one
 * fails with RDF4J's {@code RepositoryLockedException} as soon as it touches the same dataset,
 * because the lock is a {@code NativeStore} file lock, not an in-JVM object lock.</p>
 *
 * <p>{@link #delegatesEveryOtherCallAndAnAcquireThatSucceedsUnchanged()} and
 * {@link #translatesAnyAcquireFailureRegardlessOfItsConcreteType()} cover the decorator's own
 * logic in isolation against a hand-rolled stub, as a fallback/addition to the real-lock proof
 * above - they pin down delegation and message/cause composition without needing a second RDF4J
 * store per case.</p>
 */
class LockSafeDatasetLifecycleTest {

    @TempDir
    Path storageDir;

    @Test
    void aSecondLifecycleOverTheSameStorageDirectoryFailsWithADidacticMessage() {
        final DatasetId id = new DatasetId("lock-conflict-test-project");
        final DatasetLifecycle first = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        final DatasetHandle firstHandle = first.acquire(id);
        try {
            final DatasetLifecycle second = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
            final LockSafeDatasetLifecycle guarded = new LockSafeDatasetLifecycle(second, storageDir);

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
        final LockSafeDatasetLifecycle guarded = new LockSafeDatasetLifecycle(delegate, storageDir);

        assertThat(guarded.acquire(id)).isSameAs(handle);

        guarded.close(id);
        guarded.delete(id);
        assertThat(guarded.list()).isEqualTo(Set.of(id));

        assertThat(delegate.closed).containsExactly(id);
        assertThat(delegate.deleted).containsExactly(id);
    }

    @Test
    void translatesAnyAcquireFailureRegardlessOfItsConcreteType() {
        final DatasetId id = new DatasetId("translation-test");
        final IllegalStateException original = new IllegalStateException("boom");
        final LockSafeDatasetLifecycle guarded =
                new LockSafeDatasetLifecycle(new FailingLifecycle(original), storageDir);

        assertThatThrownBy(() -> guarded.acquire(id))
                .isInstanceOf(DatasetLockConflictException.class)
                .hasMessageContaining(storageDir.toString())
                .hasMessageContaining(id.value())
                .hasMessageContaining("boom")
                .hasCause(original);
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
