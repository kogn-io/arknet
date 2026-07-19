package de.hauschel.arknet.req.adapter.kogniordf;

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

import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.application.port.out.TermLookup;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Regression test for the second interleaving of issue #144, reproduced against a real
 * RDF4J-backed store (in-memory {@code SailRepository}) with real threads - unlike {@code
 * RequirementServiceConcurrencyTest}, which reproduces the first interleaving ("a concurrent
 * caller commits its whole write before this one's transaction even begins") with a repository
 * decorator and no real transactions at all.
 *
 * <p>Mirrors {@code BoundedContextServiceRealStoreConcurrencyTest} exactly: {@link
 * KognioRdfRequirementRepository#isWriteConflict} translates a genuine store-level commit
 * conflict (two callers' code-uniqueness guards both passing before either commits, under {@code
 * SERIALIZABLE} isolation, kogn-io/rdf-core#18) into the same {@link
 * de.hauschel.arknet.req.domain.DuplicateRequirementCodeException} the synchronous guard throws,
 * so {@code CodeAssignment}'s retry (in {@link RequirementService#add}) catches this interleaving
 * exactly like the first one: both callers end up with distinct codes, neither sees a failure.</p>
 *
 * <p><strong>Timeout.</strong> {@link CyclicBarrier#await()}/{@link CountDownLatch#await()} block
 * indefinitely by default; a future regression that stops one caller from ever reaching its
 * barrier/latch would otherwise hang {@code join()} forever, so neither {@code @AfterEach} nor
 * {@code shutDownAll()} would ever run - the build would hang instead of failing. The project has
 * no {@code junit-platform.properties}/Surefire-level timeout, so this class-level {@link Timeout}
 * is the only backstop; the interleaving itself normally resolves in well under a second.</p>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RequirementServiceRealStoreConcurrencyTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;
    /** Unused by this race: neither caller links a term. */
    private static final TermLookup UNUSED_TERM_LOOKUP = (workspaceId, termCode) -> {
        throw new UnsupportedOperationException("not exercised by this test");
    };
    /** Unused by this race: {@code req_schema} is orthogonal to code assignment. */
    private static final RequirementSchemaSource UNUSED_SCHEMA_SOURCE = List::of;

    private DatasetLifecycleRdf4j realLifecycle;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-req-real-race");
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

        RequirementService winnerService = guardedService(() -> awaitBarrier(bothGuardsChecked));
        RequirementService loserService = guardedService(() -> {
            awaitBarrier(bothGuardsChecked);
            awaitLatch(winnerCommitted);
        });

        AtomicReference<Requirement> winnerResult = new AtomicReference<>();
        AtomicReference<Requirement> loserResult = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            try {
                winnerResult.set(winnerService.add(WS, newFunctionalRequirement()));
            } finally {
                winnerCommitted.countDown();
            }
        });
        Thread loserThread = new Thread(() -> {
            try {
                loserResult.set(loserService.add(WS, newFunctionalRequirement()));
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
        // retry inside RequirementService#add recovered it with a freshly recomputed code; no
        // caller-visible failure, and both requirements persisted under distinct codes.
        assertNull(loserFailure.get(), "the retry must absorb the store's commit-time conflict");
        assertNotNull(winnerResult.get());
        assertNotNull(loserResult.get());
        assertNotEquals(winnerResult.get().code(), loserResult.get().code());

        List<Requirement> stored = KognioRdfRequirementRepositoryFactory.over(realLifecycle).findAll(WS);
        assertEquals(2, stored.size());
        assertTrue(stored.stream().map(Requirement::code).toList()
                .containsAll(List.of(winnerResult.get().code(), loserResult.get().code())));
    }

    private static NewRequirement newFunctionalRequirement() {
        return new NewRequirement("User can log in", "The system shall let a registered user authenticate.",
                RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"));
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

    private RequirementService guardedService(Runnable afterSecondAsk) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new AskGuardSyncTx(tx, afterSecondAsk);
            }
            return tx;
        });
        RequirementRepository repository = KognioRdfRequirementRepositoryFactory.over(guarded);
        return new RequirementService(repository, new UuidResourceIdFactory(), UNUSED_TERM_LOOKUP,
                UNUSED_SCHEMA_SOURCE);
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
     * {@link KognioRdfRequirementRepository#write} issues exactly two on the create path: the
     * identity guard, then the code-uniqueness guard.
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
