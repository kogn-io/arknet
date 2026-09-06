// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclValidation;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.testsupport.GuardedLifecycle;

/**
 * Regression test for issue #168: {@link WriteFunnel#compareAndUpdate}'s head-CAS - the central
 * concurrency-token mechanism behind {@code req_update}, {@code term_update}, {@code
 * bc_link_term} and {@code uc_update} - had never run under genuine thread concurrency against a
 * real on-disk store. {@code WriteFunnelTest} exercises the same "second interleaving" (see
 * {@code compareAndUpdateTranslatesRecognisedCommitConflictIntoHeadMismatch}) but only against
 * {@code FakeTx}/{@code FakeTransactor}; {@code RequirementServiceRealStoreConcurrencyTest} runs
 * real threads against a real {@code NativeStore}, but only for {@code create}'s code-uniqueness
 * guard, not for {@code compareAndUpdate}.
 *
 * <p>Lives here rather than duplicated per bounded context (the scope question the issue left
 * open): every {@code compareAndUpdate} caller funnels through this one class, so one test against
 * the funnel itself covers all of them - {@code arknet-persistence-test-support}'s
 * {@link GuardedLifecycle} exists for exactly this purpose and already names {@code
 * beforeTransaction} as the hook for "a lost-update / compare-and-set race" in its own javadoc.
 * {@code GuardSyncTx} does not fit here: it is anchored on the <em>second</em> {@code contains()}
 * call, the shape of {@code create}'s two guards, and {@code compareAndUpdate} issues only one
 * {@code contains()} plus one head-reading {@code select()} - so
 * {@link #bothCallersPassTheHeadReadButOnlyOneCommits_theLoserFailsInsteadOfLosingAnUpdate} pins
 * that interleaving with a small local {@link Function}-based decorator anchored on that
 * {@code select()} instead (documented as the needed alternative in {@code GuardSyncTx}'s own
 * javadoc).</p>
 *
 * <p><strong>Both interleavings, deliberately in two separate methods.</strong> A CAS guard has
 * two distinct ways of catching a conflict, and one test method proves only one at a time (a
 * review of an earlier version of this class - see its history - confirmed this the hard way: a
 * single method built around
 * {@link #bothCallersPassTheHeadReadButOnlyOneCommits_theLoserFailsInsteadOfLosingAnUpdate}'s
 * scenario alone stayed green even with the synchronous head comparison itself commented out,
 * because both racers there start from the still-current head by construction, so that comparison
 * can never fire - only the commit-time translation is exercised).
 * {@link #firstCallerCommitsFullyThenASecondCallersStaleExpectedHeadFailsTheSynchronousComparison}
 * covers the other one: a caller whose read is already stale <em>before</em> its own transaction
 * even opens, via {@link GuardedLifecycle}'s other hook, {@code beforeTransaction} - "let the
 * interfering writer commit here", per that hook's own javadoc.</p>
 *
 * <p><strong>Timeout.</strong> No class-level timeout; the project-wide Surefire default backstops
 * a hang instead (kogn-io/arknet#458), mirroring {@code RequirementServiceRealStoreConcurrencyTest}.</p>
 */
class WriteFunnelCompareAndUpdateRealStoreConcurrencyTest {

    private static final DatasetId DATASET = new DatasetId("test-project");
    private static final String GRAPH_IRI = "https://example.org/graph";
    private static final String SUBJECT_IRI = "https://example.org/thing/1";
    private static final String WRITER_PREDICATE_IRI = "https://example.org/writtenBy";

    private final RDF rdf = new SimpleRdf();

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

