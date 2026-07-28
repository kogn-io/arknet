// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Regression tests for {@link KognioRdfProjectRegistry#register} against a real RDF4J-backed store
 * (on-disk {@code NativeStore}) with real threads: two registrations whose transactions genuinely
 * <em>overlap</em>, so that both pass their in-transaction uniqueness guards before either commits
 * and only the second commit is rejected - by the store, not by a guard (issue #181, the
 * {@code arknet-project} counterpart of the four model contexts' {@code
 * *RealStoreConcurrencyTest}s, issue #144).
 *
 * <p><strong>What this proves.</strong> Two things the four model contexts' tests cannot cover,
 * because this context differs from them in two ways. First: it guards <em>two</em> uniqueness
 * rules, the label (through {@link de.hauschel.arknet.persistence.WriteFunnel}'s {@code code}
 * parameter) and the anchor ({@link KognioRdfProjectRegistry#checkAnchorUniqueness}, ADR-016
 * decision 4) - so "you lost the commit race" does not by itself say which rule was broken.
 * Second: there is no {@code CodeAssignment} retry to heal the loser (there is no {@code PRJ-N}
 * code to recompute), so whatever the loser is thrown is what a caller actually reads. The proof
 * is therefore not only that the invariant holds - one anchor, one project, even under a genuinely
 * overlapping race - but that the losing caller is told <em>which</em> collision cost it the
 * write.</p>
 *
 * <p><strong>Why the on-disk sail.</strong> Every race here is decided by the store's commit-time
 * conflict detection, and that lives in each sail rather than in a shared layer above them:
 * {@code rdf4j-sail-memory} and {@code rdf4j-sail-nativerdf} are two separate code paths. The
 * daemon runs on the {@code NativeStore}, so this store is built {@code PERSISTENT} - with the very
 * {@link DatasetStoreConfig#persistentDefault()} configuration the composition root uses (issue
 * #180). {@link KognioRdfProjectRegistryTest} stays {@code IN_MEMORY} on purpose: what it asserts
 * sits above the store, where the faster sail is the legitimate choice.</p>
 *
 * <p><strong>Driven at the out-port, not through {@code ProjectService}.</strong> The service's
 * {@code register} runs a {@code findByAnchor} pre-check outside any transaction, which catches the
 * <em>non</em>-parallel case and would only dilute what these tests pin down. The race lives below
 * that check, in the adapter, so that is where these tests drive it.</p>
 *
 * <p><strong>How the overlap is forced deterministically.</strong> A {@link DatasetLifecycle}
 * decorator wraps each caller's {@link DatasetTx} so that it blocks on a two-party
 * {@link CyclicBarrier} right before its first {@code add} - the moment every guard of that
 * transaction has passed and nothing has been written yet. The hook deliberately hangs on the
 * first write rather than on a counted {@code contains} call the way {@code
 * BoundedContextServiceRealStoreConcurrencyTest} does: this write path issues four such guards
 * (the funnel's identity and label checks, then the body's own label and per-anchor checks), and
 * a count would silently stop pinning the intended moment the day one of them is added or dropped.
 * "The last guard has passed" is what matters, and "about to write" says exactly that. A
 * {@link CountDownLatch} then holds the loser until the winner's transaction has fully committed,
 * so which of the two loses is deterministic instead of flaky. The decorator disarms itself after
 * firing once, so nothing that follows the race is synchronised.</p>
 *
 * <p><strong>Timeout.</strong> {@link CyclicBarrier#await()}/{@link CountDownLatch#await()} block
 * indefinitely by default; a future regression that stops one caller from ever reaching its
 * barrier/latch would hang {@code join()} forever, so neither {@code @AfterEach} nor
 * {@code shutDownAll()} would ever run - the build would hang instead of failing. The project has
 * no {@code junit-platform.properties}/Surefire-level timeout, so this class-level {@link Timeout}
 * is the only backstop; each interleaving itself normally resolves in well under a second.</p>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ProjectRegistryRealStoreConcurrencyTest {

    /**
     * The {@code NativeStore}'s on-disk home, managed by JUnit rather than by
     * {@code Files.createTempDirectory}: a persistent store fills its directory, and JUnit deletes
     * this one after {@link #tearDown()} has shut the store down (issue #180).
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j realLifecycle;
    private ProjectRegistry straightThrough;

    @BeforeEach
    void setUp() {
        realLifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        straightThrough = KognioRdfProjectRepositoryFactory.registryOver(realLifecycle, DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        realLifecycle.shutDownAll();
    }

    /**
     * The invariant itself (ADR-016 decision 4) under a genuinely overlapping race: one anchor
     * ends up owned by exactly one project - and the loser is told about the <em>anchor</em>.
     *
     * <p>Before the {@code commitConflict} translator existed, the loser was thrown
     * {@link DuplicateProjectLabelException} for a label no project had ever registered, because
     * {@code WriteFunnel#create} reported every lost commit as a code collision (issue #181). Both
     * callers deliberately register a different label here, so nothing but the anchor can be the
     * real cause and a label complaint cannot be right by accident.</p>
     */
    @Test
    void concurrentRegistrationsOfTheSameAnchor_leaveOneOwnerAndTellTheLoserAboutTheAnchor()
            throws InterruptedException {
        Anchor contested = pathAnchor("/home/dev/arknet");
        Project winner = new Project(freshId(), "winner-label", List.of(contested));
        Project loser = new Project(freshId(), "loser-label", List.of(contested));

        Race race = raceRegistrations(winner, loser);

        assertNull(race.winnerFailure(), "the winner commits first and must not fail");
        AnchorAlreadyRegisteredException rejected = assertInstanceOf(AnchorAlreadyRegisteredException.class,
                race.loserFailure(), "the loser lost on the anchor, so that is what it must be told");
        assertEquals(contested, rejected.anchor());
        assertEquals(winner.id(), rejected.owner(), "the message must name the project that actually owns it");

        assertEquals(List.of(winner), straightThrough.findAll(),
                "the loser's whole transaction must have rolled back - one anchor, one project");
    }

    /**
     * The label rule under the same overlapping race: both callers pass the funnel's synchronous
     * {@code dcterms:identifier} check because neither sees the other's uncommitted write, and the
     * loser must still be told its label is taken - here it genuinely is.
     */
    @Test
    void concurrentRegistrationsOfTheSameLabel_leaveOneHolderAndTellTheLoserAboutTheLabel()
            throws InterruptedException {
        Project winner = new Project(freshId(), "arknet", List.of(pathAnchor("/home/dev/arknet")));
        Project loser = new Project(freshId(), "arknet", List.of(pathAnchor("/home/dev/arknet-worktree")));

        Race race = raceRegistrations(winner, loser);

        assertNull(race.winnerFailure());
        assertInstanceOf(DuplicateProjectLabelException.class, race.loserFailure(),
                "the loser lost on the label, and that is a collision a human can cause and fix");
        assertEquals(List.of(winner), straightThrough.findAll());
    }

    /**
     * The same overlap, but between two registrations that share <em>nothing</em> - different
     * label, different anchor: both commit. The uniqueness guards cost concurrent registrations of
     * unrelated projects nothing, even though every one of them reads {@code dcterms:identifier}
     * with an unbound subject: the store's conflict detection is finer than the graph the guard
     * scans, so one new project's identifier triple does not invalidate another's read.
     *
     * <p>Measured, not assumed - and it is the reason the two tests above may assert a
     * <em>specific</em> collision at all. Were an unrelated writer enough to lose the race, the
     * loser's rejection would no longer identify what it collided with, and any collision named
     * for it would be a guess dressed up as a diagnosis.</p>
     */
    @Test
    void twoUnrelatedRegistrationsOverlapWithoutCostingEitherOfThemTheWrite() throws InterruptedException {
        Project first = new Project(freshId(), "alpha", List.of(pathAnchor("/home/dev/alpha")));
        Project second = new Project(freshId(), "beta", List.of(pathAnchor("/home/dev/beta")));

        Race race = raceRegistrations(first, second, false);

        assertNull(race.winnerFailure());
        assertNull(race.loserFailure(), "nothing collided, so neither write may be rejected");
        assertEquals(Set.of(first, second), Set.copyOf(straightThrough.findAll()));
    }

    /**
     * The residual case, and the reason {@link KognioRdfProjectRegistry} may return the store's own
     * exception unchanged: a lost commit that none of this context's uniqueness rules explains.
     * The test above shows the real store does not produce one today for two unrelated
     * registrations, so the conflict is injected here rather than raced - which is the honest way
     * to pin a fallback whose trigger is, by definition, something the adapter does not know about
     * (a future guard, a different sail, a store that detects conflicts more coarsely - ADR-001
     * keeps it swappable).
     *
     * <p>What must not happen is the adapter inventing an explanation: reporting
     * {@link DuplicateProjectLabelException} for a label that is demonstrably free - the defect
     * issue #181 uncovered - would send the caller after a collision that never existed.</p>
     */
    @Test
    void aLostCommitNoRuleExplainsSurfacesAsTheStoresOwnConflict() {
        ConcurrencyConflictException storeConflict = new ConcurrencyConflictException("lost the commit", null);
        ProjectRegistry failingCommits =
                KognioRdfProjectRepositoryFactory.registryOver(new GuardedLifecycle(realLifecycle,
                        tx -> tx, storeConflict), DisplayLocale.DEFAULT);
        Project project = new Project(freshId(), "free-label", List.of(pathAnchor("/home/dev/free")));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> failingCommits.register(project));

        assertSame(storeConflict, thrown, "with no rule broken there is nothing truthful to translate into");
        assertTrue(straightThrough.findAll().isEmpty(), "the rejected write must have left nothing behind");
    }

    // ---- the race itself -----------------------------------------------------------------

    /** What both callers came out of a race with: the winner's outcome and the loser's. */
    private record Race(Throwable winnerFailure, Throwable loserFailure) {
    }

    /** Races two registrations that are expected to collide, so the loser must be rejected. */
    private Race raceRegistrations(Project winner, Project loser) throws InterruptedException {
        return raceRegistrations(winner, loser, true);
    }

    /**
     * Registers both projects concurrently through two separate registries, holding each caller at
     * its first write until both have passed every guard, and the loser additionally until the
     * winner has committed.
     *
     * @param expectLoserRejected whether the second committer must come back with a failure - the
     *                            guard that a test claiming to have raced actually raced, rather
     *                            than having run two writes that never overlapped
     */
    private Race raceRegistrations(Project winner, Project loser, boolean expectLoserRejected)
            throws InterruptedException {
        CyclicBarrier bothGuardsPassed = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        ProjectRegistry winnerRegistry = guardedRegistry(() -> awaitBarrier(bothGuardsPassed));
        ProjectRegistry loserRegistry = guardedRegistry(() -> {
            awaitBarrier(bothGuardsPassed);
            awaitLatch(winnerCommitted);
        });

        AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            try {
                winnerRegistry.register(winner);
            } catch (Throwable t) {
                // Throwable, not RuntimeException: a broken harness (a missing method, an
                // assertion inside the decorator) must surface as a failed assertion below
                // rather than as a silently absent failure.
                winnerFailure.set(t);
            } finally {
                winnerCommitted.countDown();
            }
        });
        Thread loserThread = new Thread(() -> {
            try {
                loserRegistry.register(loser);
            } catch (Throwable t) {
                loserFailure.set(t);
            }
        });

        winnerThread.start();
        loserThread.start();
        winnerThread.join();
        loserThread.join();

        if (expectLoserRejected) {
            assertNotNull(loserFailure.get(),
                    "the loser must not have committed - the race was not raced otherwise");
        }
        return new Race(winnerFailure.get(), loserFailure.get());
    }

    private static ProjectId freshId() {
        return new ProjectId(UUID.randomUUID().toString());
    }

    private static Anchor pathAnchor(String value) {
        return new Anchor(value, AnchorType.PATH);
    }

    /**
     * A registry whose first write transaction runs {@code beforeFirstWrite} right before its
     * first {@code add} - after every guard of that transaction has passed and before anything is
     * written. Disarms itself afterwards, so the store's own read paths and any later write run
     * unsynchronised.
     */
    private ProjectRegistry guardedRegistry(Runnable beforeFirstWrite) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new PausingTx(tx, beforeFirstWrite);
            }
            return tx;
        }, null);
        return KognioRdfProjectRepositoryFactory.registryOver(guarded, DisplayLocale.DEFAULT);
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

    // ---- DatasetLifecycle decoration -----------------------------------------------------

    /**
     * Wraps a real {@link DatasetLifecycle}, decorating every acquired transaction's
     * {@link DatasetTx} and - if {@code failCommitWith} is given - rejecting every write
     * transaction with it once the body has run, the way a store rejects a commit it found in
     * conflict. The real transactor still sees the exception, so it rolls its transaction back:
     * nothing is written, exactly as in a genuinely lost race.
     */
    private static final class GuardedLifecycle implements DatasetLifecycle {

        private final DatasetLifecycle delegate;
        private final Function<DatasetTx, DatasetTx> txDecorator;
        private final RuntimeException failCommitWith;

        GuardedLifecycle(DatasetLifecycle delegate, Function<DatasetTx, DatasetTx> txDecorator,
                RuntimeException failCommitWith) {
            this.delegate = delegate;
            this.txDecorator = txDecorator;
            this.failCommitWith = failCommitWith;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return new GuardedHandle(delegate.acquire(id), txDecorator, failCommitWith);
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
        private final RuntimeException failCommitWith;

        GuardedHandle(DatasetHandle delegate, Function<DatasetTx, DatasetTx> txDecorator,
                RuntimeException failCommitWith) {
            this.delegate = delegate;
            this.txDecorator = txDecorator;
            this.failCommitWith = failCommitWith;
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
                    return real.inTransaction(tx -> {
                        T result = fn.apply(txDecorator.apply(tx));
                        if (failCommitWith != null) {
                            throw failCommitWith;
                        }
                        return result;
                    });
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Runs {@code beforeFirstWrite} once, immediately before its delegate's first
     * {@link GraphStore#add} call - the point in {@link KognioRdfProjectRegistry}'s write body
     * where every uniqueness guard has passed and no triple has been written yet.
     */
    private static final class PausingTx implements DatasetTx {

        private final DatasetTx delegate;
        private final Runnable beforeFirstWrite;
        private boolean pending = true;

        PausingTx(DatasetTx delegate, Runnable beforeFirstWrite) {
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
        public boolean contains(IRI graph, BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
            return delegate.contains(graph, subject, predicate, object);
        }

        @Override
        public boolean ask(String query) {
            return delegate.ask(query);
        }

        @Override
        public boolean ask(String query, Map<String, RDFTerm> bindings) {
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
        public void update(String sparqlUpdate, Map<String, RDFTerm> bindings) {
            delegate.update(sparqlUpdate, bindings);
        }

        @Override
        public Stream<BindingSet> select(String query) {
            return delegate.select(query);
        }

        @Override
        public Stream<BindingSet> select(String query, Map<String, RDFTerm> bindings) {
            return delegate.select(query, bindings);
        }

        @Override
        public ReadableGraph construct(String query) {
            return delegate.construct(query);
        }

        @Override
        public ReadableGraph construct(String query, Map<String, RDFTerm> bindings) {
            return delegate.construct(query, bindings);
        }
    }
}
