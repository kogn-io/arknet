// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class ReadTransactionsTest {

    @Test
    void aReadThatLosesEveryAttemptReportsAnArknetSignalThatSaysToRetry() {
        AtomicInteger attempts = new AtomicInteger();

        ReadConflictException thrown = assertThrows(ReadConflictException.class,
                () -> ReadTransactions.read(new ConflictingHandle(attempts, Integer.MAX_VALUE), tx -> "never"));

        assertEquals(ReadTransactions.MAX_ATTEMPTS, attempts.get());
        assertEquals(ReadTransactions.MAX_ATTEMPTS, thrown.attempts());
        assertInstanceOf(ConcurrencyConflictException.class, thrown.getCause());
        assertTrue(thrown.getMessage().contains("try the call again"), thrown.getMessage());
    }

    @Test
    void aReadThatLosesOnceIsRunAgainAndReturnsItsResult() {
        AtomicInteger attempts = new AtomicInteger();

        String result = ReadTransactions.read(new ConflictingHandle(attempts, 1), tx -> "read");

        assertEquals("read", result);
        assertEquals(2, attempts.get());
    }

    /** Loses the first {@code conflicts} attempts, then runs the work. */
    private static final class ConflictingHandle implements DatasetHandle {

        private final AtomicInteger attempts;
        private final int conflicts;

        ConflictingHandle(AtomicInteger attempts, int conflicts) {
            this.attempts = attempts;
            this.conflicts = conflicts;
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
                    if (attempts.incrementAndGet() <= conflicts) {
                        throw new ConcurrencyConflictException("lost the race", null);
                    }
                    return work.apply(null);
                }
            };
        }

        @Override
        public void close() {
            // nothing to close
        }
    }
}