    /**
     * Two genuinely overlapping {@code compareAndUpdate} calls, both starting from the same
     * observed {@code arkprov:head}: both pass the synchronous head comparison (neither has
     * committed yet, so both still see the original head), then both try to commit. Under
     * {@code SERIALIZABLE} the store detects the conflict at commit time - the loser's transaction
     * never lands, so its write is never lost, it simply never happens; it surfaces to the caller
     * as the {@code headMismatch} signal instead of silently overwriting the winner.
     */
    @Test
    void bothCallersPassTheHeadReadButOnlyOneCommits_theLoserFailsInsteadOfLosingAnUpdate()
            throws InterruptedException {
        String initialHead = seedSubject();

        CyclicBarrier bothHeadReadsDone = new CyclicBarrier(2);

        AtomicReference<Optional<RuntimeException>> racerAOutcome = new AtomicReference<>();
        AtomicReference<Optional<RuntimeException>> racerBOutcome = new AtomicReference<>();

        Thread racerA = new Thread(
                () -> racerAOutcome.set(compareAndUpdate("racer-A", initialHead, bothHeadReadsDone)), "racer-A");
        Thread racerB = new Thread(
                () -> racerBOutcome.set(compareAndUpdate("racer-B", initialHead, bothHeadReadsDone)), "racer-B");

        // when
        racerA.start();
        racerB.start();
        racerA.join();
        racerB.join();

        // then - exactly one racer's write committed, the other saw its commit rejected and
        // reported through the caller-facing headMismatch signal, never through a silently
        // discarded update.
        assertNotNull(racerAOutcome.get(), "racer-A must have finished");
        assertNotNull(racerBOutcome.get(), "racer-B must have finished");
        boolean aSucceeded = racerAOutcome.get().isEmpty();
        boolean bSucceeded = racerBOutcome.get().isEmpty();
        assertNotEquals(aSucceeded, bSucceeded, "exactly one racer must succeed, the other must be rejected");

        RuntimeException loserFailure = aSucceeded ? racerBOutcome.get().get() : racerAOutcome.get().get();
        assertSame(HeadMismatch.class, loserFailure.getClass());

        String winnerName = aSucceeded ? "racer-A" : "racer-B";
        String newHead = readHead().orElseThrow(() -> new AssertionError("winning write recorded no head"));
        assertNotEquals(initialHead, newHead, "the winning write must have advanced the head");
        assertEquals(List.of(winnerName), writersOfSubject(), "only the winner's write may be visible - "
                + "a lost commit must never surface as data, not even partially");
    }

    /**
     * The first interleaving: a caller commits fully <em>before</em> a second caller's
     * transaction even opens, so the second caller's {@code expectedHead} - read earlier, before
     * either write - is already stale by the time its own transaction's synchronous head
     * comparison runs. No commit-time conflict is involved here at all: the interfering write is
     * long done, so the store never sees two overlapping transactions.
     *
     * <p>Pinned via {@link GuardedLifecycle}'s {@code beforeTransaction} hook - "let the
     * interfering writer commit here", exactly as documented on that hook - instead of a barrier:
     * this interleaving needs no thread coordination, only a write guaranteed to have committed
     * before the next one's transaction opens.</p>
     */
    @Test
    void firstCallerCommitsFullyThenASecondCallersStaleExpectedHeadFailsTheSynchronousComparison() {
        String initialHead = seedSubject();
        WriteFunnel interferingFunnel = new WriteFunnel(realLifecycle, permissiveGate(), WriteFunnel.DEFAULT_WRITE_CONFLICT);

        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, Function.identity(),
                () -> interferingFunnel.compareAndUpdate(DATASET, GRAPH_IRI, SUBJECT_IRI, initialHead, candidate(),
                        null, Signals::unexpected, Signals::unexpected, tx -> markWriter(tx, "interferer")));
        WriteFunnel staleCallerFunnel = new WriteFunnel(guarded, permissiveGate(), WriteFunnel.DEFAULT_WRITE_CONFLICT);

        // when - the stale caller still hands in the head it observed before the interferer ran
        assertThrows(HeadMismatch.class,
                () -> staleCallerFunnel.compareAndUpdate(DATASET, GRAPH_IRI, SUBJECT_IRI, initialHead, candidate(),
                        null, Signals::unexpected, HeadMismatch::new, tx -> markWriter(tx, "stale-caller")));

