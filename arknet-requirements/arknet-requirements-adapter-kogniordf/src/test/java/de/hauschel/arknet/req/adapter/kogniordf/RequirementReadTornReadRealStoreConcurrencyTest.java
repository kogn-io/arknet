// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetExport;
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
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.application.port.out.TermLookup;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Regression test for issue #171 (torn read on {@code findByCode}/{@code findAll}), reproduced
 * against a real RDF4J-backed store (on-disk {@code NativeStore}) with real threads and a forced
 * interleaving - unlike a statistical stress test, this pins the exact race the issue describes:
 * a reader's main row query returns before a concurrent {@code req_update} commits, and the
 * reader's follow-up {@code acceptanceCriterion} read would - on the pre-fix code, which ran each
 * of {@link KognioRdfRequirementRepository}'s three reads as its own independent,
 * non-transactional {@link SparqlQuery} call - only run after that commit, combining a
 * pre-write core field with a post-write peripheral field into a {@link Requirement} that never
 * existed as a single, coherent store state.
 *
 * <p><strong>Why {@code title} + {@code acceptanceCriteria}, not {@code usesTerms}.</strong> The
 * issue's own example links a term while a reader is in flight, but a linked term's target is
 * simply present or absent - the "torn" combination (old title, newly-linked term) is
 * observationally identical to a legitimate post-write read a moment later, so a test built on it
 * could not distinguish a fixed read path from a lucky one. Changing {@code title} <em>and</em>
 * {@code acceptanceCriteria} in the same {@code req_update} call instead produces two
 * observably different value pairs - {@code (OLD title, [OLD criterion])} before the write,
 * {@code (NEW title, [OLD criterion, NEW criterion])} after it, {@code req_update} appending
 * rather than replacing (issue #266) - so a torn read is caught the moment either field
 * comes from the wrong pair, deterministically, not statistically.</p>
 *
 * <p><strong>Forcing the interleaving, path-agnostically.</strong> {@link
 * KognioRdfRequirementRepository#findByCode}'s main row query reaches the store through a
 * different port depending on which side of the fix runs: the pre-fix code calls {@code
 * handle.sparqlQuery().select(...)} (non-transactional {@link SparqlQuery}), the post-fix code
 * calls {@code tx.select(...)} on the live {@link DatasetTx} of one shared transaction. This
 * test's {@link PausingHandle} decorates <em>both</em> ports with the same {@link PauseGate}, so
 * whichever one the main query actually goes through pauses - proving the fix by construction
 * rather than by only being able to run against the fixed code. The gate pauses immediately after
 * that first {@code select} call returns - still inside the reader's own connection/transaction -
 * until the writer's {@code req_update} has fully committed on an independent connection: a
 * {@link CyclicBarrier} orders "writer starts writing" strictly after "reader's main query
 * returned", and a separate {@link CountDownLatch}, released only once the writer's synchronous
 * write call has returned (commit included), orders "reader's follow-up reads run" strictly after
 * "writer committed". Only then do the reader's follow-up reads ({@code readUsesTerms}, {@code
 * readAcceptanceCriteria}) and its transaction's commit proceed. Mirrors the shared {@code
 * GuardedLifecycle}/{@code GuardedHandle} decoration technique (arknet-persistence-test-support),
 * applied to {@code select} instead of {@code contains}.</p>
 *
 * <p><strong>Why the assertion allows either pair, not just the pre-write one.</strong> Once the
 * fix is in place, the reader's transaction has observed the {@code title} pattern before the
 * writer's commit, so - under {@code SERIALIZABLE} isolation - its own commit can itself lose a
 * race against that concurrent write (see {@link
 * KognioRdfRequirementRepository#readInTransaction}'s javadoc): when that happens here, the
 * reader's first attempt is discarded whole and retried from scratch against the store's
 * now-current, post-write state. Either outcome - the pre-write pair from a first attempt that
 * kept its snapshot to commit, or the post-write pair from a first attempt that lost the race and
 * retried cleanly - is a legitimate, coherent read; a torn read is neither, and is what this test
 * actually rules out. Run against the pre-fix code (no shared transaction, no retry), this
 * assertion fails: the pre-fix main query returns the pre-write title, and its non-transactional
 * follow-up read - reached fresh, after the writer's commit - returns the post-write criteria,
 * neither pair.</p>
 *
 * <p><strong>Timeout.</strong> Same backstop as the sibling real-store concurrency tests: no
 * {@code junit-platform.properties}/Surefire-level timeout exists project-wide, so this
 * class-level {@link Timeout} is what turns a future regression that stops either thread from
 * ever reaching its barrier/latch into a failure instead of a hang.</p>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RequirementReadTornReadRealStoreConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");
    private static final String OLD_TITLE = "Old Title";
    private static final String NEW_TITLE = "New Title";
    private static final String OLD_CRITERION = "Done when the old behaviour holds";
    private static final String NEW_CRITERION = "Done when the new behaviour holds";

    /** Unused by this test: no term is ever linked. */
    private static final TermLookup UNUSED_TERM_LOOKUP = (projectId, termCode) -> {
        throw new UnsupportedOperationException("not exercised by this test");
    };
    /** Unused by this test: {@code req_schema} is orthogonal to the read path under test. */
    private static final RequirementSchemaSource UNUSED_SCHEMA_SOURCE = List::of;
    /** Unused by this test: no constraint is ever linked or resolved. */
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
     * The {@code NativeStore}'s on-disk home, managed by JUnit rather than
     * {@code Files.createTempDirectory} - see the sibling real-store concurrency tests for why a
     * persistent store needs this instead of an in-memory one (commit-time conflict detection
     * belongs to the sail, and {@code NativeStore} is the one production runs on).
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
    void findByCodeDuringConcurrentUpdate_neverReturnsATornCombination() throws InterruptedException {
        // given - a requirement whose title and acceptance criteria are both about to change in
        // one req_update call, so "torn" is observable rather than merely theoretical.
        RequirementRepository plainRepository =
                KognioRdfRequirementRepositoryFactory.over(realLifecycle, DisplayLocale.DEFAULT);
        RequirementService plainService = new RequirementService(
                plainRepository, new UuidResourceIdFactory(), UNUSED_TERM_LOOKUP, UNUSED_CONSTRAINT_REPOSITORY,
                UNUSED_SCHEMA_SOURCE);
        RequirementCode code = plainService.add(WS, new NewRequirement(OLD_TITLE,
                "A requirement whose fields change mid-read.", null, RequirementType.FUNCTIONAL, null, null, null,
                List.of(OLD_CRITERION), null), "en").code();

        CyclicBarrier readerPastMainQuery = new CyclicBarrier(2);
        CountDownLatch writerCommitted = new CountDownLatch(1);

        RequirementRepository readerRepository =
                pausingAfterFirstSelectRepository(readerPastMainQuery, writerCommitted);

        AtomicReference<Optional<Requirement>> readResult = new AtomicReference<>();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread readerThread = new Thread(() -> {
            try {
                readResult.set(readerRepository.findByCode(WS, code, null));
            } catch (Throwable t) {
                readerFailure.set(t);
            }
        }, "reader");

        Thread writerThread = new Thread(() -> {
            awaitBarrier(readerPastMainQuery);
            try {
                plainService.update(WS, code, NEW_TITLE, null, null, List.of(NEW_CRITERION), null, null, null, "en");
            } finally {
                writerCommitted.countDown();
            }
        }, "writer");

        // when - the reader's main query returns and pauses (still inside its own open
        // transaction) before the writer's req_update commits on an independent connection; the
        // reader's follow-up reads and commit only run once the writer is done.
        readerThread.start();
        writerThread.start();
        readerThread.join();
        writerThread.join();

        // then - no torn combination: the reader's whole result comes from one snapshot, so it is
        // either the pre-write or the post-write pair, never a mix of the two.
        assertNull(readerFailure.get(), () -> "reader threw: " + readerFailure.get());
        Requirement observed = readResult.get().orElseThrow(() -> new AssertionError("requirement not found"));

        boolean matchesPreWriteSnapshot = OLD_TITLE.equals(observed.title())
                && List.of(new AcceptanceCriterion(1, OLD_CRITERION)).equals(observed.acceptanceCriteria());
        boolean matchesPostWriteSnapshot = NEW_TITLE.equals(observed.title())
                && List.of(new AcceptanceCriterion(1, OLD_CRITERION), new AcceptanceCriterion(2, NEW_CRITERION))
                        .equals(observed.acceptanceCriteria());
        String diagnostics = "observed title='" + observed.title() + "', acceptanceCriteria="
                + observed.acceptanceCriteria() + " - neither the pre-write pair (" + OLD_TITLE + ", "
                + OLD_CRITERION + ") nor the post-write pair (" + NEW_TITLE + ", " + NEW_CRITERION + ")";
        assertTrue(matchesPreWriteSnapshot || matchesPostWriteSnapshot, diagnostics);
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

    /**
     * Builds a {@link RequirementRepository} whose main row query - reached through whichever
     * port {@link KognioRdfRequirementRepository#findByCode} actually calls it through, {@code
     * DatasetHandle#sparqlQuery()} pre-fix or the transactional {@code DatasetTx} post-fix -
     * pauses after its first invocation until {@code readerPastMainQuery}'s barrier releases and
     * {@code writerCommitted}'s latch fires. Mirrors the shared {@code GuardedLifecycle}/{@code
     * GuardedHandle} pair (arknet-persistence-test-support), decorating {@code select} instead of
     * {@code contains}.
     */
    private RequirementRepository pausingAfterFirstSelectRepository(
            CyclicBarrier readerPastMainQuery, CountDownLatch writerCommitted) {
        PauseGate gate = new PauseGate(readerPastMainQuery, writerCommitted);
        DatasetLifecycle guarded = new PausingLifecycle(realLifecycle, gate);
        return KognioRdfRequirementRepositoryFactory.over(guarded, DisplayLocale.DEFAULT);
    }

    // ---- DatasetLifecycle decoration: pauses the reader's first select() call, through
    //      whichever port it arrives, until the writer's update has committed -----------------

    /**
     * The "pause once" synchronisation shared between {@link PausingSparqlQuery} (the pre-fix,
     * non-transactional read path reached via {@code DatasetHandle#sparqlQuery()}) and
     * {@link PausingSelectTx} (the post-fix, transactional read path reached via {@code
     * DatasetHandle#transactor()}) - whichever of the two the adapter code under test actually
     * calls first for its main row query pauses; the other is never touched by this test's
     * requirement. Firing on the very first {@code select} call regardless of which port it
     * arrived through is what lets this single test exercise both the pre-#171-fix and the
     * post-#171-fix code path unchanged.
     *
     * <p>{@code armed} is shared across every retry {@link
     * KognioRdfRequirementRepository#readInTransaction} makes too, not just the first attempt:
     * that method retries a lost {@code SERIALIZABLE} race by re-invoking {@code inTransaction}
     * with a brand-new {@code DatasetTx}, and without a flag shared across attempts each retry's
     * own first {@code select} would try to pause again on already-spent synchronisation -
     * hanging forever instead of letting the retried attempt run to completion.</p>
     */
    private static final class PauseGate {

        private final CyclicBarrier readerPastMainQuery;
        private final CountDownLatch writerCommitted;
        private final AtomicBoolean armed = new AtomicBoolean(true);

        PauseGate(CyclicBarrier readerPastMainQuery, CountDownLatch writerCommitted) {
            this.readerPastMainQuery = readerPastMainQuery;
            this.writerCommitted = writerCommitted;
        }

        void pauseIfArmed() {
            if (armed.compareAndSet(true, false)) {
                awaitBarrier(readerPastMainQuery);
                awaitLatch(writerCommitted);
            }
        }
    }

    private static final class PausingLifecycle implements DatasetLifecycle {

        private final DatasetLifecycle delegate;
        private final PauseGate gate;

        PausingLifecycle(DatasetLifecycle delegate, PauseGate gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return new PausingHandle(delegate.acquire(id), gate);
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

    private static final class PausingHandle implements DatasetHandle {

        private final DatasetHandle delegate;
        private final PauseGate gate;

        PausingHandle(DatasetHandle delegate, PauseGate gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public SparqlQuery sparqlQuery() {
            return new PausingSparqlQuery(delegate.sparqlQuery(), gate);
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
                    return real.inTransaction(tx -> fn.apply(new PausingSelectTx(tx, gate)));
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Pauses after its delegate's first {@code select(String)} call returns - the pre-fix,
     * non-transactional read path's would-be main row query - via {@code gate}. See {@link
     * PauseGate}'s javadoc for the full synchronisation story.
     */
    private static final class PausingSparqlQuery implements SparqlQuery {

        private final SparqlQuery delegate;
        private final PauseGate gate;

        PausingSparqlQuery(SparqlQuery delegate, PauseGate gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        public Stream<BindingSet> select(String query) {
            List<BindingSet> materialised = delegate.select(query).toList();
            gate.pauseIfArmed();
            return materialised.stream();
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

        @Override
        public boolean ask(String query) {
            return delegate.ask(query);
        }

        @Override
        public boolean ask(String query, Map<String, RDFTerm> bindings) {
            return delegate.ask(query, bindings);
        }
    }

    /**
     * Pauses after its delegate's first {@code select(String)} call returns - the post-fix,
     * transactional read path's main row query - via {@code gate}. See {@link PauseGate}'s
     * javadoc for the full synchronisation story.
     */
    private static final class PausingSelectTx implements DatasetTx {

        private final DatasetTx delegate;
        private final PauseGate gate;

        PausingSelectTx(DatasetTx delegate, PauseGate gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        public Stream<BindingSet> select(String query) {
            List<BindingSet> materialised = delegate.select(query).toList();
            gate.pauseIfArmed();
            return materialised.stream();
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

        @Override
        public boolean ask(String query) {
            return delegate.ask(query);
        }

        @Override
        public boolean ask(String query, Map<String, RDFTerm> bindings) {
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
        public boolean contains(IRI graph, BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
            return delegate.contains(graph, subject, predicate, object);
        }

        @Override
        public void update(String sparqlUpdate) {
            delegate.update(sparqlUpdate);
        }

        @Override
        public void update(String sparqlUpdate, Map<String, RDFTerm> bindings) {
            delegate.update(sparqlUpdate, bindings);
        }
    }
}
