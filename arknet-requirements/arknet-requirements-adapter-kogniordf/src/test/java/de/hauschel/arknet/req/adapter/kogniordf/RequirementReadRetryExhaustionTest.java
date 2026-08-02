// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementReadConflictException;

/**
 * Regression test for the P1 review follow-up on PR #227 (issue #171): {@link
 * KognioRdfRequirementRepository#readInTransaction} must translate a
 * {@code ConcurrencyConflictException} that survives every retry attempt into the bounded
 * context's own {@link RequirementReadConflictException} - never let the raw
 * {@code io.kogn.rdf} type reach a caller - exactly the "always translate, never pass through
 * raw" convention {@code WriteFunnel} (ADR-013) establishes for every write path of this adapter,
 * extended here to its read paths.
 *
 * <p>Uses a fake {@link DatasetLifecycle} whose {@link DatasetTransactor#inTransaction} always
 * loses the race, rather than the real-store forced-interleaving technique {@link
 * RequirementReadTornReadRealStoreConcurrencyTest} uses: exhausting a bounded retry loop needs no
 * genuine concurrency, only a transactor that always throws.</p>
 */
class RequirementReadRetryExhaustionTest {

    private static final ProjectId WS = new ProjectId("test-project");
    private static final RequirementCode CODE = new RequirementCode("FR-1");

    @Test
    void findByCode_afterExhaustingRetries_throwsTranslatedSignalNotRawStoreException() {
        // given - a transactor that always loses the SERIALIZABLE race.
        AtomicInteger attempts = new AtomicInteger();
        RequirementRepository repository = KognioRdfRequirementRepositoryFactory.over(
                new AlwaysConflictingLifecycle(attempts), DisplayLocale.DEFAULT);

        // when
        RequirementReadConflictException thrown = assertThrows(RequirementReadConflictException.class,
                () -> repository.findByCode(WS, CODE));

        // then - translated, not raw; every attempt actually ran; cause preserved.
        assertEquals(CodeAssignment.DEFAULT_MAX_ATTEMPTS, attempts.get());
        assertEquals(WS, thrown.projectId());
        assertInstanceOf(ConcurrencyConflictException.class, thrown.getCause());
    }

    @Test
    void findAll_afterExhaustingRetries_throwsTranslatedSignalNotRawStoreException() {
        // given - a transactor that always loses the SERIALIZABLE race.
        AtomicInteger attempts = new AtomicInteger();
        RequirementRepository repository = KognioRdfRequirementRepositoryFactory.over(
                new AlwaysConflictingLifecycle(attempts), DisplayLocale.DEFAULT);

        // when
        RequirementReadConflictException thrown = assertThrows(RequirementReadConflictException.class,
                () -> repository.findAll(WS));

        // then - translated, not raw; every attempt actually ran; cause preserved.
        assertEquals(CodeAssignment.DEFAULT_MAX_ATTEMPTS, attempts.get());
        assertEquals(WS, thrown.projectId());
        assertInstanceOf(ConcurrencyConflictException.class, thrown.getCause());
    }

    // ---- fakes ----------------------------------------------------------------------------

    private static final class AlwaysConflictingLifecycle implements DatasetLifecycle {

        private final AtomicInteger attempts;

        AlwaysConflictingLifecycle(AtomicInteger attempts) {
            this.attempts = attempts;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return new AlwaysConflictingHandle(attempts);
        }

        @Override
        public void close(DatasetId id) {
            // no-op: never reached by this test
        }

        @Override
        public void delete(DatasetId id) {
            // no-op: never reached by this test
        }

        @Override
        public Set<DatasetId> list() {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }

    private static final class AlwaysConflictingHandle implements DatasetHandle {

        private final AtomicInteger attempts;

        AlwaysConflictingHandle(AtomicInteger attempts) {
            this.attempts = attempts;
        }

        @Override
        public GraphStore graphStore() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public SparqlQuery sparqlQuery() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public SparqlUpdate sparqlUpdate() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public DatasetExport datasetExport() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public DatasetTransactor transactor() {
            return new DatasetTransactor() {
                @Override
                public <T> T inTransaction(Function<DatasetTx, T> work) {
                    attempts.incrementAndGet();
                    throw new ConcurrencyConflictException("lost the race", null);
                }
            };
        }

        @Override
        public void close() {
            // no-op: never reached by this test
        }
    }
}
