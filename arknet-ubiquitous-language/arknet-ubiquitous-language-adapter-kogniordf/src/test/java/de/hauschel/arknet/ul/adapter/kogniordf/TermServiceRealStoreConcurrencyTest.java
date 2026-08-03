// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

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

import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Regression test for the second interleaving of the code-assignment race, reproduced against a
 * real RDF4J-backed store (on-disk {@code NativeStore}) with real threads - unlike {@code
 * TermServiceConcurrencyTest}, which reproduces the first interleaving ("a concurrent caller
 * commits its whole write before this one's transaction even begins") with a repository decorator
 * and no real transactions at all.
 *
 * <p>Mirrors {@code BoundedContextServiceRealStoreConcurrencyTest} exactly: the shared
 * {@link de.hauschel.arknet.persistence.WriteFunnel} translates a genuine store-level commit conflict (two
 * callers' code-uniqueness guards both passing before either commits, under {@code SERIALIZABLE}
 * isolation, kogn-io/rdf-core#18) into the same {@link
 * de.hauschel.arknet.ul.domain.DuplicateTermCodeException} the synchronous guard throws, so
 * {@code CodeAssignment}'s retry (in {@link TermService#add}) catches this interleaving exactly
 * like the first one: both callers end up with distinct codes, neither sees a failure.</p>
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
class TermServiceRealStoreConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");

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
        // Diagnostics for two unreproducible sightings of this assertion failing under
        // full parallel-build load left nothing to go on beyond "both got the same code" - this test
        // now also records a nanoTime-stamped timeline of both racers plus each result's arkprov:head
        // (ADR-014), so that the next sighting is evaluable instead of merely confirming the symptom.
        long testStartNanos = System.nanoTime();
        List<String> timeline = new CopyOnWriteArrayList<>();

        CyclicBarrier bothGuardsChecked = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        TermService winnerService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, proceeding to commit");
        });
        TermService loserService = guardedService(() -> {
            logEvent(timeline, testStartNanos, "reached uniqueness guard, awaiting barrier");
            awaitBarrier(bothGuardsChecked);
            logEvent(timeline, testStartNanos, "barrier released, awaiting winner's commit");
            awaitLatch(winnerCommitted);
            logEvent(timeline, testStartNanos, "latch released, proceeding to commit");
        });

        AtomicReference<Term> winnerResult = new AtomicReference<>();
        AtomicReference<Term> loserResult = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            logEvent(timeline, testStartNanos, "started");
            try {
                Term result = winnerService.add(WS, newTerm());
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
                Term result = loserService.add(WS, newTerm());
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
        // retry inside TermService#add recovered it with a freshly recomputed code; no
        // caller-visible failure, and both terms persisted under distinct codes.
        Supplier<String> diagnostics =
                () -> diagnosticReport(timeline, winnerResult.get(), loserResult.get(), loserFailure.get());
        assertNull(loserFailure.get(), diagnostics);
        assertNotNull(winnerResult.get(), diagnostics);
        assertNotNull(loserResult.get(), diagnostics);
        assertNotEquals(winnerResult.get().code(), loserResult.get().code(), diagnostics);

        List<Term> stored = KognioRdfTermRepositoryFactory.over(realLifecycle).findAll(WS);
        assertEquals(2, stored.size(), diagnostics);
        assertTrue(stored.stream().map(Term::code).toList()
                .containsAll(List.of(winnerResult.get().code(), loserResult.get().code())), diagnostics);
    }

    /**
     * The {@code term_update} counterpart of the race above (issue #230 review): two
     * {@code term_update}-shaped calls against the very same term, both reading the same
     * {@code arkprov:head}, each correcting a <em>different</em> language variant of
     * {@code skos:prefLabel} - proof that the CAS token races on regardless, because it guards
     * the whole resource, not a single predicate/language slot ({@code
     * arknet-ubiquitous-language/CLAUDE.md}: "der Head ist pro Ressource, nicht pro
     * Praedikat"). Unlike {@code ProjectRegistry#updateAttributes}'s CAS retry (which lives one
     * layer up, in {@code ProjectService}), {@link KognioRdfTermRepository#update}'s retry loop
     * lives inside the out-adapter itself and re-reads the current state on every attempt, so it
     * absorbs the lost race transparently: neither caller ever sees {@link
     * de.hauschel.arknet.ul.domain.TermConcurrentlyModifiedException}, and the assertions below
     * pin the two failure modes a purely mocked/in-memory test cannot exercise - a lost update
     * (the loser's {@code @fr} label silently missing) and an orphaned duplicate (the original
     * {@code @de} label surviving next to its own correction, the exact defect the language-scoped
     * delete in issue #228 exists to prevent).
     */
    @Test
    void concurrentUpdatesOfDifferentLanguageVariants_bothSurviveWithoutLossOrDuplication()
            throws InterruptedException {
        TermRepository straightThrough = KognioRdfTermRepositoryFactory.over(realLifecycle);
        TermId id = new TermId(new UuidResourceIdFactory().newId());
        TermCode code = new TermCode("TERM-1");
        straightThrough.create(WS, new Term(id, code, "Kunde", "Erste Definition.", null), "de");

        CyclicBarrier bothGuardsPassed = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        TermRepository winnerRepository = guardedRepository(() -> awaitBarrier(bothGuardsPassed));
        TermRepository loserRepository = guardedRepository(() -> {
            awaitBarrier(bothGuardsPassed);
            awaitLatch(winnerCommitted);
        });

        AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            try {
                winnerRepository.update(WS, code, "Kunde (korrigiert)", null, null, "de");
            } catch (Throwable t) {
                winnerFailure.set(t);
            } finally {
                winnerCommitted.countDown();
            }
        }, "racer-A");
        Thread loserThread = new Thread(() -> {
            try {
                loserRepository.update(WS, code, "Client", null, null, "fr");
            } catch (Throwable t) {
                loserFailure.set(t);
            }
        }, "racer-B");

        winnerThread.start();
        loserThread.start();
        winnerThread.join();
        loserThread.join();

        assertNull(winnerFailure.get(), "the winner commits first and must not fail");
        assertNull(loserFailure.get(),
                "the internal retry loop (KognioRdfTermRepository#update) must absorb the lost race "
                        + "with a fresh read - no caller-visible failure");

        assertTrue(subjectHasLanguageTaggedPrefLabel(id, "Kunde (korrigiert)", "de"),
                "the winner's corrected @de label must be stored");
        assertTrue(subjectHasLanguageTaggedPrefLabel(id, "Client", "fr"),
                "the loser's new @fr label must be stored - not lost to the lost race");
        assertFalse(subjectHasLanguageTaggedPrefLabel(id, "Kunde", "de"),
                "the original @de label must have been replaced, not left standing next to its own correction");
        assertEquals(2, countPrefLabelTriples(id),
                "exactly one prefLabel per language - no accumulated duplicate from the retried write");
    }

    /** Whether the term carries a {@code skos:prefLabel} literal with exactly this value and language tag. */
    private boolean subjectHasLanguageTaggedPrefLabel(TermId id, String value, String tag) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + id.value().value() + "> <http://www.w3.org/2004/02/skos/core#prefLabel> \""
                + value + "\"@" + tag + " } }";
        try (DatasetHandle handle = realLifecycle.acquire(new DatasetId(WS.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /** How many {@code skos:prefLabel} triples the term carries in total, across every language. */
    private long countPrefLabelTriples(TermId id) {
        String query = "SELECT (COUNT(?o) AS ?n) WHERE { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> "
                + "{ <" + id.value().value() + "> <http://www.w3.org/2004/02/skos/core#prefLabel> ?o } }";
        try (DatasetHandle handle = realLifecycle.acquire(new DatasetId(WS.value()))) {
            return handle.sparqlQuery().select(query)
                    .findFirst()
                    .flatMap(row -> row.getValue("n"))
                    .filter(io.kogn.rdf.terms.Literal.class::isInstance)
                    .map(v -> Long.parseLong(((io.kogn.rdf.terms.Literal) v).getLexicalForm()))
                    .orElse(0L);
        }
    }

    /**
     * A term repository whose first write transaction runs {@code beforeFirstWrite} right before
     * its first {@code add} - after every guard of that transaction (the funnel's head check,
     * inside {@link de.hauschel.arknet.persistence.WriteFunnel#compareAndUpdate}) has passed and
     * before anything is written. Disarms itself afterwards, so {@link
     * KognioRdfTermRepository#update}'s internal retry - a second, later transaction on this very
     * same repository instance - runs unsynchronised, the same one-shot arming {@link
     * #guardedService} uses for the create path's second guard.
     */
    private TermRepository guardedRepository(Runnable beforeFirstWrite) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new PausingOnFirstAddTx(tx, beforeFirstWrite);
            }
            return tx;
        });
        return KognioRdfTermRepositoryFactory.over(guarded);
    }

    /**
     * Runs {@code beforeFirstWrite} once, immediately before its delegate's first {@link
     * GraphStore#add} call - the point in {@link KognioRdfTermRepository}'s write body where the
     * funnel's head check has already passed and no triple has been written yet. Unlike {@link
     * GuardSyncTx} (which counts {@code contains()} calls for the {@code create} path's two
     * uniqueness guards), {@code update}'s write body issues no {@code contains()} at all - its
     * only guard is the funnel's own head comparison, reached before this transaction's body ever
     * runs, so pausing on the first {@code add} is the correct, guard-count-independent hook.
     */
    private static final class PausingOnFirstAddTx implements DatasetTx {

        private final DatasetTx delegate;
        private final Runnable beforeFirstWrite;
        private boolean pending = true;

        PausingOnFirstAddTx(DatasetTx delegate, Runnable beforeFirstWrite) {
            this.delegate = delegate;
            this.beforeFirstWrite = beforeFirstWrite;
        }

        @Override
        public long add(IRI graph, ReadableGraph data) {
            if (pending) {
                pending = false;
                beforeFirstWrite.run();
            }
            return delegate.add(graph, data);
        }

        @Override
        public boolean contains(IRI graph, io.kogn.rdf.terms.BlankNodeOrIRI subject, IRI predicate,
                io.kogn.rdf.terms.RDFTerm object) {
            return delegate.contains(graph, subject, predicate, object);
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
     * Renders everything needed to make the next random sighting of this race evaluable: both
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
     * diagnostic instead of carrying its own signature. Everything already appended to {@code report}
     * survives a failure below it; {@link #headOf} additionally never throws on its own.</p>
     */
    private String diagnosticReport(List<String> timeline, Term winner, Term loser, Throwable failure) {
        StringBuilder report = new StringBuilder();
        try {
            report.append("concurrency-race diagnostics").append(System.lineSeparator());
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

    private static String describe(Term term) {
        if (term == null) {
            return "null (no result)";
        }
        return "code=" + term.code() + ", id=" + term.id().value().value();
    }

    /**
     * Reads {@code term}'s current {@code arkprov:head} (ADR-014) straight from
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
    private String headOf(Term term) {
        if (term == null) {
            return "n/a (no result)";
        }
        if (Thread.currentThread().isInterrupted()) {
            return "skipped (thread interrupted before this read)";
        }
        String subjectIriString = term.id().value().value();
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

    private static NewTerm newTerm() {
        return new NewTerm("Gutschrift", "Rueckerstattung eines bereits gezahlten Betrags.", null, null);
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

    private TermService guardedService(Runnable afterSecondGuard) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new GuardSyncTx(tx, afterSecondGuard);
            }
            return tx;
        });
        TermRepository repository = KognioRdfTermRepositoryFactory.over(guarded);
        return new TermService(repository, new UuidResourceIdFactory());
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
        public DatasetExport datasetExport() {
            return delegate.datasetExport();
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
     * Runs {@code afterSecondGuard} exactly once its delegate's second {@code contains()} call
     * returns - {@link KognioRdfTermRepository#create} issues exactly two on the create path: the
     * identity guard, then the code-uniqueness guard.
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
