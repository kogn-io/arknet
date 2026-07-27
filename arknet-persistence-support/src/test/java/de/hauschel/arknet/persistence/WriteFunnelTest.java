// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.shacl.ShaclMessage;
import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclResult;
import io.kogn.rdf.shacl.ShaclValidation;
import io.kogn.rdf.shacl.Severity;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.DisplayLocale;

/**
 * Unit test for the shared {@link WriteFunnel}.
 *
 * <p>Drives the funnel against hand-rolled fakes of the kognio-rdf dataset port (recording
 * lifecycle/transactor/tx) and a recording {@link ShaclValidation} behind a real
 * {@link ShaclWriteGate} - what is under test is the funnel's own contract: gate before
 * transaction, both {@code contains} existence checks inside the transaction, the caller-supplied
 * signals for each rejection, and the create-only commit-conflict translation (issue #144).
 * Whether a real store honours that contract is the business of each adapter's own tests.</p>
 */
class WriteFunnelTest {

    private static final String GRAPH_IRI = "https://example.org/graph";
    private static final String SUBJECT_IRI = "https://example.org/thing/1";
    private static final String CODE = "THING-1";
    private static final String IDENTIFIER_PROPERTY_IRI = "http://purl.org/dc/terms/identifier";

    private final RDF rdf = new SimpleRdf();

    @Test
    void createWritesWhenSubjectAndCodeAreNew() {
        Fixture fixture = new Fixture(List.of(false, false));

        List<DatasetTx> bodyCalls = new ArrayList<>();
        fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, bodyCalls::add);

        assertEquals(2, fixture.tx.containsCalls.size());
        assertSubjectExistenceCheck(fixture.tx.containsCalls.get(0), GRAPH_IRI, SUBJECT_IRI);
        assertCodeExistenceCheck(fixture.tx.containsCalls.get(1), GRAPH_IRI, CODE);
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
     * existence checks, and the loser fails at commit with the store's own exception - the
     * funnel must translate exactly the recognised conflict into the {@code duplicateCode}
     * signal.
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

    /**
     * The predicate the four out-adapters actually inject carries the #144 invariant: point it at
     * the wrong type (a superclass, a {@code RuntimeException} wrapper) and a lost race would stop
     * being translated - visible only in a real race, which is exactly what the
     * {@code *RealStoreConcurrencyTest}s cover flakily (issue #171). Pinned here directly instead.
     */
    @Test
    void defaultWriteConflictRecognisesOnlyTheStoresConcurrencyConflict() {
        assertTrue(WriteFunnel.DEFAULT_WRITE_CONFLICT.test(
                new ConcurrencyConflictException("lost SERIALIZABLE conflict", null)));
        assertFalse(WriteFunnel.DEFAULT_WRITE_CONFLICT.test(new IllegalStateException("unrelated")));
        assertFalse(WriteFunnel.DEFAULT_WRITE_CONFLICT.test(
                new RuntimeException("wrapper", new ConcurrencyConflictException("cause", null))),
                "a merely wrapped conflict is not the store's own signal");
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

    /**
     * The code-uniqueness check compares an {@code io.kogn.rdf.terms.Literal} directly via
     * {@link DatasetTx#contains} - no SPARQL string is built for it, so a code containing
     * characters that would need escaping in a hand-rolled {@code ASK} query (quotes, newlines)
     * must reach the check verbatim, unescaped.
     */
    @Test
    void createPassesCodeVerbatimToExistenceCheck() {
        Fixture fixture = new Fixture(List.of(false, false));
        String code = "TH\"ING\n1";

        fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, code,
                candidate(), null, Signals::unexpected, Signals::unexpected, tx -> { });

        assertCodeExistenceCheck(fixture.tx.containsCalls.get(1), GRAPH_IRI, code);
    }

