// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.testsupport.GuardSyncTx;
import de.hauschel.arknet.persistence.testsupport.GuardedLifecycle;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermCycleException;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermLabelMismatchException;

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
 * {@code shutDownAll()} would ever run - the build would hang instead of failing. The project sets
 * no class-level timeouts: the backstop is project-wide,
 * {@code junit.jupiter.execution.timeout.default} in the root POM's Surefire
 * {@code configurationParameters}, sized to catch a hang rather than to police runtime
 * (kogn-io/arknet#458); the interleaving itself normally resolves in well under a second.</p>
 */
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
        //, so that the next sighting is evaluable instead of merely confirming the symptom.
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
                Term result = winnerService.add(WS, newTerm(), "en");
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
                Term result = loserService.add(WS, newTerm(), "en");
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

        List<Term> stored = KognioRdfTermRepositoryFactory.over(realLifecycle).findAll(WS, null);
        assertEquals(2, stored.size(), diagnostics);
        assertTrue(stored.stream().map(Term::code).toList()
                .containsAll(List.of(winnerResult.get().code(), loserResult.get().code())), diagnostics);
    }

    /**
     * The {@code term_update} counterpart of the race above (issue #230 review), reworked for
     * FR-10 (kogn-io/arknet#502): a term's {@code skos:prefLabel} is now the same word under every
     * language tag, so two racers writing two genuinely different words for two different
     * languages - the original scenario here - would itself be the violation the rule exists to
     * prevent, not a race worth surviving. The race that remains interesting is a translation
     * racing a rename: the winner renames the term outright (no {@code language}); the loser,
     * unaware, tries to add an {@code @fr} translation of the term's OLD word. The loser's
     * pre-transaction mismatch check passes against its own (still stale) read - the old word
     * still matches at that point - but it then loses the head CAS to the winner's commit under
     * {@code SERIALIZABLE} isolation. Its internal retry (inside
     * {@link KognioRdfTermRepository#update}) re-reads the now-current state, sees the winner's
     * new word, and correctly rejects the stale translation with {@link TermLabelMismatchException}
     * instead of silently writing a translation of a word the term no longer carries.
     *
     * <p>This is the reason the mismatch guard sits in
     * {@link KognioRdfTermRepository#attemptUpdate}, checked against the very same
     * compare-and-set read on every attempt, rather than as a separate, one-shot read in
     * {@code TermService}: only a check that reruns on every retry can see the concurrent rename
     * before it writes.</p>
     */
    @Test
    void concurrentRenameAndStaleTranslationWrite_theStaleTranslationIsRejectedNotSilentlyWritten()
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
                winnerRepository.update(WS, code, "Kunde (korrigiert)", null, null, null, null, null);
            } catch (Throwable t) {
                winnerFailure.set(t);
            } finally {
                winnerCommitted.countDown();
            }
        }, "racer-A");
        Thread loserThread = new Thread(() -> {
            try {
                loserRepository.update(WS, code, "Kunde", null, "fr", null, null, null);
            } catch (Throwable t) {
                loserFailure.set(t);
            }
        }, "racer-B");

        winnerThread.start();
        loserThread.start();
        winnerThread.join();
        loserThread.join();

        assertNull(winnerFailure.get(), "the winner commits first and must not fail");
        assertNotNull(loserFailure.get(),
                "the loser's stale translation write must be rejected once its retry sees the winner's rename");
        assertInstanceOf(TermLabelMismatchException.class, loserFailure.get(),
                "the retry must re-read the renamed word and report a label mismatch, not a generic "
                        + "concurrent-modification failure");
        assertTrue(loserFailure.get().getMessage().contains("Kunde (korrigiert)"),
                "the rejection must name the term's now-current label");

        assertTrue(subjectHasLanguageTaggedPrefLabel(id, "Kunde (korrigiert)", "de"),
                "the winner's rename must be stored");
        assertFalse(subjectHasLanguageTaggedPrefLabel(id, "Kunde (korrigiert)", "fr"),
                "the rejected loser must not have written any @fr variant");
        assertFalse(subjectHasLanguageTaggedPrefLabel(id, "Kunde", "de"),
                "the original word must not survive the winner's rename");
        assertEquals(1, countPrefLabelTriples(id),
                "exactly one prefLabel: the winner's rename - nothing added or left over from the loser");
    }

    /**
     * The should-fix finding from issue #252's own review (PR #268): two {@code term_update} calls,
     * each setting the <em>other</em> term as its own {@code skos:broader}, close a cycle no single
     * call's guard alone can ever see - racer-A's cycle check only walks TERM-B's chain, racer-B's
     * only walks TERM-A's, and if both run before either commits, both see an empty chain and both
     * believe themselves safe. Reproduced by pausing racer-A's transaction right after its
     * in-transaction re-check of TERM-B's chain ({@link KognioRdfTermRepository#assertNoCycle},
     * called from inside {@link de.hauschel.arknet.persistence.WriteFunnel#compareAndUpdate}'s write
     * body) but before racer-A writes anything, letting racer-B's whole {@code term_update} run and
     * commit to completion, then releasing racer-A.
     *
     * <p>Before that in-transaction re-check existed, racer-A's only cycle check ran entirely before
     * its own write transaction opened - a snapshot read racer-B's later commit could never
     * invalidate, so racer-A would go on to commit its own half of the cycle unchallenged and the
     * store would end up holding both halves. With the re-check running against {@code tx::select}
     * instead, racer-A's read of TERM-B's (still empty) chain becomes part of its own transaction's
     * read set: racer-B's concurrent commit of {@code TERM-B.broader=TERM-A} invalidates that read
     * under the store's {@code SERIALIZABLE} isolation, racer-A's own commit loses as a genuine
     * write conflict (translated to {@link de.hauschel.arknet.ul.domain.TermConcurrentlyModifiedException}),
     * and {@link KognioRdfTermRepository#update}'s retry loop re-runs {@code attemptUpdate} from
     * scratch - whose fresh pre-transaction {@code assertNoCycle} now sees the real, already-committed
     * {@code TERM-B.broader=TERM-A} and correctly rejects racer-A with {@link TermCycleException}
     * instead of silently completing the cycle.</p>
     */
    @Test
    void concurrentUpdatesRacingToCloseATwoTermCycle_racerAIsRejectedInsteadOfBothSucceeding()
            throws InterruptedException {
        TermRepository straightThrough = KognioRdfTermRepositoryFactory.over(realLifecycle);
        TermId idA = new TermId(new UuidResourceIdFactory().newId());
        TermId idB = new TermId(new UuidResourceIdFactory().newId());
        TermCode codeA = new TermCode("TERM-1");
        TermCode codeB = new TermCode("TERM-2");
        straightThrough.create(WS, new Term(idA, codeA, "Begriff A", "Erste Definition.", null), "de");
        straightThrough.create(WS, new Term(idB, codeB, "Begriff B", "Zweite Definition.", null), "de");

        CountDownLatch racerAReachedReCheck = new CountDownLatch(1);
        CountDownLatch racerBCommitted = new CountDownLatch(1);

        TermRepository racerARepository = pausingAfterSecondSelectRepository(() -> {
            racerAReachedReCheck.countDown();
            awaitLatch(racerBCommitted);
        });

        AtomicReference<Throwable> racerAFailure = new AtomicReference<>();
        AtomicReference<Throwable> racerBFailure = new AtomicReference<>();

        Thread racerAThread = new Thread(() -> {
            try {
                racerARepository.update(WS, codeA, null, null, "de", null, Optional.of(codeB), null);
            } catch (Throwable t) {
                racerAFailure.set(t);
            }
        }, "racer-A");
        Thread racerBThread = new Thread(() -> {
            try {
                awaitLatch(racerAReachedReCheck);
                straightThrough.update(WS, codeB, null, null, "de", null, Optional.of(codeA), null);
            } catch (Throwable t) {
                racerBFailure.set(t);
            } finally {
                racerBCommitted.countDown();
            }
        }, "racer-B");

        racerAThread.start();
        racerBThread.start();
        racerAThread.join();
        racerBThread.join();

        assertNull(racerBFailure.get(), "racer-B runs and commits unobstructed - the cycle's other half");
        assertNotNull(racerAFailure.get(),
                "racer-A must not silently complete the cycle once racer-B has closed the other half");
        assertInstanceOf(TermCycleException.class, racerAFailure.get(),
                "racer-A's retry re-reads the now-real chain and must report it as a cycle, not a "
                        + "generic concurrent-modification failure");

        assertTrue(subjectHasBroader(idB, idA), "racer-B's half of the (attempted) cycle must be persisted");
        assertFalse(subjectHasBroader(idA, idB), "racer-A's half must NOT be persisted - no cycle in the store");
    }

    /**
     * The delete-vs-reference race issue #335's own review asked for: {@code term_delete}'s
     * {@code rejectIfReferenced} check and a concurrent writer that commits a brand-new
     * {@code arkreq:usesTerm} edge onto the very same term, genuinely overlapping under
     * {@code SERIALIZABLE} isolation.
     *
     * <p>The delete's transaction is paused - via {@link #pausingBeforeFirstUpdateRepository} -
     * immediately after {@link KognioRdfTermRepository#rejectIfReferenced} has run its {@code ASK}
     * checks and found nothing (there is nothing to find yet) but before the physical
     * {@code DELETE WHERE} that follows it. While paused, a second writer commits a fresh
     * {@code arkreq:usesTerm} triple pointing at the very same term, in its own transaction against
     * the same real store - deliberately raw SPARQL rather than a {@code RequirementService} call:
     * this adapter module cannot depend on the requirements bounded context, and the race is a
     * store-level phenomenon between two write transactions,
     * independent of which bounded context authored either one. Only then is the delete allowed to
     * resume and attempt its commit.</p>
     *
     * <p><strong>What must hold regardless of which side the store resolves the conflict.</strong>
     * The one invariant issue #335 exists to protect is that a {@code usesTerm} edge must never end
     * up dangling at a deleted subject. Two outcomes are both acceptable: (a) the store's
     * {@code SERIALIZABLE} isolation detects that the delete's read set (the {@code ASK} pattern
     * across every graph) was invalidated by the concurrent insert and rejects the delete's commit,
     * in which case the term and the fresh reference both survive; or (b) the two transactions do
     * not conflict at the store's own read/write-set granularity and the delete commits regardless,
     * in which case the reference itself must be gone too - {@code usesTerm} pointing at the very
     * subject whose triples {@link KognioRdfTermRepository#delete} just removed would itself be the
     * dangling edge. What must never happen is the term gone <em>and</em> the reference still
     * standing - that is the outcome this test pins against.</p>
     */
    @Test
    void deleteRacingAConcurrentlyCommittedUsesTermReferenceLeavesNoDanglingReference()
            throws InterruptedException {
        TermRepository straightThrough = KognioRdfTermRepositoryFactory.over(realLifecycle);
        TermId id = new TermId(new UuidResourceIdFactory().newId());
        TermCode code = new TermCode("TERM-1");
        straightThrough.create(WS, new Term(id, code, "Gutschrift", "Eine Erstattung.", null), "de");

        CountDownLatch deletePausedAfterReferenceCheck = new CountDownLatch(1);
        CountDownLatch referenceCommitted = new CountDownLatch(1);

        TermRepository deletingRepository = pausingBeforeFirstUpdateRepository(() -> {
            deletePausedAfterReferenceCheck.countDown();
            awaitLatch(referenceCommitted);
        });

        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        Thread deleteThread = new Thread(() -> {
            try {
                deletingRepository.delete(WS, code);
            } catch (Throwable t) {
                deleteFailure.set(t);
            }
        }, "racer-delete");
        Thread referenceThread = new Thread(() -> {
            awaitLatch(deletePausedAfterReferenceCheck);
            String insert = "INSERT DATA { GRAPH <https://example.org/req> { <https://example.org/req/1> <"
                    + ArkreqVocabulary.USES_TERM + "> <" + id.value().value() + "> } }";
            try (DatasetHandle handle = realLifecycle.acquire(new DatasetId(WS.value()))) {
                handle.transactor().inTransaction(tx -> {
                    tx.update(insert);
                    return null;
                });
            } finally {
                referenceCommitted.countDown();
            }
        }, "racer-reference");

        deleteThread.start();
        referenceThread.start();
        deleteThread.join();
        referenceThread.join();

        boolean termStillExists = straightThrough.findByCode(WS, code, null).isPresent();
        boolean referenceStillExists = isReferencedViaUsesTerm(id);
        String diagnostics = "deleteFailure=" + (deleteFailure.get() == null ? "none"
                : deleteFailure.get().getClass().getName() + ": " + deleteFailure.get().getMessage())
                + ", termStillExists=" + termStillExists + ", referenceStillExists=" + referenceStillExists;
        assertFalse(!termStillExists && referenceStillExists,
                "dangling reference: the term is gone but a usesTerm edge still points at it: " + diagnostics);
        assertFalse(!termStillExists && deleteFailure.get() != null,
                "the delete must not both fail and have removed the term: " + diagnostics);
    }

    /** {@code true} if any named graph holds an {@code arkreq:usesTerm} triple pointing at {@code id}. */
    private boolean isReferencedViaUsesTerm(TermId id) {
        String query = "ASK { GRAPH ?g { ?s <" + ArkreqVocabulary.USES_TERM + "> <"
                + id.value().value() + "> } }";
        try (DatasetHandle handle = realLifecycle.acquire(new DatasetId(WS.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /**
     * A term repository whose first write transaction runs {@code beforeFirstUpdate} right before
     * its delegate's first {@link DatasetTx#update(String)} call - the point in
     * {@link KognioRdfTermRepository#delete}'s write body where {@code rejectIfReferenced}'s
     * {@code ASK} checks have already run and found nothing, and the physical
     * {@code DELETE WHERE} is the very next thing to happen. Disarms itself afterwards, mirroring
     * {@link #guardedRepository}/{@link #pausingAfterSecondSelectRepository}.
     */
    private TermRepository pausingBeforeFirstUpdateRepository(Runnable beforeFirstUpdate) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new PausingOnFirstUpdateTx(tx, beforeFirstUpdate);
            }
            return tx;
        });
        return KognioRdfTermRepositoryFactory.over(guarded);
    }

    /**
     * Runs {@code beforeFirstUpdate} once, immediately before its delegate's first
     * {@link DatasetTx#update(String)} call. Mirrors {@link PausingOnFirstAddTx}'s shape but hooks
     * {@code update} instead of {@code add}, since {@link KognioRdfTermRepository#delete}'s write
     * body issues no {@code add} at all - only {@code ask} (the reference checks) followed by one
     * {@code update} (the physical {@code DELETE WHERE}).
     */
    private static final class PausingOnFirstUpdateTx implements DatasetTx {

        private final DatasetTx delegate;
        private final Runnable beforeFirstUpdate;
        private boolean pending = true;

        PausingOnFirstUpdateTx(DatasetTx delegate, Runnable beforeFirstUpdate) {
            this.delegate = delegate;
            this.beforeFirstUpdate = beforeFirstUpdate;
        }

        @Override
        public void update(String sparqlUpdate) {
            if (pending) {
                pending = false;
                beforeFirstUpdate.run();
            }
            delegate.update(sparqlUpdate);
        }

        @Override
        public void update(String sparqlUpdate, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
            delegate.update(sparqlUpdate, bindings);
        }

        @Override
        public long add(IRI graph, ReadableGraph data) {
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

    /** Whether {@code subject} carries a {@code skos:broader} edge to exactly {@code target}. */
    private boolean subjectHasBroader(TermId subject, TermId target) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { <"
                + subject.value().value() + "> <" + ArkreqVocabulary.BROADER + "> <" + target.value().value() + "> } }";
        try (DatasetHandle handle = realLifecycle.acquire(new DatasetId(WS.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /**
     * A term repository whose first write transaction runs {@code afterSecondSelect} the moment its
     * delegate's <em>second</em> {@code select()} call has returned - the funnel's own {@code
     * arkprov:head} read is the first, {@link KognioRdfTermRepository#assertNoCycle}'s in-transaction
     * re-check of the candidate broader term's chain is the second, so this pauses a caller right
     * after it has read the other term's (at that point still empty) chain but before it writes
     * anything. Disarms itself afterwards, exactly like {@link #guardedRepository}, so a retried
     * attempt on the same repository instance runs unimpeded.
     */
    private TermRepository pausingAfterSecondSelectRepository(Runnable afterSecondSelect) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new PausingOnSecondSelectTx(tx, afterSecondSelect);
            }
            return tx;
        });
        return KognioRdfTermRepositoryFactory.over(guarded);
    }

    /**
     * Runs {@code afterSecondSelect} once, immediately after its delegate's second {@link
     * DatasetTx#select(String)} call has returned. Mirrors {@link PausingOnFirstAddTx}'s shape but
     * counts reads instead of writes, since the interleaving this pins must be held open one read
     * short of the write, not at the write itself.
     */
    private static final class PausingOnSecondSelectTx implements DatasetTx {

        private final DatasetTx delegate;
        private final Runnable afterSecondSelect;
        private int selectCount;

        PausingOnSecondSelectTx(DatasetTx delegate, Runnable afterSecondSelect) {
            this.delegate = delegate;
            this.afterSecondSelect = afterSecondSelect;
        }

        @Override
        public Stream<BindingSet> select(String query) {
            Stream<BindingSet> result = delegate.select(query);
            selectCount++;
            if (selectCount == 2) {
                afterSecondSelect.run();
            }
            return result;
        }

        @Override
        public Stream<BindingSet> select(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
            return delegate.select(query, bindings);
        }

        @Override
        public long add(IRI graph, ReadableGraph data) {
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
        public ReadableGraph construct(String query) {
            return delegate.construct(query);
        }

        @Override
        public ReadableGraph construct(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
            return delegate.construct(query, bindings);
        }
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
     * {@code arkprov:head} read fresh from the store after the race (the concurrency token -
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
     * Reads {@code term}'s current {@code arkprov:head} straight from
     * {@link #realLifecycle} after the race, outside any transaction - the same triple pattern
     * {@link de.hauschel.arknet.persistence.WriteFunnel#compareAndUpdate} reads inside its
     * transaction, but there is no accessor for that private read path, so this queries it directly
     * via {@link io.kogn.rdf.dataset.SparqlQuery}.
     *
     * <p>Never throws: a timeout interrupt landing mid-race can
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
}
