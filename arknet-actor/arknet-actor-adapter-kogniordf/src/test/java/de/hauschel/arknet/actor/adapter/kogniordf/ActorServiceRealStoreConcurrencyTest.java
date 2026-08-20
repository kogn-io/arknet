// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.actor.application.ActorService;
import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.testsupport.GuardSyncTx;
import de.hauschel.arknet.persistence.testsupport.GuardedLifecycle;

/**
 * Regression tests against a real RDF4J-backed store (on-disk {@code NativeStore}): the second
 * interleaving of the code-assignment race with real threads, and the lost-update race through the
 * funnel's own compare-and-set path - unlike {@code ActorServiceConcurrencyTest}, which reproduces
 * the first interleaving ("a concurrent caller commits its whole write before this one's
 * transaction even begins") with a repository decorator and no real transactions at all.
 *
 * <p><strong>What this proves.</strong> Two callers can also race so that their transactions
 * genuinely <em>overlap</em>: both pass the in-transaction {@code contains} code-uniqueness guard
 * before either commits (neither sees the other's uncommitted write under {@code SERIALIZABLE}
 * isolation), and only the second commit is rejected as a conflict - by the store itself, not by
 * the guard. The shared {@link de.hauschel.arknet.persistence.WriteFunnel} translates that
 * commit-time rejection into the same
 * {@link de.hauschel.arknet.actor.domain.DuplicateActorCodeException} the synchronous guard throws,
 * so {@code CodeAssignment}'s retry (in {@link ActorService#add}) catches this interleaving exactly
 * like the first one: both callers end up with distinct codes, neither sees a failure.</p>
 *
 * <p><strong>Why the on-disk sail.</strong> Both races above are decided by the store's commit-time
 * conflict detection, and that lives in each sail rather than in a shared layer above them:
 * {@code rdf4j-sail-memory} and {@code rdf4j-sail-nativerdf} are two separate code paths. The daemon
 * runs on the {@code NativeStore}, so this store is built {@code PERSISTENT} - with the very
 * {@link DatasetStoreConfig#persistentDefault()} configuration the composition root uses - and the
 * proof holds for the sail that actually holds user data. Every other test in this module stays
 * {@code IN_MEMORY} on purpose.</p>
 *
 * <p><strong>How the overlap is forced deterministically.</strong> A {@link DatasetLifecycle}
 * decorator wraps each caller's transaction so that, right after its second {@code contains} (the
 * code-uniqueness guard the funnel issues), it blocks on a {@link CyclicBarrier} with two parties.
 * Both callers' guards must therefore have already passed before either proceeds to write, while a
 * {@link CountDownLatch} then forces the loser to wait until the winner's transaction has fully
 * committed before its own commit is attempted, so which of the two conflicts is deterministic
 * instead of a flaky race. The decorator disarms itself after firing once per caller, so
 * {@code CodeAssignment}'s retry transaction runs unsynchronised.</p>
 *
 * <p><strong>Timeout.</strong> {@link CyclicBarrier#await()}/{@link CountDownLatch#await()} block
 * indefinitely by default; a future regression that stops one caller from ever reaching its
 * barrier/latch would otherwise hang {@code join()} forever, and the build would hang instead of
 * failing. The project has no {@code junit-platform.properties}/Surefire-level timeout, so this
 * class-level {@link Timeout} is the only backstop. 60 s matches the budget
 * {@code BoundedContextServiceRealStoreConcurrencyTest} settled on after measuring the same
 * interleaving under a full parallel reactor build.</p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ActorServiceRealStoreConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");

    /**
     * The {@code NativeStore}'s on-disk home. Managed by JUnit rather than
     * {@code Files.createTempDirectory}, which left its directories behind - a persistent store
     * fills them. JUnit deletes this one after {@link #tearDown()} has shut the store down.
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
        long testStartNanos = System.nanoTime();
        List<String> timeline = new CopyOnWriteArrayList<>();

        CyclicBarrier bothGuardsChecked = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        ActorService winnerService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, proceeding to commit");
        });
        ActorService loserService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, awaiting winner's commit");
            awaitLatch(winnerCommitted);
            logEvent(timeline, testStartNanos, "latch released, proceeding to commit");
        });

        AtomicReference<Actor> winnerResult = new AtomicReference<>();
        AtomicReference<Actor> loserResult = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            logEvent(timeline, testStartNanos, "started");
            try {
                Actor result = winnerService.add(WS, newActor("Winner"));
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
                Actor result = loserService.add(WS, newActor("Loser"));
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
        // retry inside ActorService#add recovered it with a freshly recomputed code; no
        // caller-visible failure, and both actors persisted under distinct codes.
        Supplier<String> diagnostics =
                () -> diagnosticReport(timeline, winnerResult.get(), loserResult.get(), loserFailure.get());
        assertNull(loserFailure.get(), diagnostics);
        assertNotNull(winnerResult.get(), diagnostics);
        assertNotNull(loserResult.get(), diagnostics);
        assertNotEquals(winnerResult.get().code(), loserResult.get().code(), diagnostics);

        List<Actor> stored =
                KognioRdfActorRepositoryFactory.over(realLifecycle, DisplayLocale.DEFAULT).findAll(WS);
        assertEquals(2, stored.size(), diagnostics);
        assertTrue(stored.stream().map(Actor::code).toList()
                .containsAll(List.of(winnerResult.get().code(), loserResult.get().code())), diagnostics);
    }

    /**
     * Lost-update guard against the real store: a concurrent {@code actor_update} that commits
     * between this caller's read (state plus {@code arkprov:head}) and its own write must cost the
     * caller nothing and lose neither correction. Without the compare-and-set guard, the second
     * writer would silently drop the first writer's field.
     *
     * <p>The interleaving is pinned by the {@code beforeTransaction} hook - which fires exactly
     * where the funnel's compare-and-set transaction opens - rather than by real threads, which
     * would make this flaky. The one-shot guard lives in the injected {@link Runnable}, so the
     * retried attempt runs unimpeded.</p>
     */
    @Test
    void updateRetriesAndKeepsBothCorrectionsWhenAConcurrentWriterAdvancedTheHead() {
        ActorService straightThrough = serviceOver(realLifecycle);
        ActorCode code = straightThrough.add(WS, newActor("Sachbearbeiter")).code();

        AtomicBoolean pending = new AtomicBoolean(true);
        ActorService racing = serviceOver(new GuardedLifecycle(realLifecycle, tx -> tx, () -> {
            if (pending.compareAndSet(true, false)) {
                straightThrough.update(WS, code, null, "Beschreibung des anderen Aufrufers.");
            }
        }));

        Actor result = racing.update(WS, code, "Antragsbearbeiter", null);

        assertFalse(pending.get(), "the concurrent writer must have committed - nothing was raced otherwise");
        assertEquals("Antragsbearbeiter", result.name());
        assertEquals("Beschreibung des anderen Aufrufers.", result.description(),
                "the retry must build on the state it re-read, not on its stale first read");
        Actor stored = straightThrough.get(WS, code).orElseThrow();
        assertEquals("Antragsbearbeiter", stored.name());
        assertEquals("Beschreibung des anderen Aufrufers.", stored.description(),
                "both writers' corrections must survive - neither is silently lost");
    }

    private static NewActor newActor(String name) {
        return new NewActor(ActorType.HUMAN, name, "Bearbeitet eingehende Antraege im Backoffice.");
    }

    private static ActorService serviceOver(DatasetLifecycle lifecycle) {
        return new ActorService(
                KognioRdfActorRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                new UuidResourceIdFactory());
    }

    /**
     * Appends one timestamped event to {@code timeline}, tagged with the calling thread's name (the
     * guard callback and the commit call both run on the racer thread itself, so this alone
     * distinguishes {@code racer-A} from {@code racer-B}). The report built from this timeline is
     * only rendered on assertion failure, per {@link #diagnosticReport}.
     */
    private static void logEvent(List<String> timeline, long testStartNanos, String message) {
        timeline.add(String.format("%s @ %,d ns: %s", Thread.currentThread().getName(),
                System.nanoTime() - testStartNanos, message));
    }

    /**
     * Renders both racers' results and the full timestamped timeline of guard/barrier/latch/commit
     * events, so a random sighting under load is evaluable instead of merely confirming the symptom.
     * Built lazily by an assertion's message {@link Supplier}, so it costs nothing when the race
     * resolves as expected, and never throws - it runs inside an already-failing assertion, where a
     * second exception would replace the message it exists to carry.
     */
    private static String diagnosticReport(List<String> timeline, Actor winner, Actor loser, Throwable failure) {
        StringBuilder report = new StringBuilder();
        try {
            report.append("concurrency race diagnostics").append(System.lineSeparator());
            report.append("  racer-A (winner) result: ").append(describe(winner)).append(System.lineSeparator());
            report.append("  racer-B (loser) result: ").append(describe(loser)).append(System.lineSeparator());
            report.append("  racer-B (loser) failure: ").append(failure == null
                    ? "none" : failure.getClass().getName() + ": " + failure.getMessage())
                    .append(System.lineSeparator());
            report.append("  timeline:").append(System.lineSeparator());
            timeline.forEach(event -> report.append("    ").append(event).append(System.lineSeparator()));
        } catch (Throwable t) {
            report.append("  [diagnostic report itself failed: ").append(t.getClass().getName())
                    .append(": ").append(t.getMessage()).append(']').append(System.lineSeparator());
        }
        return report.toString();
    }

    private static String describe(Actor actor) {
        if (actor == null) {
            return "null (no result)";
        }
        return "code=" + actor.code() + ", id=" + actor.id().value().value();
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

    private ActorService guardedService(Runnable afterSecondGuard) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new GuardSyncTx(tx, afterSecondGuard);
            }
            return tx;
        }, () -> {
            // This service pins the code-assignment interleaving inside the transaction;
            // nothing to do before.
        });
        ActorRepository repository = KognioRdfActorRepositoryFactory.over(guarded, DisplayLocale.DEFAULT);
        return new ActorService(repository, new UuidResourceIdFactory());
    }
}
