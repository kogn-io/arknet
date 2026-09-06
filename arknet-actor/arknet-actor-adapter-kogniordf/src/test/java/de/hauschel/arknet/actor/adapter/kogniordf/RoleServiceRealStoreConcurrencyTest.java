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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.persistence.testsupport.GuardSyncTx;
import de.hauschel.arknet.persistence.testsupport.GuardedLifecycle;

/**
 * Regression tests against a real RDF4J-backed store (on-disk {@code NativeStore}) - mirrors
 * {@code ActorServiceRealStoreConcurrencyTest} exactly, both races included, ported to
 * {@link RoleService}. See that class's own javadoc for the full rationale (why the on-disk sail,
 * how each race is forced deterministically, the project-wide test timeout).
 */
class RoleServiceRealStoreConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");
    private static final String LANGUAGE = "en";

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
        long testStartNanos = System.nanoTime();
        List<String> timeline = new CopyOnWriteArrayList<>();

        CyclicBarrier bothGuardsChecked = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        RoleService winnerService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, proceeding to commit");
        });
        RoleService loserService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, awaiting winner's commit");
            awaitLatch(winnerCommitted);
            logEvent(timeline, testStartNanos, "latch released, proceeding to commit");
        });

        AtomicReference<RoleDetail> winnerResult = new AtomicReference<>();
        AtomicReference<RoleDetail> loserResult = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            logEvent(timeline, testStartNanos, "started");
            try {
                RoleDetail result = winnerService.add(WS, newRole("Winner"), LANGUAGE);
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
                RoleDetail result = loserService.add(WS, newRole("Loser"), LANGUAGE);
                loserResult.set(result);
                logEvent(timeline, testStartNanos, "commit succeeded, " + describe(result));
            } catch (RuntimeException e) {
                loserFailure.set(e);
                logEvent(timeline, testStartNanos,
                        "commit failed: " + e.getClass().getName() + ": " + e.getMessage());
            }
        }, "racer-B");

        winnerThread.start();
        loserThread.start();
        winnerThread.join();
        loserThread.join();

        Supplier<String> diagnostics =
                () -> diagnosticReport(timeline, winnerResult.get(), loserResult.get(), loserFailure.get());
        assertNull(loserFailure.get(), diagnostics);
        assertNotNull(winnerResult.get(), diagnostics);
        assertNotNull(loserResult.get(), diagnostics);
        assertNotEquals(winnerResult.get().role().code(), loserResult.get().role().code(), diagnostics);

        WriteFunnel funnel = KognioRdfActorRepositoryFactory.buildFunnel(realLifecycle, DisplayLocale.DEFAULT);
        List<de.hauschel.arknet.actor.domain.Role> stored =
                KognioRdfRoleRepositoryFactory.over(realLifecycle, DisplayLocale.DEFAULT, funnel).findAll(WS, LANGUAGE);
        assertEquals(2, stored.size(), diagnostics);
        assertTrue(stored.stream().map(de.hauschel.arknet.actor.domain.Role::code).toList()
                .containsAll(List.of(winnerResult.get().role().code(), loserResult.get().role().code())),
                diagnostics);
    }

    /**
     * Lost-update guard against the real store: a concurrent {@code role_update} that commits
     * between this caller's read and its own write must cost the caller nothing and lose neither
     * correction - mirrors {@code ActorServiceRealStoreConcurrencyTest
     * #updateRetriesAndKeepsBothCorrectionsWhenAConcurrentWriterAdvancedTheHead} exactly.
     */
    @Test
    void updateRetriesAndKeepsBothCorrectionsWhenAConcurrentWriterAdvancedTheHead() {
        RoleService straightThrough = serviceOver(realLifecycle);
        RoleCode code = straightThrough.add(WS, newRole("Requirements Engineer"), LANGUAGE).role().code();

        AtomicBoolean pending = new AtomicBoolean(true);
        RoleService racing = serviceOver(new GuardedLifecycle(realLifecycle, tx -> tx, () -> {
            if (pending.compareAndSet(true, false)) {
                straightThrough.update(WS, code, null, "Beschreibung des anderen Aufrufers.", null, LANGUAGE,
                        LANGUAGE);
            }
        }));

        RoleDetail result = racing.update(WS, code, "Senior Requirements Engineer", null, null, LANGUAGE, LANGUAGE);

        assertFalse(pending.get(), "the concurrent writer must have committed - nothing was raced otherwise");
        assertEquals("Senior Requirements Engineer", result.role().name());
        assertEquals("Beschreibung des anderen Aufrufers.", result.role().description(),
                "the retry must build on the state it re-read, not on its stale first read");
        RoleDetail stored = straightThrough.get(WS, code, LANGUAGE).orElseThrow();
        assertEquals("Senior Requirements Engineer", stored.role().name());
        assertEquals("Beschreibung des anderen Aufrufers.", stored.role().description(),
                "both writers' corrections must survive - neither is silently lost");
    }

    private static NewRole newRole(String name) {
        return new NewRole(name, "Writes and maintains requirements.", null, LANGUAGE);
    }

    private static RoleService serviceOver(DatasetLifecycle lifecycle) {
        WriteFunnel funnel = KognioRdfActorRepositoryFactory.buildFunnel(lifecycle, DisplayLocale.DEFAULT);
        RoleRepository repository = KognioRdfRoleRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT, funnel);
        ActorRepository actorRepository = KognioRdfActorRepositoryFactory.over(lifecycle, funnel);
        return new RoleService(repository, actorRepository, new UuidResourceIdFactory());
    }

    /** Mirrors {@code ActorServiceRealStoreConcurrencyTest#logEvent} exactly. */
    private static void logEvent(List<String> timeline, long testStartNanos, String message) {
        timeline.add(String.format("%s @ %,d ns: %s", Thread.currentThread().getName(),
                System.nanoTime() - testStartNanos, message));
    }

    private static String diagnosticReport(List<String> timeline, RoleDetail winner, RoleDetail loser,
            Throwable failure) {
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

    private static String describe(RoleDetail detail) {
        if (detail == null) {
            return "null (no result)";
        }
        return "code=" + detail.role().code() + ", id=" + detail.role().id().value().value();
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

    private RoleService guardedService(Runnable afterSecondGuard) {
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
        return serviceOver(guarded);
    }
}
