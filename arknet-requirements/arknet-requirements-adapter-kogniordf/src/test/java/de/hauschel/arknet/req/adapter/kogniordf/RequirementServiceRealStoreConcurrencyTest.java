// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.IRI;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.testsupport.GuardSyncTx;
import de.hauschel.arknet.persistence.testsupport.GuardedLifecycle;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.application.port.out.TermLookup;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Regression test for the second interleaving of the code-assignment race, reproduced against a
 * real RDF4J-backed store (on-disk {@code NativeStore}) with real threads - unlike {@code
 * RequirementServiceConcurrencyTest}, which reproduces the first interleaving ("a concurrent
 * caller commits its whole write before this one's transaction even begins") with a repository
 * decorator and no real transactions at all.
 *
 * <p>Mirrors {@code BoundedContextServiceRealStoreConcurrencyTest} exactly: the shared
 * {@link de.hauschel.arknet.persistence.WriteFunnel} translates a genuine store-level commit
 * conflict (two callers' code-uniqueness guards both passing before either commits, under {@code
 * SERIALIZABLE} isolation, kogn-io/rdf-core#18) into the same {@link
 * de.hauschel.arknet.req.domain.DuplicateRequirementCodeException} the synchronous guard throws,
 * so {@code CodeAssignment}'s retry (in {@link RequirementService#add}) catches this interleaving
 * exactly like the first one: both callers end up with distinct codes, neither sees a failure.</p>
 *
 * <p>That includes the sail: the store is built {@code PERSISTENT}, the one the daemon runs on.
 * Commit-time conflict detection belongs to each sail, so an {@code IN_MEMORY} run would prove the
 * invariant for a store that holds no user data in production;
 * {@code BoundedContextServiceRealStoreConcurrencyTest} spells the reasoning out.</p>
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

    private static final ProjectId WS = new ProjectId("test-project");
    /** Unused by this race: neither caller links a term. */
    private static final TermLookup UNUSED_TERM_LOOKUP = (projectId, termCode) -> {
        throw new UnsupportedOperationException("not exercised by this test");
    };
    /** Unused by this race: {@code req_schema} is orthogonal to code assignment. */
    private static final RequirementSchemaSource UNUSED_SCHEMA_SOURCE = List::of;
    /** Unused by this race: neither caller links or resolves a constraint. */
    private static final ConstraintRepository UNUSED_CONSTRAINT_REPOSITORY = new ConstraintRepository() {
        @Override
        public void create(ProjectId projectId, Constraint constraint, String language) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Optional<Constraint> findByCode(ProjectId projectId, ConstraintCode code, String displayLocale) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public List<Constraint> findAll(ProjectId projectId, String displayLocale) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public List<ConstraintCode> findAllCodes(ProjectId projectId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Constraint updated,
                String titleLanguage, String statementLanguage, String defaultLanguage) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Optional<CurrentConstraint> findCurrentByCode(ProjectId projectId, ConstraintCode code) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public List<ResolveConstraints.ResolvedConstraint> findByIds(ProjectId projectId, List<ResourceId> ids) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    };

    /**
     * The {@code NativeStore}'s on-disk home, managed by JUnit rather than by
     * {@code Files.createTempDirectory}, which left its directories behind - empty and harmless
     * while the store was in-memory, but a persistent store fills them. Deleted after
     * {@link #tearDown()} has shut the store down.
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

        RequirementService winnerService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, proceeding to commit");
        });
        RequirementService loserService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, awaiting winner's commit");
            awaitLatch(winnerCommitted);
            logEvent(timeline, testStartNanos, "latch released, proceeding to commit");
        });

        AtomicReference<Requirement> winnerResult = new AtomicReference<>();
        AtomicReference<Requirement> loserResult = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            logEvent(timeline, testStartNanos, "started");
            try {
                Requirement result = winnerService.add(WS, newFunctionalRequirement(), "en");
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
                Requirement result = loserService.add(WS, newFunctionalRequirement(), "en");
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
        // retry inside RequirementService#add recovered it with a freshly recomputed code; no
        // caller-visible failure, and both requirements persisted under distinct codes.
        Supplier<String> diagnostics =
                () -> diagnosticReport(timeline, winnerResult.get(), loserResult.get(), loserFailure.get());
        assertNull(loserFailure.get(), diagnostics);
        assertNotNull(winnerResult.get(), diagnostics);
        assertNotNull(loserResult.get(), diagnostics);
        assertNotEquals(winnerResult.get().code(), loserResult.get().code(), diagnostics);

        List<Requirement> stored =
                KognioRdfRequirementRepositoryFactory.over(realLifecycle, DisplayLocale.DEFAULT).findAll(WS, null);
        assertEquals(2, stored.size(), diagnostics);
        assertTrue(stored.stream().map(Requirement::code).toList()
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
     * Renders everything a future random sighting of this race needs to be evaluable with: both
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
     * diagnostic instead of carrying the race's own signature. Everything already appended to {@code report}
     * survives a failure below it; {@link #headOf} additionally never throws on its own.</p>
     */
    private String diagnosticReport(List<String> timeline, Requirement winner, Requirement loser,
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
     * moment the report is built - both real sightings of this race were load-dependent, and
     * without this line the load has to be reconstructed after the fact from unrelated sources.
     */
    private static String systemDiagnostics() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        double loadAverage = osBean.getSystemLoadAverage();
        String loadAverageText = loadAverage < 0 ? "n/a" : String.format("%.2f", loadAverage);
        return "availableProcessors=" + Runtime.getRuntime().availableProcessors()
                + ", systemLoadAverage=" + loadAverageText
                + ", threadInterrupted=" + Thread.currentThread().isInterrupted();
    }

    private static String describe(Requirement requirement) {
        if (requirement == null) {
            return "null (no result)";
        }
        return "code=" + requirement.code() + ", id=" + requirement.id().value().value();
    }

    /**
     * Reads {@code requirement}'s current {@code arkprov:head} (ADR-014) straight from
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
    private String headOf(Requirement requirement) {
        if (requirement == null) {
            return "n/a (no result)";
        }
        if (Thread.currentThread().isInterrupted()) {
            return "skipped (thread interrupted before this read)";
        }
        String subjectIriString = requirement.id().value().value();
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

    private static NewRequirement newFunctionalRequirement() {
        return new NewRequirement("User can log in", "The system shall let a registered user authenticate.", null,
                RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null);
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

    private RequirementService guardedService(Runnable afterSecondGuard) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new GuardSyncTx(tx, afterSecondGuard);
            }
            return tx;
        });
        RequirementRepository repository = KognioRdfRequirementRepositoryFactory.over(guarded, DisplayLocale.DEFAULT);
        return new RequirementService(repository, new UuidResourceIdFactory(), UNUSED_TERM_LOOKUP,
                UNUSED_CONSTRAINT_REPOSITORY, UNUSED_SCHEMA_SOURCE);
    }
}