    @Test
    void updateWritesWhenSubjectExists() {
        Fixture fixture = new Fixture(List.of(true));

        List<DatasetTx> bodyCalls = new ArrayList<>();
        fixture.funnel().update(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                candidate(), null, Signals::unexpected, bodyCalls::add);

        assertEquals(1, fixture.tx.containsCalls.size(), "update runs no code check");
        assertSubjectExistenceCheck(fixture.tx.containsCalls.get(0), GRAPH_IRI, SUBJECT_IRI);
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
                List.of(new ShaclResult(SUBJECT_IRI, null, Severity.VIOLATION,
                        List.of(ShaclMessage.untagged("bad")))));
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

    // ---- compareAndUpdate (ADR-014 decisions 3+4, issue #167) ---------------------------

    @Test
    void compareAndUpdateWritesWhenSubjectExistsAndHeadMatches() {
        Fixture fixture = new Fixture(List.of(true));
        String head = "https://w3id.org/arknet/revision/current";
        fixture.tx.headAnswers.add(head);

        List<DatasetTx> bodyCalls = new ArrayList<>();
        fixture.funnel().compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, head,
                candidate(), null, Signals::unexpected, Signals::unexpected, bodyCalls::add);

        assertEquals(1, fixture.tx.containsCalls.size(), "compareAndUpdate runs no code check");
        assertSubjectExistenceCheck(fixture.tx.containsCalls.get(0), GRAPH_IRI, SUBJECT_IRI);
        assertEquals(1, bodyCalls.size());
        assertTrue(fixture.handle.closed);
    }

    /** No revision recorded yet is a valid, comparable state: {@code null} must match no head. */
    @Test
    void compareAndUpdateTreatsNoExpectedHeadAsMatchingNoRecordedHead() {
        Fixture fixture = new Fixture(List.of(true));

        List<DatasetTx> bodyCalls = new ArrayList<>();
        fixture.funnel().compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, null,
                candidate(), null, Signals::unexpected, Signals::unexpected, bodyCalls::add);

        assertEquals(1, bodyCalls.size());
    }

    @Test
    void compareAndUpdateRejectsMissingSubjectWithNotFoundSignal() {
        Fixture fixture = new Fixture(List.of(false));
        RuntimeException signal = new RuntimeException("not found");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, null,
                        candidate(), null, () -> signal, Signals::unexpected, Signals.noBody()));

        assertSame(signal, thrown);
        assertFalse(fixture.bodyRan);
    }

    /** The core of the CAS guard: a caller whose observed head is no longer current is rejected. */
    @Test
    void compareAndUpdateRejectsStaleExpectedHeadWithHeadMismatchSignal() {
        Fixture fixture = new Fixture(List.of(true));
        fixture.tx.headAnswers.add("https://w3id.org/arknet/revision/actual");
        RuntimeException signal = new RuntimeException("head mismatch");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                        "https://w3id.org/arknet/revision/stale", candidate(), null,
                        Signals::unexpected, () -> signal, Signals.noBody()));

        assertSame(signal, thrown);
        assertFalse(fixture.bodyRan, "body must not run after a rejected head comparison");
    }

    /**
     * Issue #144's "second interleaving", now also relevant to an update path: two callers can
     * both observe the same expected head and both pass the synchronous comparison before either
     * commits, under {@code SERIALIZABLE} isolation - the loser's commit itself is then rejected,
     * and must translate into the identical {@code headMismatch} signal.
     */
    @Test
    void compareAndUpdateTranslatesRecognisedCommitConflictIntoHeadMismatch() {
        RuntimeException storeConflict = new RuntimeException("store commit conflict");
        Fixture fixture = new Fixture(List.of(true), storeConflict, e -> e == storeConflict);
        RuntimeException signal = new RuntimeException("head mismatch");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, null,
                        candidate(), null, Signals::unexpected, () -> signal, tx -> { }));

        assertSame(signal, thrown);
        assertTrue(fixture.handle.closed, "handle must be released even on a conflict");
    }

    /** Same revision-recording contract as {@link #update}, now proven for the CAS path too. */
    @Test
    void compareAndUpdateChainsTheRevisionToThePreviousHeadAndRewritesIt() {
        Fixture fixture = new Fixture(List.of(true));
        String previous = "https://w3id.org/arknet/revision/previous";
        fixture.tx.headAnswers.add(previous);

        fixture.funnel().compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, previous,
                candidate(), null, Signals::unexpected, Signals::unexpected, tx -> { });

        ReadableGraph provenance = fixture.tx.provenanceAdded();
        List<String> revisions = subjectsTyped(provenance, ArkprovVocabulary.REVISION_TYPE);
        assertEquals(1, revisions.size(), "exactly one revision per write");
        String revision = revisions.get(0);
        assertEquals(List.of(previous), objectIris(provenance, revision, ArkprovVocabulary.WAS_REVISION_OF),
                "the new revision must supersede the previous head");
        assertEquals(List.of(revision), objectIris(provenance, SUBJECT_IRI, ArkprovVocabulary.HEAD));
    }

    // ---- revision recording (ADR-014, revision basis) -----------------------------------

    /**
     * ADR-014 decision 2: every write through the funnel records exactly one immutable
     * revision - a {@code prov:Entity}/{@code arkprov:Revision} generated by a
     * {@code prov:Activity} - plus the resource's {@code arkprov:head} pointer, inside the
     * same write transaction as the model write.
     */
    @Test
    void createRecordsExactlyOneRevisionAndTheHeadInsideTheWriteTransaction() {
        Fixture fixture = new Fixture(List.of(false, false));

        fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, tx -> { });

        ReadableGraph provenance = fixture.tx.provenanceAdded();
        List<String> revisions = subjectsTyped(provenance, ArkprovVocabulary.REVISION_TYPE);
        assertEquals(1, revisions.size(), "exactly one revision per write");
        String revision = revisions.get(0);
        assertEquals(List.of(revision), subjectsTyped(provenance, ArkprovVocabulary.ENTITY_TYPE),
                "the revision must also be a plain prov:Entity");
        assertEquals(List.of(revision), objectIris(provenance, SUBJECT_IRI, ArkprovVocabulary.HEAD),
                "the head must point at the new revision");
        assertEquals(List.of(SUBJECT_IRI),
                objectIris(provenance, revision, ArkprovVocabulary.SPECIALIZATION_OF));
        List<String> activities = objectIris(provenance, revision, ArkprovVocabulary.WAS_GENERATED_BY);
        assertEquals(1, activities.size(), "the revision must be generated by one activity");
        assertEquals(activities, subjectsTyped(provenance, ArkprovVocabulary.ACTIVITY_TYPE));
        assertTrue(hasStatement(provenance, revision, ArkprovVocabulary.GENERATED_AT_TIME),
                "the revision must carry its generation instant");
        assertTrue(objectIris(provenance, revision, ArkprovVocabulary.WAS_REVISION_OF).isEmpty(),
                "the first revision has no predecessor");
        assertTrue(fixture.tx.updates.isEmpty(), "no head existed, so nothing must be deleted");
    }

    /**
     * On a later write the previous head becomes the new revision's {@code prov:wasRevisionOf}
     * predecessor and the old {@code arkprov:head} triple is removed - all in the same
     * transaction, so the head is rewritten, never duplicated.
     */
    @Test
    void updateChainsTheRevisionToThePreviousHeadAndRewritesIt() {
        Fixture fixture = new Fixture(List.of(true));
        String previous = "https://w3id.org/arknet/revision/previous";
        fixture.tx.headAnswers.add(previous);

        fixture.funnel().update(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                candidate(), null, Signals::unexpected, tx -> { });

        ReadableGraph provenance = fixture.tx.provenanceAdded();
        List<String> revisions = subjectsTyped(provenance, ArkprovVocabulary.REVISION_TYPE);
        assertEquals(1, revisions.size(), "exactly one revision per write");
        String revision = revisions.get(0);
        assertEquals(List.of(previous), objectIris(provenance, revision, ArkprovVocabulary.WAS_REVISION_OF),
                "the new revision must supersede the previous head");
        assertEquals(List.of(revision), objectIris(provenance, SUBJECT_IRI, ArkprovVocabulary.HEAD));
        assertEquals(1, fixture.tx.updates.size(), "the old head triple must be deleted");
        String deleteHead = fixture.tx.updates.get(0);
        assertTrue(deleteHead.contains("<" + ArkprovVocabulary.PROVENANCE_GRAPH + ">")
                && deleteHead.contains("<" + SUBJECT_IRI + ">")
                && deleteHead.contains("<" + ArkprovVocabulary.HEAD + ">"),
                "head delete must target this subject's head in the provenance graph, got: " + deleteHead);
    }

    /**
     * The generation instant comes from the injected clock. It is the only value a revision
     * carries that is not derived from the write's inputs, so an ambient {@code Instant.now()}
     * would leave {@code prov:generatedAtTime} permanently unassertable - both here and for the
     * "revisions between T1 and T2" read path ADR-011 still owes.
     */
    @Test
    void theRevisionCarriesTheGenerationInstantOfTheInjectedClock() {
        Fixture fixture = new Fixture(List.of(false, false));
        Instant fixed = Instant.parse("2026-07-26T18:30:00Z");

        fixture.funnelAt(Clock.fixed(fixed, ZoneOffset.UTC)).create(fixture.dataset, GRAPH_IRI,
                SUBJECT_IRI, CODE, candidate(), null, Signals::unexpected, Signals::unexpected,
                tx -> { });

        ReadableGraph provenance = fixture.tx.provenanceAdded();
        String revision = subjectsTyped(provenance, ArkprovVocabulary.REVISION_TYPE).get(0);
        List<Literal> instants = literalsOf(provenance, revision, ArkprovVocabulary.GENERATED_AT_TIME);
        assertEquals(1, instants.size(), "exactly one generation instant per revision");
        assertEquals(fixed.toString(), instants.get(0).getLexicalForm());
        assertEquals(VocabXsd.DATETIME.getIRIString(), instants.get(0).getDatatype().getIRIString(),
                "the instant must be typed xsd:dateTime, not a plain string");
    }

    /** A rejected write (here: subject already exists) must leave no revision behind. */
    @Test
    void rejectedCreateRecordsNoRevision() {
        Fixture fixture = new Fixture(List.of(true));

        assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, () -> new RuntimeException("already exists"),
                        Signals::unexpected, Signals.noBody()));

        assertTrue(fixture.tx.provenanceStatements().isEmpty(),
                "a rejected write must not record a revision");
        assertTrue(fixture.tx.updates.isEmpty());
    }

    /** A failing body aborts the transaction before any revision is recorded. */
    @Test
    void failingBodyRecordsNoRevision() {
        Fixture fixture = new Fixture(List.of(false, false));
        RuntimeException failure = new RuntimeException("body failed");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, Signals::unexpected,
                        tx -> {
                            throw failure;
                        }));

        assertSame(failure, thrown);
        assertTrue(fixture.tx.provenanceStatements().isEmpty(),
                "a failed write must not record a revision");
    }

    private static List<String> subjectsTyped(ReadableGraph graph, String type) {
        return graph.stream()
                .filter(triple -> triple.getPredicate().getIRIString().equals(VocabRdf.TYPE.getIRIString()))
                .filter(triple -> triple.getObject() instanceof IRI iri && iri.getIRIString().equals(type))
                .map(triple -> ((IRI) triple.getSubject()).getIRIString())
                .toList();
    }

    private static List<String> objectIris(ReadableGraph graph, String subject, String predicate) {
        return graph.stream()
                .filter(triple -> triple.getSubject() instanceof IRI iri && iri.getIRIString().equals(subject))
                .filter(triple -> triple.getPredicate().getIRIString().equals(predicate))
                .filter(triple -> triple.getObject() instanceof IRI)
                .map(triple -> ((IRI) triple.getObject()).getIRIString())
                .toList();
    }

    private static List<Literal> literalsOf(ReadableGraph graph, String subject, String predicate) {
        return graph.stream()
                .filter(triple -> triple.getSubject() instanceof IRI iri && iri.getIRIString().equals(subject))
                .filter(triple -> triple.getPredicate().getIRIString().equals(predicate))
                .filter(triple -> triple.getObject() instanceof Literal)
                .map(triple -> (Literal) triple.getObject())
                .toList();
    }

    private static boolean hasStatement(ReadableGraph graph, String subject, String predicate) {
        return graph.stream()
                .anyMatch(triple -> triple.getSubject() instanceof IRI iri
                        && iri.getIRIString().equals(subject)
                        && triple.getPredicate().getIRIString().equals(predicate));
    }

    private Graph candidate() {
        Graph graph = rdf.createGraph();
        graph.add(rdf.createIRI(SUBJECT_IRI),
                rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                rdf.createIRI("https://example.org/Type"));
        return graph;
    }

    /** Asserts a {@code contains} call checking whether {@code subject} exists at all (issue #173). */
    private static void assertSubjectExistenceCheck(FakeTx.ContainsCall call, String graph, String subject) {
        assertEquals(graph, call.namedGraph().getIRIString());
        assertEquals(subject, ((IRI) call.subject()).getIRIString());
        assertNull(call.predicate(), "a subject existence check leaves the predicate wildcarded");
        assertNull(call.object(), "a subject existence check leaves the object wildcarded");
    }

    /**
     * Asserts a {@code contains} call checking whether any subject already carries {@code code}
     * on {@code dcterms:identifier} (issue #173).
     */
    private static void assertCodeExistenceCheck(FakeTx.ContainsCall call, String graph, String code) {
        assertEquals(graph, call.namedGraph().getIRIString());
        assertNull(call.subject(), "a code existence check leaves the subject wildcarded");
        assertEquals(IDENTIFIER_PROPERTY_IRI, call.predicate().getIRIString());
        assertEquals(code, ((Literal) call.object()).getLexicalForm());
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

        private Fixture(List<Boolean> containsAnswers) {
            this(containsAnswers, null, e -> false);
        }

        private Fixture(List<Boolean> containsAnswers, RuntimeException commitFailure,
                Predicate<RuntimeException> isWriteConflict) {
            this(containsAnswers, new ShaclReport(true, List.of()), commitFailure, isWriteConflict);
        }

        private Fixture(List<Boolean> containsAnswers, ShaclReport gateReport,
                RuntimeException commitFailure, Predicate<RuntimeException> isWriteConflict) {
            this.tx = new FakeTx(containsAnswers);
            this.handle = new FakeHandle(new FakeTransactor(tx, commitFailure));
            this.lifecycle = new FakeLifecycle(handle);
            this.validation = new RecordingValidation(gateReport);
            this.isWriteConflict = isWriteConflict;
        }

        private WriteFunnel funnel() {
            return funnelAt(Clock.systemUTC());
        }

        private WriteFunnel funnelAt(Clock clock) {
            RDF graphs = new SimpleRdf();
            ShaclWriteGate gate = new ShaclWriteGate(validation, graphs.createGraph(),
                    graphs.createGraph(), ValidationOptions.defaults(), DisplayLocale.DEFAULT);
            return new WriteFunnel(lifecycle, gate, isWriteConflict, clock);
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

    /**
     * Records every {@code contains} existence check (answered from a scripted queue), every
     * {@code SELECT} (answered with the scripted {@code headAnswers} IRIs bound to {@code ?head}
     * - the funnel's only in-transaction {@code SELECT} is the previous-head lookup), every
     * {@code update} and every {@code add} - so the tests can assert exactly what the revision
     * recording wrote.
     */
    private static final class FakeTx implements DatasetTx {

        private record RecordedAdd(IRI graph, ReadableGraph triples) {
        }

        private record ContainsCall(IRI namedGraph, BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
        }

        private final List<ContainsCall> containsCalls = new ArrayList<>();
        private final Deque<Boolean> containsAnswers;
        private final List<String> selectQueries = new ArrayList<>();
        private final List<String> headAnswers = new ArrayList<>();
        private final List<String> updates = new ArrayList<>();
        private final List<RecordedAdd> adds = new ArrayList<>();

        private FakeTx(List<Boolean> containsAnswers) {
            this.containsAnswers = new ArrayDeque<>(containsAnswers);
        }

        @Override
        public boolean ask(String sparql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean ask(String sparql, java.util.Map<String, RDFTerm> bindings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long add(IRI namedGraph, ReadableGraph triples) {
            adds.add(new RecordedAdd(namedGraph, triples));
            return triples.stream().count();
        }

        @Override
        public long remove(IRI namedGraph, ReadableGraph triples) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear(IRI namedGraph) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReadableGraph export(IRI namedGraph) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count(IRI namedGraph) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean contains(IRI namedGraph, BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
            containsCalls.add(new ContainsCall(namedGraph, subject, predicate, object));
            Boolean answer = containsAnswers.poll();
            if (answer == null) {
                throw new AssertionError(
                        "unscripted contains() check: " + namedGraph + " " + subject + " " + predicate + " "
                                + object);
            }
            return answer;
        }

        @Override
        public void update(String sparql) {
            updates.add(sparql);
        }

        @Override
        public void update(String sparql, java.util.Map<String, RDFTerm> bindings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<BindingSet> select(String sparql) {
            selectQueries.add(sparql);
            RDF terms = new SimpleRdf();
            return headAnswers.stream()
                    .map(iri -> (BindingSet) new SingleBinding("head", terms.createIRI(iri)));
        }

        @Override
        public Stream<BindingSet> select(String sparql, java.util.Map<String, RDFTerm> bindings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReadableGraph construct(String sparql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReadableGraph construct(String sparql, java.util.Map<String, RDFTerm> bindings) {
            throw new UnsupportedOperationException();
        }

        /** The one graph added to the provenance graph - fails if there is none or several. */
        private ReadableGraph provenanceAdded() {
            List<RecordedAdd> provenance = adds.stream()
                    .filter(add -> add.graph().getIRIString().equals(ArkprovVocabulary.PROVENANCE_GRAPH))
                    .toList();
            assertEquals(1, provenance.size(), "expected exactly one provenance-graph write");
            return provenance.get(0).triples();
        }

        /** All statements added to the provenance graph across all {@code add} calls. */
        private List<Triple> provenanceStatements() {
            return adds.stream()
                    .filter(add -> add.graph().getIRIString().equals(ArkprovVocabulary.PROVENANCE_GRAPH))
                    .flatMap(add -> add.triples().stream())
                    .toList();
        }
    }

    /** A one-variable {@link BindingSet} for scripting the previous-head {@code SELECT}. */
    private static final class SingleBinding implements BindingSet {

        private final String name;
        private final RDFTerm value;

        private SingleBinding(String name, RDFTerm value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public Set<String> getBindingNames() {
            return Set.of(name);
        }

        @Override
        public java.util.Optional<RDFTerm> getValue(String bindingName) {
            return name.equals(bindingName) ? java.util.Optional.of(value) : java.util.Optional.empty();
        }

        @Override
        public boolean hasBinding(String bindingName) {
            return name.equals(bindingName);
        }

        @Override
        public int size() {
            return 1;
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
