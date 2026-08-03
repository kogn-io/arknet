// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;

/**
 * Regression tests against a real RDF4J-backed store (on-disk {@code NativeStore}): the
 * second interleaving of the code-assignment race with real threads, and the lost-update race
 * through the funnel's own compare-and-set path - unlike {@code
 * BoundedContextServiceConcurrencyTest}, which reproduces the first interleaving ("a concurrent
 * caller commits its whole write before this one's transaction even begins") with a repository
 * decorator and no real transactions at all.
 *
 * <p><strong>What this proves.</strong> Two callers can also race so that their transactions
 * genuinely <em>overlap</em>: both pass the in-transaction {@code contains} code-uniqueness guard
 * before either commits (neither sees the other's uncommitted write under {@code SERIALIZABLE}
 * isolation, kogn-io/rdf-core#18), and only the second commit is rejected as a conflict - by the
 * store itself, not by the guard. The shared {@link de.hauschel.arknet.persistence.WriteFunnel}
 * translates that commit-time rejection into the same {@link
 * de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException} the synchronous guard
 * throws, so {@code CodeAssignment}'s retry (in {@link BoundedContextService#add}) catches this
 * interleaving exactly like the first one: both callers end up with distinct codes, neither sees
 * a failure.</p>
 *
 * <p><strong>Why the on-disk sail.</strong> Both races above are decided by the store's
 * commit-time conflict detection, and that lives in each sail rather than in a shared layer above
 * them: {@code rdf4j-sail-memory} and {@code rdf4j-sail-nativerdf} are two separate code paths.
 * The daemon runs on the {@code NativeStore}, so this store is built {@code PERSISTENT} - with the
 * very {@link DatasetStoreConfig#persistentDefault()} configuration the composition root uses -
 * and the proof holds for the sail that actually holds user data. Every other test in
 * this module stays {@code IN_MEMORY} on purpose: what they assert sits above the store, where the
 * faster sail is the legitimate choice and no coverage is lost.</p>
 *
 * <p><strong>How the overlap is forced deterministically.</strong> Mirrors the pattern kogn-io/
 * rdf-core's own {@code DatasetRdf4jTest#inTransaction_overlappingContainsGuardedWrites_
 * whenGuardIrisUnknownToStore_loserCommitFails} test uses to prove the store-level mechanism, one
 * layer up: a {@link DatasetLifecycle} decorator wraps each caller's {@link DatasetTx} so that,
 * right after its second {@code contains} (the code-uniqueness guard
 * {@link KognioRdfBoundedContextRepository#create} issues), it blocks on a {@link CyclicBarrier}
 * with two parties. Both callers' guards must therefore have already passed before either proceeds
 * to write - the exact guard-defeat scenario, which under a SPARQL {@code ASK} guard on
 * store-unknown IRIs would not even be caught at commit time (ADR-013 Nachtrag) - while a {@link
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
 * is the only backstop; the interleaving itself normally resolves in well under a second.
 * Uncontended, both methods together finish in about 4 s. The budget was originally 10 s, sized
 * while this class ran against {@code sail-memory} - moving it onto the on-disk
 * {@code NativeStore}, whose commit path serialises writers through
 * {@link org.eclipse.rdf4j.common.concurrent.locks.ExclusiveReentrantLockManager}, and under a full
 * parallel reactor build {@code linkTermRetriesAndKeepsBothEdgesWhenAConcurrentWriterAdvancedTheHead}
 * measured up to 13.44 s, occasionally tripping the 10 s budget. Reproduced locally by saturating
 * every core with busy-loops during a run: the method finished in 10.6-23 s across repeated runs
 * (bounded, not growing further under repeated saturation) - consistent with CPU-starved I/O, not
 * with a stuck lock, since {@code ExclusiveReentrantLockManager} would otherwise hold this class's
 * only writer waiting on nothing (the two test methods each open their own store, so neither
 * contends with the other). 60 s leaves several times that headroom while still catching a genuine
 * hang (see above) long before a human would notice the build stall.</p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BoundedContextServiceRealStoreConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");
    private static final ResourceId TERM_1 = ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final ResourceId TERM_2 = ResourceId.of("https://w3id.org/arknet/id/term-2");

    /**
     * The {@code NativeStore}'s on-disk home. Managed by JUnit rather than by
     * {@code Files.createTempDirectory}, which left its directories behind: harmless while the
     * store was in-memory and the directories stayed empty, but a persistent store fills them
     * (some 21 MB per 400 runs). JUnit deletes this one after {@link #tearDown()} has shut the
     * store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j realLifecycle;

    @BeforeEach
    void setUp() {
        realLifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
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
        // Diagnostics: two unreproducible sightings of this assertion failing under
        // full parallel-build load left nothing to go on beyond "both got the same code" - this test
        // now also records a nanoTime-stamped timeline of both racers plus each result's arkprov:head
        // (ADR-014), so that the next sighting is evaluable instead of merely confirming the symptom.
        long testStartNanos = System.nanoTime();
        List<String> timeline = new CopyOnWriteArrayList<>();

        CyclicBarrier bothGuardsChecked = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        BoundedContextService winnerService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, proceeding to commit");
        });
        BoundedContextService loserService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, awaiting winner's commit");
            awaitLatch(winnerCommitted);
            logEvent(timeline, testStartNanos, "latch released, proceeding to commit");
        });

        AtomicReference<BoundedContext> winnerResult = new AtomicReference<>();
        AtomicReference<BoundedContext> loserResult = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            logEvent(timeline, testStartNanos, "started");
            try {
                BoundedContext result = winnerService.add(WS, newBoundedContext("Winner"));
                winnerResult.set(result);
                logEvent(timeline, testStartNanos, "commit succeeded, " + describe(result));
            } finally {
                winnerCommitted.countDown();
                logEvent(timeline, testStartNanos, "counted down latch, releasing loser's commit");
            }
        }, "racer-A");
        Thread loserThread = new Thread(() -> {
            logEvent(timeline, testStartNanos, "started");
            try {
                BoundedContext result = loserService.add(WS, newBoundedContext("Loser"));
                loserResult.set(result);
                logEvent(timeline, testStartNanos, "commit succeeded, " + describe(result));
            } catch (RuntimeException e) {
                loserFailure.set(e);
                logEvent(timeline, testStartNanos,
                        "commit failed: " + e.getClass().getName() + ": " + e.getMessage());
            }
        }, "racer-B");

        // when
        winnerThread.start();
        loserThread.start();
        winnerThread.join();
        loserThread.join();

        // then - the loser's first commit lost the real store-level conflict, but CodeAssignment's
        // retry inside BoundedContextService#add recovered it with a freshly recomputed code; no
        // caller-visible failure, and both bounded contexts persisted under distinct codes.
        Supplier<String> diagnostics =
                () -> diagnosticReport(timeline, winnerResult.get(), loserResult.get(), loserFailure.get());
        assertNull(loserFailure.get(), diagnostics);
        assertNotNull(winnerResult.get(), diagnostics);
        assertNotNull(loserResult.get(), diagnostics);
        assertNotEquals(winnerResult.get().code(), loserResult.get().code(), diagnostics);

        List<BoundedContext> stored =
                KognioRdfBoundedContextRepositoryFactory.over(realLifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT).findAll(WS);
        assertEquals(2, stored.size(), diagnostics);
        assertTrue(stored.stream().map(BoundedContext::code).toList()
                .containsAll(List.of(winnerResult.get().code(), loserResult.get().code())), diagnostics);
    }

    /**
     * Appends one timestamped event to {@code timeline}, tagged with the calling thread's name
     * (the guard callback and the commit call both run on the racer thread itself, so this alone
     * distinguishes {@code racer-A} from {@code racer-B} without an explicit parameter). Costs one
     * list append and a {@code nanoTime} call regardless of test outcome - the report built from
     * this timeline is only rendered on assertion failure, per {@link #diagnosticReport}.
     */
    private static void logEvent(List<String> timeline, long testStartNanos, String message) {
        timeline.add(String.format("%s @ %,d ns: %s", Thread.currentThread().getName(),
                System.nanoTime() - testStartNanos, message));
    }

    /**
     * Renders everything a past random sighting asked the next one to be evaluable with: both
     * racers' results (business code plus resource IRI), each result's current
     * {@code arkprov:head} read fresh from the store after the race (ADR-014's concurrency token -
     * shows whether the two results really are two distinct, independently committed revisions),
     * the loser's exception if any, the system load at failure time, and the full timestamped
     * timeline of guard/barrier/latch/commit events. Built lazily by an assertion's message
     * {@link Supplier}, so it costs nothing when the race resolves as expected.
     *
     * <p><strong>Must never throw.</strong> This runs inside an already-failing assertion; a
     * second exception from the diagnostics themselves (the store in a bad state after the race,
     * the lifecycle already shut down, a lock held, a timeout interrupt) would replace the
     * assertion's actual message and leave the next sighting with nothing evaluable again - worse
     * than before this class was instrumented, because the failure would then look like a broken
     * diagnostic instead of carrying the original signature. Everything already appended to {@code report}
     * survives a failure below it; {@link #headOf} additionally never throws on its own.</p>
     */
    private String diagnosticReport(List<String> timeline, BoundedContext winner, BoundedContext loser,
            Throwable failure) {
        StringBuilder report = new StringBuilder();
        try {
            report.append("concurrency race diagnostics").append(System.lineSeparator());
            report.append("  system: ").append(systemDiagnostics()).append(System.lineSeparator());
            report.append("  racer-A (winner) result: ").append(describe(winner)).append(System.lineSeparator());
            report.append("  racer-A (winner) arkprov:head: ").append(headOf(winner)).append(System.lineSeparator());
            report.append("  racer-B (loser) result: ").append(describe(loser)).append(System.lineSeparator());
            report.append("  racer-B (loser) arkprov:head: ").append(headOf(loser)).append(System.lineSeparator());
            report.append("  racer-B (loser) failure: ").append(failure == null
                    ? "none" : failure.getClass().getName() + ": " + failure.getMessage())
                    .append(System.lineSeparator());
            report.append("  timeline:").append(System.lineSeparator());
            timeline.forEach(event -> report.append("    ").append(event).append(System.lineSeparator()));
        } catch (Throwable t) {
            report.append("  [diagnostic report itself failed: ").append(t.getClass().getName())
                    .append(": ").append(t.getMessage()).append(']').append(System.lineSeparator());
            report.append("  timeline so far:").append(System.lineSeparator());
            timeline.forEach(event -> report.append("    ").append(event).append(System.lineSeparator()));
        }
        return report.toString();
    }

    /**
     * Available processors, {@code systemLoadAverage} and this thread's interrupt status at the
     * moment the report is built - both real past sightings were load-dependent, and without this
     * line the load has to be reconstructed after the fact from unrelated sources.
     */
    private static String systemDiagnostics() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        double loadAverage = osBean.getSystemLoadAverage();
        String loadAverageText = loadAverage < 0 ? "n/a" : String.format("%.2f", loadAverage);
        return "availableProcessors=" + Runtime.getRuntime().availableProcessors()
                + ", systemLoadAverage=" + loadAverageText
                + ", threadInterrupted=" + Thread.currentThread().isInterrupted();
    }

    private static String describe(BoundedContext boundedContext) {
        if (boundedContext == null) {
            return "null (no result)";
        }
        return "code=" + boundedContext.code() + ", id=" + boundedContext.id().value().value();
    }

    /**
     * Reads {@code boundedContext}'s current {@code arkprov:head} (ADR-014) straight from
     * {@link #realLifecycle} after the race, outside any transaction - the same triple pattern
     * {@link de.hauschel.arknet.persistence.WriteFunnel#compareAndUpdate} reads inside its
     * transaction, but there is no accessor for that private read path, so this queries it directly
     * via {@link io.kogn.rdf.dataset.SparqlQuery}.
     *
     * <p>Never throws: a {@code @Timeout} interrupt landing mid-race can
     * leave the sail in a bad state for a follow-up read, so both the dataset acquisition and the
     * query run inside one {@code try}/{@code catch(Throwable)} - a failure here becomes part of
     * the diagnostic text instead of replacing it. The interrupt status is recorded rather than
     * silently dropped, since it is itself a diagnostic signal (a timed-out racer thread reading
     * its own head after having been interrupted).</p>
     */
    private String headOf(BoundedContext boundedContext) {
        if (boundedContext == null) {
            return "n/a (no result)";
        }
        if (Thread.currentThread().isInterrupted()) {
            return "skipped (thread interrupted before this read)";
        }
        String subjectIriString = boundedContext.id().value().value();
        String query = "SELECT ?head WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + subjectIriString + "> <" + ArkprovVocabulary.HEAD + "> ?head } }";
        try (DatasetHandle handle = realLifecycle.acquire(new DatasetId(WS.value()))) {
            Optional<String> head = handle.sparqlQuery().select(query)
                    .findFirst()
                    .flatMap(row -> row.getValue("head"))
                    .filter(IRI.class::isInstance)
                    .map(value -> ((IRI) value).getIRIString());
            return head.orElse("none (no revision recorded through the funnel)");
        } catch (Throwable t) {
            String interruptNote = Thread.currentThread().isInterrupted() ? ", thread interrupted" : "";
            return "unavailable: " + t.getClass().getName() + ": " + t.getMessage() + interruptNote;
        }
    }

    /**
     * Lost-update guard against the real store: a concurrent {@code bc_link_term} that commits between
     * this caller's read (state plus {@code arkprov:head}) and its own write must cost the caller
     * nothing and lose neither edge. Before the fix, {@code linkTerm} read outside any transaction
     * and wrote back unconditionally, so the second writer silently dropped the first writer's
     * {@code arkddd:ubiquitousLanguageTerm} edge.
     *
     * <p>The interleaving is pinned by the {@code beforeTransaction} hook - which fires exactly
     * where the funnel's compare-and-set transaction opens - rather than by real threads, which
     * would make this flaky. The one-shot guard lives in the injected {@link Runnable}, so the
     * retried attempt runs unimpeded.</p>
     */
    @Test
    void linkTermRetriesAndKeepsBothEdgesWhenAConcurrentWriterAdvancedTheHead() {
        BoundedContextService straightThrough = serviceOver(realLifecycle);
        BoundedContextCode code = straightThrough.add(WS, newBoundedContext("orders-team")).code();

        AtomicBoolean pending = new AtomicBoolean(true);
        BoundedContextService racing = serviceOver(new GuardedLifecycle(realLifecycle, tx -> tx, () -> {
            if (pending.compareAndSet(true, false)) {
                straightThrough.linkTerm(WS, code, "TERM-2");
            }
        }));

        BoundedContext result = racing.linkTerm(WS, code, "TERM-1");

        assertFalse(pending.get(), "the concurrent writer must have committed - nothing was raced otherwise");
        assertEquals(2, result.usesTerms().size(),
                "the retry must return the state it re-read, not its stale first read");
        assertTrue(result.usesTerms().containsAll(List.of(new TermRef(TERM_1), new TermRef(TERM_2))));
        BoundedContext stored = straightThrough.get(WS, code).orElseThrow();
        assertEquals(2, stored.usesTerms().size(), "both writers' edges must survive - neither is silently lost");
    }

    private static NewBoundedContext newBoundedContext(String owner) {
        return new NewBoundedContext("OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, owner);
    }

    /**
     * A service wired over {@code lifecycle} with a term lookup that knows {@code TERM-1} and
     * {@code TERM-2} - the two edges the lost-update race above competes over.
     */
    private static BoundedContextService serviceOver(DatasetLifecycle lifecycle) {
        TermLookup termLookup = (projectId, termCode) -> switch (termCode) {
            case "TERM-1" -> TERM_1;
            case "TERM-2" -> TERM_2;
            default -> throw new IllegalArgumentException("fake lookup: unknown term code " + termCode);
        };
        return new BoundedContextService(
                KognioRdfBoundedContextRepositoryFactory.over(lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT),
                new UuidResourceIdFactory(), termLookup, unusedContextRelationshipRepository());
    }

    /** Neither concurrency race this class exercises reaches {@code bc_link_context}. */
    private static ContextRelationshipRepository unusedContextRelationshipRepository() {
        return (projectId, relationship) -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
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
    //      contains() guard (the code-uniqueness guard), exactly once, then gets out of the way --

    private BoundedContextService guardedService(Runnable afterSecondGuard) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new GuardSyncTx(tx, afterSecondGuard);
            }
            return tx;
        }, () -> {
            // This service pins the code-assignment interleaving inside the transaction; nothing to do before.
        });
        BoundedContextRepository repository =
                KognioRdfBoundedContextRepositoryFactory.over(guarded, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        TermLookup unusedTermLookup = (projectId, termCode) -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        return new BoundedContextService(
                repository, new UuidResourceIdFactory(), unusedTermLookup, unusedContextRelationshipRepository());
    }

    /**
     * Wraps a real {@link DatasetLifecycle}, running {@code beforeTransaction} right before every
     * write transaction opens and decorating every acquired transaction's {@link DatasetTx}. The
     * two hooks pin two different interleavings: {@code beforeTransaction} is where a concurrent
     * writer's commit turns an already-taken read stale (the lost-update race), {@code txDecorator}
     * is where two transactions are held open against each other (the code-assignment race).
     */
    private static final class GuardedLifecycle implements DatasetLifecycle {

        private final DatasetLifecycle delegate;
        private final Function<DatasetTx, DatasetTx> txDecorator;
        private final Runnable beforeTransaction;

        GuardedLifecycle(DatasetLifecycle delegate, Function<DatasetTx, DatasetTx> txDecorator,
                Runnable beforeTransaction) {
            this.delegate = delegate;
            this.txDecorator = txDecorator;
            this.beforeTransaction = beforeTransaction;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return new GuardedHandle(delegate.acquire(id), txDecorator, beforeTransaction);
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
        private final Runnable beforeTransaction;

        GuardedHandle(DatasetHandle delegate, Function<DatasetTx, DatasetTx> txDecorator,
                Runnable beforeTransaction) {
            this.delegate = delegate;
            this.txDecorator = txDecorator;
            this.beforeTransaction = beforeTransaction;
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
            DatasetTransactor real = delegate.transactor();
            return new DatasetTransactor() {
                @Override
                public <T> T inTransaction(Function<DatasetTx, T> fn) {
                    beforeTransaction.run();
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
     * Runs {@code afterSecondGuard} exactly once its delegate's second {@code contains()} call
     * returns - {@link WriteFunnel#create} (arknet-persistence-support), which
     * {@link KognioRdfBoundedContextRepository#create} delegates to, issues exactly two: the
     * identity guard, then (only reached when the identity guard passed) the code-uniqueness
     * guard.
     */
    private static final class GuardSyncTx implements DatasetTx {

        private final DatasetTx delegate;
        private final Runnable afterSecondGuard;
        private int guardCount;

        GuardSyncTx(DatasetTx delegate, Runnable afterSecondGuard) {
            this.delegate = delegate;
            this.afterSecondGuard = afterSecondGuard;
        }

        @Override
        public boolean ask(String query) {
            return delegate.ask(query);
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
            boolean result = delegate.contains(graph, subject, predicate, object);
            guardCount++;
            if (guardCount == 2) {
                afterSecondGuard.run();
            }
            return result;
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
