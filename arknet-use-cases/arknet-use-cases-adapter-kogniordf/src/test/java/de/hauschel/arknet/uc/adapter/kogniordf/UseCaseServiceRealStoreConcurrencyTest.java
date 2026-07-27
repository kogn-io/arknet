// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

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
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.out.ActorLookup;
import de.hauschel.arknet.uc.application.port.out.RequirementLookup;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.UseCase;

/**
 * Regression test for the second interleaving of issue #144, reproduced against a real
 * RDF4J-backed store (in-memory {@code SailRepository}) with real threads - unlike {@code
 * UseCaseServiceConcurrencyTest}, which reproduces the first interleaving ("a concurrent caller
 * commits its whole write before this one's transaction even begins") with a repository decorator
 * and no real transactions at all.
 *
 * <p>Mirrors {@code BoundedContextServiceRealStoreConcurrencyTest} exactly: the shared
 * {@link de.hauschel.arknet.persistence.WriteFunnel} translates a genuine store-level commit conflict
 * (two callers' code-uniqueness guards both passing before either commits, under {@code
 * SERIALIZABLE} isolation, kogn-io/rdf-core#18) into the same {@link
 * de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException} the synchronous guard throws, so
 * {@code CodeAssignment}'s retry (in {@link UseCaseService#add}) catches this interleaving exactly
 * like the first one: both callers end up with distinct codes, neither sees a failure.</p>
 *
 * <p><strong>Timeout.</strong> {@link CyclicBarrier#await()}/{@link CountDownLatch#await()} block
 * indefinitely by default; a future regression that stops one caller from ever reaching its
 * barrier/latch would otherwise hang {@code join()} forever, so neither {@code @AfterEach} nor
 * {@code shutDownAll()} would ever run - the build would hang instead of failing. The project has
 * no {@code junit-platform.properties}/Surefire-level timeout, so this class-level {@link Timeout}
 * is the only backstop; the interleaving itself normally resolves in well under a second.</p>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class UseCaseServiceRealStoreConcurrencyTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;
    private static final ResourceId CUSTOMER_ID = ResourceId.of("https://w3id.org/arknet/id/actor-customer");
    private static final ActorLookup CUSTOMER_ACTOR_LOOKUP = (workspaceId, actorName) -> {
        if (!"Customer".equals(actorName)) {
            throw new IllegalArgumentException("unexpected actor name in this test: " + actorName);
        }
        return CUSTOMER_ID;
    };
    /** Unused by this race: no step realises a requirement. */
    private static final RequirementLookup UNUSED_REQUIREMENT_LOOKUP = (workspaceId, requirementCode) -> {
        throw new UnsupportedOperationException("not exercised by this test");
    };

    private DatasetLifecycleRdf4j realLifecycle;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-uc-real-race");
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

        UseCaseService winnerService = guardedService(() -> awaitBarrier(bothGuardsChecked));
        UseCaseService loserService = guardedService(() -> {
            awaitBarrier(bothGuardsChecked);
            awaitLatch(winnerCommitted);
        });

        AtomicReference<UseCase> winnerResult = new AtomicReference<>();
        AtomicReference<UseCase> loserResult = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            try {
                winnerResult.set(winnerService.add(WS, newUseCase()));
            } finally {
                winnerCommitted.countDown();
            }
        });
        Thread loserThread = new Thread(() -> {
            try {
                loserResult.set(loserService.add(WS, newUseCase()));
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
        // retry inside UseCaseService#add recovered it with a freshly recomputed code; no
        // caller-visible failure, and both use cases persisted under distinct codes.
        assertNull(loserFailure.get(), "the retry must absorb the store's commit-time conflict");
        assertNotNull(winnerResult.get());
        assertNotNull(loserResult.get());
        assertNotEquals(winnerResult.get().code(), loserResult.get().code());

        List<UseCase> stored =
                KognioRdfUseCaseRepositoryFactory.over(realLifecycle, new UuidResourceIdFactory()).findAll(WS);
        assertEquals(2, stored.size());
        assertTrue(stored.stream().map(UseCase::code).toList()
                .containsAll(List.of(winnerResult.get().code(), loserResult.get().code())));
    }

    private static NewUseCase newUseCase() {
        return new NewUseCase("Place order", "goal of Place order", null, null, "Customer",
                List.of(), null, null, List.of(new NewStep(1, "do something", List.of())), List.of());
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

    private UseCaseService guardedService(Runnable afterSecondAsk) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new AskGuardSyncTx(tx, afterSecondAsk);
            }
            return tx;
        });
        UseCaseRepository repository = KognioRdfUseCaseRepositoryFactory.over(guarded, new UuidResourceIdFactory());
        return new UseCaseService(repository, new UuidResourceIdFactory(), UNUSED_REQUIREMENT_LOOKUP,
                CUSTOMER_ACTOR_LOOKUP);
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
     * {@link KognioRdfUseCaseRepository#write} issues exactly two on the create path: the identity
     * guard, then the code-uniqueness guard.
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
        public boolean ask(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
            return delegate.ask(query, bindings);
        }

        @Override
        public long add(IRI graph, ReadableGraph data) {
            return delegate.add(graph, data);
        }

        @Override
        public long remove(IRI graph, ReadableGraph data) {
            return delegate.remove(graph, data);
        }

        @Override
        public void clear(IRI graph) {
            delegate.clear(graph);
        }

        @Override
        public ReadableGraph export(IRI graph) {
            return delegate.export(graph);
        }

        @Override
        public long count(IRI graph) {
            return delegate.count(graph);
        }

        @Override
        public long count() {
            return delegate.count();
        }

        @Override
        public boolean contains(IRI graph, io.kogn.rdf.terms.BlankNodeOrIRI subject, IRI predicate,
                io.kogn.rdf.terms.RDFTerm object) {
            return delegate.contains(graph, subject, predicate, object);
        }

        @Override
        public void update(String sparqlUpdate) {
            delegate.update(sparqlUpdate);
        }

        @Override
        public void update(String sparqlUpdate, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
            delegate.update(sparqlUpdate, bindings);
        }

        @Override
        public Stream<BindingSet> select(String query) {
            return delegate.select(query);
        }

        @Override
        public Stream<BindingSet> select(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
            return delegate.select(query, bindings);
        }

        @Override
        public ReadableGraph construct(String query) {
            return delegate.construct(query);
        }

        @Override
        public ReadableGraph construct(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
            return delegate.construct(query, bindings);
        }
    }
}
