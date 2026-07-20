// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Regression test for the second interleaving of issue #144, reproduced against a real
 * RDF4J-backed store (in-memory {@code SailRepository}) with real threads - unlike {@code
 * BoundedContextServiceConcurrencyTest}, which reproduces the first interleaving ("a concurrent
 * caller commits its whole write before this one's transaction even begins") with a repository
 * decorator and no real transactions at all.
 *
 * <p><strong>What this proves.</strong> Two callers can also race so that their transactions
 * genuinely <em>overlap</em>: both pass the in-transaction {@code ASK} code-uniqueness guard
 * before either commits (neither sees the other's uncommitted write under {@code SERIALIZABLE}
 * isolation, kogn-io/rdf-core#18), and only the second commit is rejected as a conflict - by the
 * store itself, not by the guard. {@link KognioRdfBoundedContextRepository#isWriteConflict}
 * translates that commit-time rejection into the same {@link
 * de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException} the synchronous guard
 * throws, so {@code CodeAssignment}'s retry (in {@link BoundedContextService#add}) catches this
 * interleaving exactly like the first one: both callers end up with distinct codes, neither sees
 * a failure.</p>
 *
 * <p><strong>How the overlap is forced deterministically.</strong> Mirrors the pattern kogn-io/
 * rdf-core's own {@code DatasetRdf4jTest#inTransaction_overlappingAskGuardedWrites_loserCommitFails}
 * test uses to prove the store-level mechanism, one layer up: a {@link DatasetLifecycle} decorator
 * wraps each caller's {@link DatasetTx} so that, right after its second {@code ASK} (the
 * code-uniqueness guard {@link KognioRdfBoundedContextRepository#write} issues), it blocks on a
 * {@link CyclicBarrier} with two parties. Both callers' guards must therefore have already passed
 * before either proceeds to write - the exact "ASK-guard-defeat" scenario - while a {@link
 * CountDownLatch} then forces the loser to wait until the winner's transaction has fully committed
 * before the loser's own commit is attempted, so which of the two conflicts is deterministic
 * instead of a flaky race. The decorator disarms itself after firing once per caller, so {@code
 * CodeAssignment}'s retry transaction (a fresh {@code create()} call with a freshly recomputed
 * code) runs unsynchronised, straight through to a normal commit.</p>
 *
 * <p><strong>Timeout.</strong> {@link CyclicBarrier#await()}/{@link CountDownLatch#await()} block
 * indefinitely by default; a future regression that stops one caller from ever reaching its
 * barrier/latch would otherwise hang {@code join()} forever, so neither {@code @AfterEach} nor
 * {@code shutDownAll()} would ever run - the build would hang instead of failing. The project has
 * no {@code junit-platform.properties}/Surefire-level timeout, so this class-level {@link Timeout}
 * is the only backstop; the interleaving itself normally resolves in well under a second.</p>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class BoundedContextServiceRealStoreConcurrencyTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;

    private DatasetLifecycleRdf4j realLifecycle;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-bc-real-race");
        realLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
    }

    @AfterEach
    void tearDown() {
        realLifecycle.shutDownAll();
    }

    @Test
    void concurrentAddCallsUnderGenuinelyOverlappingTransactions_bothGetDistinctCodes() throws InterruptedException {
        // given - both callers' guards are released together only once both have checked "is this
        // code already taken?" and found it free; the loser is then held back until the winner's
        // transaction has actually committed, so the loser's own commit is the one that conflicts.
        CyclicBarrier bothGuardsChecked = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        BoundedContextService winnerService = guardedService(() -> awaitBarrier(bothGuardsChecked));
        BoundedContextService loserService = guardedService(() -> {
            awaitBarrier(bothGuardsChecked);
            awaitLatch(winnerCommitted);
        });

        AtomicReference<BoundedContext> winnerResult = new AtomicReference<>();
        AtomicReference<BoundedContext> loserResult = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            try {
                winnerResult.set(winnerService.add(WS, newBoundedContext("Winner")));
            } finally {
                winnerCommitted.countDown();
            }
        });
        Thread loserThread = new Thread(() -> {
            try {
                loserResult.set(loserService.add(WS, newBoundedContext("Loser")));
            } catch (RuntimeException e) {
                loserFailure.set(e);
            }
        });

        // when
        winnerThread.start();
        loserThread.start();
        winnerThread.join();
        loserThread.join();

        // then - the loser's first commit lost the real store-level conflict, but CodeAssignment's
        // retry inside BoundedContextService#add recovered it with a freshly recomputed code; no
        // caller-visible failure, and both bounded contexts persisted under distinct codes.
        assertNull(loserFailure.get(), "the retry must absorb the store's commit-time conflict");
        assertNotNull(winnerResult.get());
        assertNotNull(loserResult.get());
        assertNotEquals(winnerResult.get().code(), loserResult.get().code());

        List<BoundedContext> stored = KognioRdfBoundedContextRepositoryFactory.over(realLifecycle).findAll(WS);
        assertEquals(2, stored.size());
        assertTrue(stored.stream().map(BoundedContext::code).toList()
                .containsAll(List.of(winnerResult.get().code(), loserResult.get().code())));
    }

    private static NewBoundedContext newBoundedContext(String owner) {
        return new NewBoundedContext("OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, owner);
    }

    // ---- synchronisation helpers ---------------------------------------------------------

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- DatasetLifecycle decoration: pauses each caller's transaction right after its second
    //      ASK (the code-uniqueness guard), exactly once, then gets out of the way -------------

    private BoundedContextService guardedService(Runnable afterSecondAsk) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new AskGuardSyncTx(tx, afterSecondAsk);
            }
            return tx;
        });
        BoundedContextRepository repository = KognioRdfBoundedContextRepositoryFactory.over(guarded);
        TermLookup unusedTermLookup = (workspaceId, termCode) -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        return new BoundedContextService(repository, new UuidResourceIdFactory(), unusedTermLookup);
    }

    /** Wraps a real {@link DatasetLifecycle}, decorating every acquired transaction's {@link DatasetTx}. */
    private static final class GuardedLifecycle implements DatasetLifecycle {

        private final DatasetLifecycle delegate;
        private final Function<DatasetTx, DatasetTx> txDecorator;

        GuardedLifecycle(DatasetLifecycle delegate, Function<DatasetTx, DatasetTx> txDecorator) {
            this.delegate = delegate;
            this.txDecorator = txDecorator;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return new GuardedHandle(delegate.acquire(id), txDecorator);
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
    }

    private static final class GuardedHandle implements DatasetHandle {

        private final DatasetHandle delegate;
        private final Function<DatasetTx, DatasetTx> txDecorator;

        GuardedHandle(DatasetHandle delegate, Function<DatasetTx, DatasetTx> txDecorator) {
            this.delegate = delegate;
            this.txDecorator = txDecorator;
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
        public DatasetTransactor transactor() {
            DatasetTransactor real = delegate.transactor();
            return new DatasetTransactor() {
                @Override
                public <T> T inTransaction(Function<DatasetTx, T> fn) {
                    return real.inTransaction(tx -> fn.apply(txDecorator.apply(tx)));
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Runs {@code afterSecondAsk} exactly once its delegate's second {@code ask()} call returns -
     * {@link KognioRdfBoundedContextRepository#write} issues exactly two: the identity guard, then
     * (only reached when the identity guard passed) the code-uniqueness guard.
     */
    private static final class AskGuardSyncTx implements DatasetTx {

        private final DatasetTx delegate;
        private final Runnable afterSecondAsk;
        private int askCount;

        AskGuardSyncTx(DatasetTx delegate, Runnable afterSecondAsk) {
            this.delegate = delegate;
            this.afterSecondAsk = afterSecondAsk;
        }

        @Override
        public boolean ask(String query) {
            boolean result = delegate.ask(query);
            askCount++;
            if (askCount == 2) {
                afterSecondAsk.run();
            }
            return result;
        }

        @Override
        public void add(IRI graph, ReadableGraph data) {
            delegate.add(graph, data);
        }

        @Override
        public void remove(IRI graph, ReadableGraph data) {
            delegate.remove(graph, data);
        }

        @Override
        public void clear(IRI graph) {
            delegate.clear(graph);
        }

        @Override
        public void update(String sparqlUpdate) {
            delegate.update(sparqlUpdate);
        }

        @Override
        public Stream<BindingSet> select(String query) {
            return delegate.select(query);
        }

        @Override
        public ReadableGraph construct(String query) {
            return delegate.construct(query);
        }
    }
}
