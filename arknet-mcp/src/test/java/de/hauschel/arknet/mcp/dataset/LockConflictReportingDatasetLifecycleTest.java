// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;

/**
 * Tests {@link LockConflictReportingDatasetLifecycle}'s two responsibilities separately: which
 * {@code acquire} failures it recognises as a lock conflict and translates (a hand-rolled stub
 * predicate, plus the real RDF4J failure the decorator exists to translate), and which
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
 * they pin down delegation, message composition, that the original failure is preserved as a
 * suppressed exception rather than a cause (#137), and the pass-through path without needing a
 * second RDF4J store per case. The pass-through path is deliberately proven against a stub rather
 * than against a real store made to fail differently (e.g. a non-writable storage directory): such
 * a case does not fail at all for a process that may write regardless of the permission bits, so
 * it would report a defect on any machine that runs its build as root.</p>
 *
 * <p>{@link #closesEveryDatasetTheDelegateListsOnShutdown()} covers the decorator's third
 * responsibility (issue #140): as {@link AutoCloseable}, {@code close()} must close every dataset
 * the delegate currently {@code list()}s, not just one, so a daemon shutdown releases the whole
 * store in an orderly way.</p>
 *
 * <p>Two regressions from the full review of 2026-08-06 get their own tests.
 * {@link #closeDoesNotWarnWhenEveryAcquiredHandleWasClosed()} and
 * {@link #closeWarnsAboutADatasetWhoseAcquiredHandleIsStillOpen()} pin down issue #294: {@link
 * FakeLifecycle#list()} deliberately keeps returning a dataset id forever (mirroring the real
 * {@code list()} contract, "known" not "open"), so before the fix the first test's {@code close()}
 * would have warned about a dataset nothing ever left open. {@link
 * #closeContinuesClosingRemainingDatasetsWhenOneThrows()} pins down issue #302: a failing {@code
 * close(DatasetId)} for one dataset must not stop the loop from attempting the rest.</p>
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
                    .hasNoCause()
                    .satisfies(e -> assertThat(e.getSuppressed()).isNotEmpty());
        } finally {
            firstHandle.close();
            first.close(id);
        }
    }

    @Test
    void delegatesEveryOtherCallAndWrapsAnAcquireThatSucceeds() {
        final DatasetId id = new DatasetId("delegation-test");
        final StubHandle handle = new StubHandle();
        final FakeLifecycle delegate = new FakeLifecycle(handle);
        final LockConflictReportingDatasetLifecycle guarded =
                new LockConflictReportingDatasetLifecycle(delegate, storageDir, e -> true);

        // Not the same instance any more (issue #294): acquire() wraps the delegate's handle so
        // closing it can update this decorator's own open-lease bookkeeping. Closing the wrapper
        // must still reach the underlying handle.
        final DatasetHandle acquired = guarded.acquire(id);
        assertThat(acquired).isNotSameAs(handle);
        acquired.close();
        assertThat(handle.closed).isTrue();

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
                .hasNoCause()
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(original));
    }

    @Test
    void closesEveryDatasetTheDelegateListsOnShutdown() {
        final FakeLifecycle delegate = new FakeLifecycle(new StubHandle());
        final LockConflictReportingDatasetLifecycle guarded =
                new LockConflictReportingDatasetLifecycle(delegate, storageDir, e -> true);

        guarded.close();

        assertThat(delegate.closed).containsExactlyElementsOf(delegate.list());
    }

    @Test
    void rethrowsAnAcquireFailureThePredicateDoesNotRecognise() {
        final DatasetId id = new DatasetId("passthrough-test");
        final IllegalStateException original = new IllegalStateException("unable to create data directory");
        final LockConflictReportingDatasetLifecycle guarded = new LockConflictReportingDatasetLifecycle(
                new FailingLifecycle(original), storageDir, e -> false);

        assertThatThrownBy(() -> guarded.acquire(id)).isSameAs(original);
    }

    /**
     * Regression test for issue #294: {@link FakeLifecycle#list()} keeps naming its dataset even
     * after that dataset was cleanly closed (exactly what the real {@code list()} contract
     * promises - "known", not "open"). Before the fix, {@code close()} re-checked {@code list()}
     * for "still open" and would have warned about this dataset regardless. Closing the handle
     * {@link #acquire} returned must be enough to keep {@code close()} quiet.
     */
    @Test
    void closeDoesNotWarnWhenEveryAcquiredHandleWasClosed() {
        final DatasetId id = new DatasetId("delegation-test");
        final FakeLifecycle delegate = new FakeLifecycle(new StubHandle());
        final LockConflictReportingDatasetLifecycle guarded =
                new LockConflictReportingDatasetLifecycle(delegate, storageDir, e -> true);
        guarded.acquire(id).close();

        final ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            guarded.close();

            assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("still open"));
        } finally {
            detachLogAppender(logs);
        }
    }

    /**
     * Regression test for issue #294's other half: a handle {@link #acquire} returned but that was
     * never closed - the genuine "in-flight lease at shutdown time" case - must still be reported,
     * naming exactly that dataset.
     */
    @Test
    void closeWarnsAboutADatasetWhoseAcquiredHandleIsStillOpen() {
        final DatasetId openId = new DatasetId("still-open-test");
        final FakeLifecycle delegate = new FakeLifecycle(new StubHandle());
        final LockConflictReportingDatasetLifecycle guarded =
                new LockConflictReportingDatasetLifecycle(delegate, storageDir, e -> true);
        guarded.acquire(openId); // deliberately never closed

        final ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            guarded.close();

            assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("still open")
                    && event.getFormattedMessage().contains(openId.value()));
        } finally {
            detachLogAppender(logs);
        }
    }

    /**
     * Regression test for issue #302: {@code close(DatasetId)} throwing for one dataset must not
     * abort the shutdown loop before the remaining datasets are even attempted.
     */
    @Test
    void closeContinuesClosingRemainingDatasetsWhenOneThrows() {
        final DatasetId failing = new DatasetId("failing-dataset");
        final DatasetId healthy = new DatasetId("healthy-dataset");
        final ThrowingOnCloseLifecycle delegate =
                new ThrowingOnCloseLifecycle(List.of(failing, healthy), failing);
        final LockConflictReportingDatasetLifecycle guarded =
                new LockConflictReportingDatasetLifecycle(delegate, storageDir, e -> true);

        guarded.close();

        assertThat(delegate.closeAttempts).containsExactly(failing, healthy);
    }

    /**
     * Attaches a fresh {@link ListAppender} to {@link LockConflictReportingDatasetLifecycle}'s
     * logger so a test can assert a specific {@code WARN} was actually logged, not merely that the
     * computed value happens to be right.
     */
    private static ListAppender<ILoggingEvent> attachLogAppender() {
        final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(LockConflictReportingDatasetLifecycle.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(LockConflictReportingDatasetLifecycle.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    /** Never actually opened - records only whether {@link #close()} was called. */
    private static final class StubHandle implements DatasetHandle {

        private boolean closed;

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
            closed = true;
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

    /**
     * Lists exactly the given {@code known} ids (insertion order preserved) and throws when
     * {@link #close(DatasetId)} is called for {@code failing} - used to prove the shutdown loop
     * still attempts the remaining datasets (issue #302). Every attempted id, including the
     * failing one, is recorded before the exception is thrown.
     */
    private static final class ThrowingOnCloseLifecycle implements DatasetLifecycle {

        private final Set<DatasetId> known;
        private final DatasetId failing;
        private final List<DatasetId> closeAttempts = new java.util.ArrayList<>();

        ThrowingOnCloseLifecycle(List<DatasetId> known, DatasetId failing) {
            this.known = new LinkedHashSet<>(known);
            this.failing = failing;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            throw new UnsupportedOperationException("unused in this test");
        }

        @Override
        public void close(DatasetId id) {
            closeAttempts.add(id);
            if (id.equals(failing)) {
                throw new RuntimeException("simulated close failure for " + id);
            }
        }

        @Override
        public void delete(DatasetId id) {
            throw new UnsupportedOperationException("unused in this test");
        }

        @Override
        public Set<DatasetId> list() {
            return known;
        }
    }
}