        // then - the interferer's fully committed write is the only one visible; the rejected
        // caller's write never landed, not even partially
        assertEquals(List.of("interferer"), writersOfSubject());
        String newHead = readHead().orElseThrow(() -> new AssertionError("interfering write recorded no head"));
        assertNotEquals(initialHead, newHead, "the interfering write must have advanced the head");
    }

    /** Creates the racing subject and returns its {@code arkprov:head} right after the seed write. */
    private String seedSubject() {
        WriteFunnel seedFunnel = new WriteFunnel(realLifecycle, permissiveGate(), WriteFunnel.DEFAULT_WRITE_CONFLICT);
        // create()'s candidate graph is validated by the gate but not written by the funnel
        // itself - only the body writes model triples, exactly like every real out-adapter's
        // create() body does.
        seedFunnel.create(DATASET, GRAPH_IRI, SUBJECT_IRI, "THING-1", candidate(), null,
                Signals::unexpected, Signals::unexpected, tx -> tx.add(rdf.createIRI(GRAPH_IRI), candidate()));
        return readHead().orElseThrow(() -> new AssertionError("seed write recorded no head"));
    }

    /** Runs one {@code compareAndUpdate} call, pausing right after its head read at the barrier. */
    private Optional<RuntimeException> compareAndUpdate(String racerName, String expectedHead,
            CyclicBarrier bothHeadReadsDone) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new PauseAfterHeadReadTx(tx, () -> awaitBarrier(bothHeadReadsDone));
            }
            return tx;
        });
        WriteFunnel funnel = new WriteFunnel(guarded, permissiveGate(), WriteFunnel.DEFAULT_WRITE_CONFLICT);
        try {
            funnel.compareAndUpdate(DATASET, GRAPH_IRI, SUBJECT_IRI, expectedHead, candidate(), null,
                    Signals::unexpected, HeadMismatch::new, tx -> markWriter(tx, racerName));
            return Optional.empty();
        } catch (HeadMismatch mismatch) {
            return Optional.of(mismatch);
        }
    }

    private void markWriter(DatasetTx tx, String racerName) {
        Graph marker = rdf.createGraph();
        marker.add(rdf.createIRI(SUBJECT_IRI), rdf.createIRI(WRITER_PREDICATE_IRI), rdf.createLiteral(racerName));
        tx.add(rdf.createIRI(GRAPH_IRI), marker);
    }

    private List<String> writersOfSubject() {
        String query = "SELECT ?writer WHERE { GRAPH <" + GRAPH_IRI + "> { <" + SUBJECT_IRI + "> <"
                + WRITER_PREDICATE_IRI + "> ?writer } }";
        try (DatasetHandle handle = realLifecycle.acquire(DATASET)) {
            return handle.sparqlQuery().select(query)
                    .map(row -> row.getValue("writer").orElse(null))
                    .filter(io.kogn.rdf.terms.Literal.class::isInstance)
                    .map(value -> ((io.kogn.rdf.terms.Literal) value).getLexicalForm())
                    .toList();
        }
    }

    private Optional<String> readHead() {
        String query = "SELECT ?head WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <" + SUBJECT_IRI
                + "> <" + ArkprovVocabulary.HEAD + "> ?head } }";
        try (DatasetHandle handle = realLifecycle.acquire(DATASET)) {
            return handle.sparqlQuery().select(query)
                    .findFirst()
                    .flatMap(row -> row.getValue("head"))
                    .filter(IRI.class::isInstance)
                    .map(value -> ((IRI) value).getIRIString());
        }
    }

    private Graph candidate() {
        Graph graph = rdf.createGraph();
        graph.add(rdf.createIRI(SUBJECT_IRI), VocabRdf.TYPE, rdf.createIRI("https://example.org/Type"));
        return graph;
    }

    /** A gate that never rejects - this test's promise is the funnel's CAS, not SHACL. */
    private ShaclWriteGate permissiveGate() {
        RDF graphs = new SimpleRdf();
        ShaclValidation alwaysConforms = (data, shapes, options) -> new ShaclReport(true, List.of());
        return new ShaclWriteGate(alwaysConforms, graphs.createGraph(), graphs.createGraph(),
                ValidationOptions.defaults(), DisplayLocale.DEFAULT);
    }

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

    /** This test's {@code headMismatch} signal - distinct so it cannot be confused with a real BC's. */
    private static final class HeadMismatch extends RuntimeException {
        private HeadMismatch() {
            super("head mismatch");
        }
    }

    private static final class Signals {
        private static RuntimeException unexpected() {
            return new IllegalStateException("signal must not be requested in this scenario");
        }

        private Signals() {
        }
    }

    /**
     * A pass-through {@link DatasetTx} that runs {@code afterHeadRead} exactly once, right after
     * its delegate's first {@code select()} call returns - {@code compareAndUpdate}'s only
     * in-transaction {@code select} is the head read itself (see {@link WriteFunnel#readHead}), so
     * this is the exact point where a caller has decided "my expected head still matches" but has
     * written nothing yet. Unlike {@code GuardSyncTx} (anchored on the second {@code contains()}
     * call, the shape of {@code create}'s two guards), this decorator is local to this test rather
     * than shared, per {@code arknet-persistence-test-support}'s own rule for a pause anchor with
     * exactly one caller.
     */
    private static final class PauseAfterHeadReadTx implements DatasetTx {

        private final DatasetTx delegate;
        private final Runnable afterHeadRead;
        private boolean fired;

        private PauseAfterHeadReadTx(DatasetTx delegate, Runnable afterHeadRead) {
            this.delegate = delegate;
            this.afterHeadRead = afterHeadRead;
        }

        @Override
        public java.util.stream.Stream<BindingSet> select(String query) {
            java.util.stream.Stream<BindingSet> result = delegate.select(query).toList().stream();
            if (!fired) {
                fired = true;
                afterHeadRead.run();
            }
            return result;
        }

        @Override
        public java.util.stream.Stream<BindingSet> select(String query, Map<String, RDFTerm> bindings) {
            return delegate.select(query, bindings);
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
        public boolean contains(IRI graph, io.kogn.rdf.terms.BlankNodeOrIRI subject, IRI predicate,
                RDFTerm object) {
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
