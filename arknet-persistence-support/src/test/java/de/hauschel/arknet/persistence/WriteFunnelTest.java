// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclResult;
import io.kogn.rdf.shacl.ShaclValidation;
import io.kogn.rdf.shacl.Severity;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Unit test for the shared {@link WriteFunnel}.
 *
 * <p>Drives the funnel against hand-rolled fakes of the kognio-rdf dataset port (recording
 * lifecycle/transactor/tx) and a recording {@link ShaclValidation} behind a real
 * {@link ShaclWriteGate} - what is under test is the funnel's own contract: gate before
 * transaction, both {@code ASK} checks inside the transaction, the caller-supplied signals for
 * each rejection, and the create-only commit-conflict translation (issue #144). Whether a real
 * store honours that contract is the business of each adapter's own tests.</p>
 */
class WriteFunnelTest {

    private static final String GRAPH_IRI = "https://example.org/graph";
    private static final String SUBJECT_IRI = "https://example.org/thing/1";
    private static final String CODE = "THING-1";

    private static final String ASK_SUBJECT =
            "ASK { GRAPH <" + GRAPH_IRI + "> { <" + SUBJECT_IRI + "> ?p ?o } }";
    private static final String ASK_CODE = "ASK { GRAPH <" + GRAPH_IRI + "> { "
            + "?s <http://purl.org/dc/terms/identifier> \"" + CODE + "\" } }";

    private final RDF rdf = new SimpleRdf();

    @Test
    void createWritesWhenSubjectAndCodeAreNew() {
        Fixture fixture = new Fixture(List.of(false, false));

        List<DatasetTx> bodyCalls = new ArrayList<>();
        fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, bodyCalls::add);

        assertEquals(List.of(ASK_SUBJECT, ASK_CODE), fixture.tx.askQueries);
        assertEquals(1, bodyCalls.size());
        assertSame(fixture.tx, bodyCalls.get(0), "body must run on the live transaction");
        assertTrue(fixture.handle.closed, "handle must be released");
    }

    @Test
    void createRejectsExistingSubjectWithAlreadyExistsSignal() {
        Fixture fixture = new Fixture(List.of(true));
        RuntimeException signal = new RuntimeException("already exists");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, () -> signal, Signals::unexpected, Signals.noBody()));

        assertSame(signal, thrown);
        assertFalse(fixture.bodyRan, "body must not run after a rejected check");
        assertTrue(fixture.handle.closed);
    }

    @Test
    void createRejectsExistingCodeWithDuplicateCodeSignal() {
        Fixture fixture = new Fixture(List.of(false, true));
        RuntimeException signal = new RuntimeException("duplicate code");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, () -> signal, Signals.noBody()));

        assertSame(signal, thrown);
        assertFalse(fixture.bodyRan);
    }

    /**
     * Issue #144: two genuinely overlapping {@code SERIALIZABLE} transactions both pass the
     * {@code ASK}s, and the loser fails at commit with the store's own exception - the funnel
     * must translate exactly the recognised conflict into the {@code duplicateCode} signal.
     */
    @Test
    void createTranslatesRecognisedCommitConflictIntoDuplicateCode() {
        RuntimeException storeConflict = new RuntimeException("store commit conflict");
        Fixture fixture = new Fixture(List.of(false, false), storeConflict, e -> e == storeConflict);
        RuntimeException signal = new RuntimeException("duplicate code");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, () -> signal, tx -> { }));

        assertSame(signal, thrown);
        assertTrue(fixture.handle.closed, "handle must be released even on a conflict");
    }

    @Test
    void createRethrowsUnrecognisedFailureUntranslated() {
        RuntimeException unrelated = new RuntimeException("something else broke");
        Fixture fixture = new Fixture(List.of(false, false), unrelated, e -> false);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, Signals::unexpected, tx -> { }));

        assertSame(unrelated, thrown);
    }

    @Test
    void createEscapesCodeInAskQuery() {
        Fixture fixture = new Fixture(List.of(false, false));

        fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, "TH\"ING\n1",
                candidate(), null, Signals::unexpected, Signals::unexpected, tx -> { });

        assertTrue(fixture.tx.askQueries.get(1).contains("\"TH\\\"ING\\n1\""),
                "code must be SPARQL-escaped, got: " + fixture.tx.askQueries.get(1));
    }

    @Test
    void updateWritesWhenSubjectExists() {
        Fixture fixture = new Fixture(List.of(true));

        List<DatasetTx> bodyCalls = new ArrayList<>();
        fixture.funnel().update(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                candidate(), null, Signals::unexpected, bodyCalls::add);

        assertEquals(List.of(ASK_SUBJECT), fixture.tx.askQueries, "update runs no code check");
        assertEquals(1, bodyCalls.size());
        assertTrue(fixture.handle.closed);
    }

    @Test
    void updateRejectsMissingSubjectWithNotFoundSignal() {
        Fixture fixture = new Fixture(List.of(false));
        RuntimeException signal = new RuntimeException("not found");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().update(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                        candidate(), null, () -> signal, Signals.noBody()));

        assertSame(signal, thrown);
        assertFalse(fixture.bodyRan);
    }

    /**
     * The pre-funnel adapters translated a commit conflict only on create (issue #144's
     * signal is a code collision, which an update cannot cause) and rethrew it raw on update -
     * preserved deliberately, not repaired in passing.
     */
    @Test
    void updateDoesNotTranslateCommitConflict() {
        RuntimeException storeConflict = new RuntimeException("store commit conflict");
        Fixture fixture = new Fixture(List.of(true), storeConflict, e -> e == storeConflict);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().update(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                        candidate(), null, Signals::unexpected, tx -> { }));

        assertSame(storeConflict, thrown, "update must rethrow the store's own exception untouched");
    }

    /**
     * The gate is structurally unavoidable and runs before anything is acquired: a violating
     * candidate must never even reach the dataset lifecycle.
     */
    @Test
    void gateViolationPreventsAcquisition() {
        ShaclReport violation = new ShaclReport(false,
                List.of(new ShaclResult(SUBJECT_IRI, null, Severity.VIOLATION, "bad")));
        Fixture fixture = new Fixture(List.of(), violation, null, e -> false);

        assertThrows(WriteConstraintViolationException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, Signals::unexpected, Signals.noBody()));

        assertFalse(fixture.lifecycle.acquired, "gate must reject before the dataset is acquired");
    }

    /** Issue #63: the validation-only context must reach the gate; {@code null} means none. */
    @Test
    void assertedContextReachesGate() {
        Fixture fixture = new Fixture(List.of(true));
        Graph context = rdf.createGraph();
        context.add(rdf.createIRI("https://example.org/neighbour"),
                rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                rdf.createIRI("https://example.org/Type"));

        fixture.funnel().update(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                candidate(), context, Signals::unexpected, tx -> { });

        assertNotNull(fixture.validation.data);
        assertTrue(containsSubject(fixture.validation.data, "https://example.org/neighbour"),
                "asserted context must be part of the validated data");
        assertTrue(containsSubject(fixture.validation.data, SUBJECT_IRI));
    }

    private Graph candidate() {
        Graph graph = rdf.createGraph();
        graph.add(rdf.createIRI(SUBJECT_IRI),
                rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                rdf.createIRI("https://example.org/Type"));
        return graph;
    }

    private static boolean containsSubject(ReadableGraph graph, String subject) {
        return graph.stream()
                .map(Triple::getSubject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .anyMatch(iri -> iri.getIRIString().equals(subject));
    }

    /** Signal/body helpers marking paths a test expects to stay unreached. */
    private static final class Signals {

        private static RuntimeException unexpected() {
            return new IllegalStateException("signal must not be requested in this scenario");
        }

        private static java.util.function.Consumer<DatasetTx> noBody() {
            return tx -> {
                throw new AssertionError("body must not run in this scenario");
            };
        }

        private Signals() {
        }
    }

    /** One funnel wiring per test: recording lifecycle/handle/tx plus a recording gate. */
    private final class Fixture {

        private final DatasetId dataset = new DatasetId("workspace");
        private final FakeTx tx;
        private final FakeHandle handle;
        private final FakeLifecycle lifecycle;
        private final RecordingValidation validation;
        private final Predicate<RuntimeException> isWriteConflict;
        private boolean bodyRan;

        private Fixture(List<Boolean> askAnswers) {
            this(askAnswers, null, e -> false);
        }

        private Fixture(List<Boolean> askAnswers, RuntimeException commitFailure,
                Predicate<RuntimeException> isWriteConflict) {
            this(askAnswers, new ShaclReport(true, List.of()), commitFailure, isWriteConflict);
        }

        private Fixture(List<Boolean> askAnswers, ShaclReport gateReport,
                RuntimeException commitFailure, Predicate<RuntimeException> isWriteConflict) {
            this.tx = new FakeTx(askAnswers);
            this.handle = new FakeHandle(new FakeTransactor(tx, commitFailure));
            this.lifecycle = new FakeLifecycle(handle);
            this.validation = new RecordingValidation(gateReport);
            this.isWriteConflict = isWriteConflict;
        }

        private WriteFunnel funnel() {
            RDF graphs = new SimpleRdf();
            ShaclWriteGate gate = new ShaclWriteGate(validation, graphs.createGraph(),
                    graphs.createGraph(), ValidationOptions.defaults());
            return new WriteFunnel(lifecycle, gate, isWriteConflict);
        }
    }

    private static final class FakeLifecycle implements DatasetLifecycle {

        private final DatasetHandle handle;
        private boolean acquired;

        private FakeLifecycle(DatasetHandle handle) {
            this.handle = handle;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            acquired = true;
            return handle;
        }

        @Override
        public void close(DatasetId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(DatasetId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<DatasetId> list() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeHandle implements DatasetHandle {

        private final DatasetTransactor transactor;
        private boolean closed;

        private FakeHandle(DatasetTransactor transactor) {
            this.transactor = transactor;
        }

        @Override
        public DatasetTransactor transactor() {
            return transactor;
        }

        @Override
        public GraphStore graphStore() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SparqlQuery sparqlQuery() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SparqlUpdate sparqlUpdate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Runs the work, then fails "at commit" if configured - after the body already executed. */
    private static final class FakeTransactor implements DatasetTransactor {

        private final DatasetTx tx;
        private final RuntimeException commitFailure;

        private FakeTransactor(DatasetTx tx, RuntimeException commitFailure) {
            this.tx = tx;
            this.commitFailure = commitFailure;
        }

        @Override
        public <T> T inTransaction(Function<DatasetTx, T> work) {
            T result = work.apply(tx);
            if (commitFailure != null) {
                throw commitFailure;
            }
            return result;
        }
    }

    /** Records every {@code ASK} and answers from a scripted queue. */
    private static final class FakeTx implements DatasetTx {

        private final List<String> askQueries = new ArrayList<>();
        private final Deque<Boolean> askAnswers;

        private FakeTx(List<Boolean> askAnswers) {
            this.askAnswers = new ArrayDeque<>(askAnswers);
        }

        @Override
        public boolean ask(String sparql) {
            askQueries.add(sparql);
            Boolean answer = askAnswers.poll();
            if (answer == null) {
                throw new AssertionError("unscripted ASK: " + sparql);
            }
            return answer;
        }

        @Override
        public void add(IRI namedGraph, ReadableGraph triples) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(IRI namedGraph, ReadableGraph triples) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear(IRI namedGraph) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(String sparql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<BindingSet> select(String sparql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReadableGraph construct(String sparql) {
            throw new UnsupportedOperationException();
        }
    }

    /** Fake {@link ShaclValidation} recording what the gate handed it (same as the gate's test). */
    private static final class RecordingValidation implements ShaclValidation {

        private final ShaclReport report;
        private ReadableGraph data;

        private RecordingValidation(ShaclReport report) {
            this.report = report;
        }

        @Override
        public ShaclReport validate(ReadableGraph data, ReadableGraph shapes, ValidationOptions options) {
            this.data = data;
            return report;
        }
    }
}
