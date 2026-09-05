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
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetExport;
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
import io.kogn.rdf.terms.vocab.VocabDct;
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
 * signals for each rejection, and the create-only commit-conflict translation.
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
     * Two genuinely overlapping {@code SERIALIZABLE} transactions both pass the
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
     * A caller guarding a second uniqueness rule of its own inside the body (the
     * project registry's anchor uniqueness) may name the signal a lost commit surfaces as, instead
     * of having {@code duplicateCode} imposed on it - and it sees the store's own exception, so it
     * can decide by more than the mere fact of having lost.
     */
    @Test
    void createLetsTheCallerTranslateALostCommitItself() {
        RuntimeException storeConflict = new RuntimeException("store commit conflict");
        Fixture fixture = new Fixture(List.of(false, false), storeConflict, e -> e == storeConflict);
        RuntimeException ownSignal = new RuntimeException("the anchor was taken");
        List<RuntimeException> translated = new ArrayList<>();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, Signals::unexpected,
                        conflict -> {
                            translated.add(conflict);
                            return ownSignal;
                        },
                        tx -> { }));

        assertSame(ownSignal, thrown, "the caller's own signal must reach the caller");
        assertEquals(List.of(storeConflict), translated, "the translator must see the store's exception");
        assertTrue(fixture.handle.closed, "handle must be released even on a conflict");
    }

    /**
     * A translator can fail itself - {@code KognioRdfProjectRegistry#attributeLostRegistration}
     * re-reads the store after the rollback, and that read can throw. The translator's own
     * exception must be what reaches the caller (it is the more actionable diagnosis: it says what
     * went wrong attributing the loss, where the store's own conflict says only "somebody else
     * committed first"), and the original store conflict must not simply vanish - it is attached
     * as {@link Throwable#getSuppressed()}, so a caller who wants to know a race happened still
     * can.
     */
    @Test
    void createLetsTheAttributionFailureThroughWithTheLostCommitSuppressed() {
        RuntimeException storeConflict = new RuntimeException("store commit conflict");
        Fixture fixture = new Fixture(List.of(false, false), storeConflict, e -> e == storeConflict);
        RuntimeException attributionFailure = new IllegalStateException("unrecognised anchor type IRI");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, Signals::unexpected,
                        conflict -> {
                            throw attributionFailure;
                        },
                        tx -> { }));

        assertSame(attributionFailure, thrown, "the translator's own failure must reach the caller");
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(storeConflict, thrown.getSuppressed()[0],
                "the original store conflict must survive as suppressed, not vanish");
    }

    /**
     * A translator that simply rethrows the conflict it was handed - instead of returning it, the
     * documented "leave it untranslated" contract - must not fail on
     * {@code Throwable#addSuppressed(this)} (which throws {@link IllegalArgumentException} for a
     * self-reference); the conflict must still reach the caller cleanly.
     */
    @Test
    void createDoesNotFailWhenTheTranslatorRethrowsTheSameConflictInstance() {
        RuntimeException storeConflict = new RuntimeException("store commit conflict");
        Fixture fixture = new Fixture(List.of(false, false), storeConflict, e -> e == storeConflict);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, Signals::unexpected,
                        conflict -> {
                            throw conflict;
                        },
                        tx -> { }));

        assertSame(storeConflict, thrown);
        assertEquals(0, thrown.getSuppressed().length,
                "a translator rethrowing its own input must not self-suppress");
    }

    /**
     * The translator's escape hatch: returning the store's exception unchanged leaves the loss
     * untranslated - what {@code KognioRdfProjectRegistry} does when none of its uniqueness rules
     * explains the loss, rather than claiming a collision that did not happen. A {@code null}
     * result is treated the same way instead of replacing the conflict with a {@code
     * NullPointerException} that would hide it.
     */
    @Test
    void createLeavesALostCommitUntranslatedWhenTheCallerReturnsItOrNothing() {
        RuntimeException storeConflict = new RuntimeException("store commit conflict");

        Fixture passingThrough = new Fixture(List.of(false, false), storeConflict, e -> e == storeConflict);
        assertSame(storeConflict, assertThrows(RuntimeException.class,
                () -> passingThrough.funnel().create(passingThrough.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, Signals::unexpected,
                        UnaryOperator.identity(), tx -> { })));

        Fixture returningNull = new Fixture(List.of(false, false), storeConflict, e -> e == storeConflict);
        assertSame(storeConflict, assertThrows(RuntimeException.class,
                () -> returningNull.funnel().create(returningNull.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, Signals::unexpected,
                        conflict -> null, tx -> { })));
    }

    /**
     * The synchronous code check and a lost commit are two different observations, and only the
     * latter is the translator's business: a caller that translates lost commits into its own
     * signal must still see {@code duplicateCode} when the code was found taken before any write.
     */
    @Test
    void createStillRejectsATakenCodeWithDuplicateCodeWhenATranslatorIsGiven() {
        Fixture fixture = new Fixture(List.of(false, true));
        RuntimeException duplicateCode = new RuntimeException("duplicate code");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                        candidate(), null, Signals::unexpected, () -> duplicateCode,
                        conflict -> new RuntimeException("must not be reached"), Signals.noBody()));

        assertSame(duplicateCode, thrown);
        assertFalse(fixture.bodyRan);
    }

    /**
     * The predicate the four out-adapters actually inject carries an invariant: point it at
     * the wrong type (a superclass, a {@code RuntimeException} wrapper) and a lost race would stop
     * being translated - visible only in a real race, which is exactly what the
     * {@code *RealStoreConcurrencyTest}s cover flakily. Pinned here directly instead.
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
     * The pre-funnel adapters translated a commit conflict only on create (the create
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

    /** The validation-only context must reach the gate; {@code null} means none. */
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

    // ---- compareAndUpdate --------------------------------------------------------------------

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
     * The "second interleaving" from create, now also relevant to an update path: two callers can
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

    // ---- delete (issue #335) --------------------------------------------------------------

    @Test
    void deleteRunsBodyWhenSubjectExists() {
        Fixture fixture = new Fixture(List.of(true));

        List<DatasetTx> bodyCalls = new ArrayList<>();
        fixture.funnel().delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, Signals::unexpected, bodyCalls::add);

        assertEquals(1, fixture.tx.containsCalls.size());
        assertSubjectExistenceCheck(fixture.tx.containsCalls.get(0), GRAPH_IRI, SUBJECT_IRI);
        assertEquals(1, bodyCalls.size());
        assertSame(fixture.tx, bodyCalls.get(0), "body must run on the live transaction");
        assertTrue(fixture.handle.closed, "handle must be released");
    }

    @Test
    void deleteRejectsMissingSubjectWithNotFoundSignal() {
        Fixture fixture = new Fixture(List.of(false));
        RuntimeException signal = new RuntimeException("not found");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, () -> signal,
                        Signals.noBody()));

        assertSame(signal, thrown);
        assertFalse(fixture.bodyRan);
        assertTrue(fixture.handle.closed);
    }

    /**
     * The tombstone contract (issue #335): a subject that already had a head has that revision
     * marked {@code prov:invalidatedAtTime} and the {@code arkprov:head} triple removed - no new
     * revision is minted, unlike {@link #create}/{@link #update}/{@link #compareAndUpdate}.
     */
    @Test
    void deleteInvalidatesThePreviousRevisionAndRemovesTheHead() {
        Fixture fixture = new Fixture(List.of(true));
        String previous = "https://w3id.org/arknet/revision/previous";
        fixture.tx.headAnswers.add(previous);

        fixture.funnel().delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, Signals::unexpected, tx -> { });

        assertEquals(1, fixture.tx.updates.size(), "the head triple must be deleted");
        String deleteHead = fixture.tx.updates.get(0);
        assertTrue(deleteHead.contains("<" + ArkprovVocabulary.PROVENANCE_GRAPH + ">")
                && deleteHead.contains("<" + SUBJECT_IRI + ">")
                && deleteHead.contains("<" + ArkprovVocabulary.HEAD + ">"),
                "head delete must target this subject's head in the provenance graph, got: " + deleteHead);

        List<Triple> provenance = fixture.tx.provenanceStatements();
        assertFalse(provenance.isEmpty(), "the previous revision must be tombstoned");
        assertTrue(provenance.stream().allMatch(triple -> triple.getSubject() instanceof IRI iri
                        && iri.getIRIString().equals(previous)),
                "only the previous revision may be touched, no new revision minted");
        List<Literal> instants = provenance.stream()
                .filter(triple -> triple.getPredicate().getIRIString()
                        .equals(ArkprovVocabulary.INVALIDATED_AT_TIME))
                .map(triple -> (Literal) triple.getObject())
                .toList();
        assertEquals(1, instants.size(), "exactly one invalidation instant");
        assertEquals(VocabXsd.DATETIME.getIRIString(), instants.get(0).getDatatype().getIRIString(),
                "the invalidation instant must be typed xsd:dateTime");
    }

    /** A subject that predates the funnel's revision recording has no head to tombstone. */
    @Test
    void deleteWithNoPriorHeadLeavesProvenanceUntouched() {
        Fixture fixture = new Fixture(List.of(true));

        fixture.funnel().delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, Signals::unexpected, tx -> { });

        assertTrue(fixture.tx.updates.isEmpty(), "nothing to tombstone without a prior head");
        assertTrue(fixture.tx.provenanceStatements().isEmpty());
    }

    /**
     * A caller's own pre-delete check (e.g. a "still referenced" guard) runs as the first thing
     * {@code body} does; a failing body must abort before the tombstone runs, exactly like a
     * failing body aborts before {@link #recordRevision} for the other three methods.
     */
    @Test
    void deleteFailingBodyLeavesTheRevisionUntouched() {
        Fixture fixture = new Fixture(List.of(true));
        fixture.tx.headAnswers.add("https://w3id.org/arknet/revision/previous");
        RuntimeException stillReferenced = new RuntimeException("still referenced");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.funnel().delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, Signals::unexpected,
                        tx -> {
                            throw stillReferenced;
                        }));

        assertSame(stillReferenced, thrown);
        assertTrue(fixture.tx.updates.isEmpty(), "a failed delete must not tombstone the revision");
        assertTrue(fixture.tx.provenanceStatements().isEmpty());
    }

    @Test
    void deleteRejectsNullArguments() {
        Fixture fixture = new Fixture(List.of());
        WriteFunnel funnel = fixture.funnel();

        assertThrows(NullPointerException.class,
                () -> funnel.delete(null, GRAPH_IRI, SUBJECT_IRI, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class,
                () -> funnel.delete(fixture.dataset, null, SUBJECT_IRI, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class,
                () -> funnel.delete(fixture.dataset, GRAPH_IRI, null, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class,
                () -> funnel.delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, null, Signals.noBody()));
        assertThrows(NullPointerException.class,
                () -> funnel.delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, Signals::unexpected, null));
    }

    // ---- delete with code retention (issue #350) ------------------------------------------

    /**
     * A caller minting {@code PREFIX-N}-style codes hangs the deleted resource's code on exactly
     * the revision the delete tombstones - the one place {@link #findRetainedCodes} can read it
     * back from once the model triple naming it is gone.
     */
    @Test
    void deleteWithCodeRetainsItOnTheTombstonedRevision() {
        Fixture fixture = new Fixture(List.of(true));
        String previous = "https://w3id.org/arknet/revision/previous";
        fixture.tx.headAnswers.add(previous);

        fixture.funnel().delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, "TERM-7", Signals::unexpected, tx -> { });

        List<Triple> provenance = fixture.tx.provenanceStatements();
        List<Triple> identifiers = provenance.stream()
                .filter(triple -> triple.getPredicate().getIRIString().equals(VocabDct.IDENTIFIER.getIRIString()))
                .toList();
        assertEquals(1, identifiers.size(), "exactly one retained code");
        assertEquals(previous, ((IRI) identifiers.get(0).getSubject()).getIRIString(),
                "the code must be hung on the tombstoned revision, not the opaque subject");
        assertEquals("TERM-7", ((Literal) identifiers.get(0).getObject()).getLexicalForm());
    }

    /**
     * A subject that predates the funnel's revision recording has no head to hang a code on -
     * the gap is logged, not fabricated into a revision that never happened (see
     * {@link WriteFunnel} class javadoc).
     */
    @Test
    void deleteWithCodeButNoPriorHeadAddsNothing() {
        Fixture fixture = new Fixture(List.of(true));

        fixture.funnel().delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, "TERM-7", Signals::unexpected, tx -> { });

        assertTrue(fixture.tx.provenanceStatements().isEmpty(), "nothing to hang the code on without a prior head");
    }

    /** The plain overload (no business code) must never write a {@code dcterms:identifier}. */
    @Test
    void deleteWithoutCodeAddsNoIdentifierTriple() {
        Fixture fixture = new Fixture(List.of(true));
        fixture.tx.headAnswers.add("https://w3id.org/arknet/revision/previous");

        fixture.funnel().delete(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, Signals::unexpected, tx -> { });

        boolean anyIdentifier = fixture.tx.provenanceStatements().stream()
                .anyMatch(triple -> triple.getPredicate().getIRIString().equals(VocabDct.IDENTIFIER.getIRIString()));
        assertFalse(anyIdentifier, "no code was given, so nothing should be retained");
    }

    // ---- findRetainedCodes (issue #350) ---------------------------------------------------

    @Test
    void findRetainedCodesReturnsTheScriptedIdentifiers() {
        Fixture fixture = new Fixture(List.of());
        fixture.handle.retainedIdentifierAnswers.add("TERM-3");
        fixture.handle.retainedIdentifierAnswers.add("TERM-1");

        List<String> retained = fixture.funnel().findRetainedCodes(fixture.dataset, "TERM-");

        assertEquals(List.of("TERM-3", "TERM-1"), retained);
        assertTrue(fixture.handle.closed, "handle must be released");
        assertEquals(1, fixture.handle.sparqlSelectQueries.size());
        String query = fixture.handle.sparqlSelectQueries.get(0);
        assertTrue(query.contains(ArkprovVocabulary.PROVENANCE_GRAPH));
        assertTrue(query.contains(ArkprovVocabulary.INVALIDATED_AT_TIME));
        assertTrue(query.contains("TERM-"), "the prefix must be filtered on, got: " + query);
    }

    @Test
    void findRetainedCodesRejectsNullArguments() {
        Fixture fixture = new Fixture(List.of());
        WriteFunnel funnel = fixture.funnel();

        assertThrows(NullPointerException.class, () -> funnel.findRetainedCodes(null, "TERM-"));
        assertThrows(NullPointerException.class, () -> funnel.findRetainedCodes(fixture.dataset, null));
    }

    // ---- revision recording (revision basis) -----------------------------------

    /**
     * Every write through the funnel records exactly one immutable
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
     * "revisions between T1 and T2" read path the provenance model still owes (ADR-28 in arknet's
     * own store makes the revision the change record, so change tracking has to come from it).
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

    // ---- null-check contract (constructors, create/update/compareAndUpdate) --------------

    /**
     * Both constructors validate every collaborator with {@code Objects.requireNonNull} -
     * mirrors {@code ShaclWriteGateTest#rejectsNullArguments} for the analogous contract on
     * {@link ShaclWriteGate}. Left untested, a dropped guard would surface only as an NPE deep
     * inside {@code rdf.createIRI(null)} or similar instead of a clear message at the
     * boundary.
     */
    @Test
    void constructorsRejectNullArguments() {
        Fixture fixture = new Fixture(List.of());
        DatasetLifecycle lifecycle = fixture.lifecycle;
        ShaclWriteGate gate = validGate();
        Predicate<RuntimeException> isWriteConflict = e -> false;
        Clock clock = Clock.systemUTC();

        assertThrows(NullPointerException.class, () -> new WriteFunnel(null, gate, isWriteConflict));
        assertThrows(NullPointerException.class, () -> new WriteFunnel(lifecycle, null, isWriteConflict));
        assertThrows(NullPointerException.class, () -> new WriteFunnel(lifecycle, gate, null));

        assertThrows(NullPointerException.class, () -> new WriteFunnel(null, gate, isWriteConflict, clock));
        assertThrows(NullPointerException.class, () -> new WriteFunnel(lifecycle, null, isWriteConflict, clock));
        assertThrows(NullPointerException.class, () -> new WriteFunnel(lifecycle, gate, null, clock));
        assertThrows(NullPointerException.class, () -> new WriteFunnel(lifecycle, gate, isWriteConflict, null));
    }

    /**
     * The short {@code create} overload (bound {@code commitConflict}) checks the same
     * parameters as the full overload below, plus its own early {@code duplicateCode} check
     * before delegating. {@code assertedContext} is deliberately excluded: it is documented as
     * optional ({@code null} if the shapes need none).
     */
    @Test
    void createShortOverloadRejectsNullArguments() {
        Fixture fixture = new Fixture(List.of());
        WriteFunnel funnel = fixture.funnel();

        assertThrows(NullPointerException.class, () -> funnel.create(null, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, null, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, null, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, null,
                candidate(), null, Signals::unexpected, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                null, null, Signals::unexpected, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, null, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, null, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, null));
    }

    /** The full {@code create} overload additionally checks {@code commitConflict}. */
    @Test
    void createFullOverloadRejectsNullArguments() {
        Fixture fixture = new Fixture(List.of());
        WriteFunnel funnel = fixture.funnel();
        UnaryOperator<RuntimeException> commitConflict = UnaryOperator.identity();

        assertThrows(NullPointerException.class, () -> funnel.create(null, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, commitConflict, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, null, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, commitConflict, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, null, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, commitConflict, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, null,
                candidate(), null, Signals::unexpected, Signals::unexpected, commitConflict, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                null, null, Signals::unexpected, Signals::unexpected, commitConflict, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, null, Signals::unexpected, commitConflict, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, null, commitConflict, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, null, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.create(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, CODE,
                candidate(), null, Signals::unexpected, Signals::unexpected, commitConflict, null));
    }

    /** {@code assertedContext} is deliberately excluded here too - optional, {@code null} means none. */
    @Test
    void updateRejectsNullArguments() {
        Fixture fixture = new Fixture(List.of());
        WriteFunnel funnel = fixture.funnel();

        assertThrows(NullPointerException.class, () -> funnel.update(null, GRAPH_IRI, SUBJECT_IRI,
                candidate(), null, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.update(fixture.dataset, null, SUBJECT_IRI,
                candidate(), null, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.update(fixture.dataset, GRAPH_IRI, null,
                candidate(), null, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.update(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                null, null, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.update(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                candidate(), null, null, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.update(fixture.dataset, GRAPH_IRI, SUBJECT_IRI,
                candidate(), null, Signals::unexpected, null));
    }

    /**
     * {@code assertedContext} and {@code expectedHead} are deliberately excluded: both are
     * documented as optionally {@code null} ({@code expectedHead} meaning "no revision recorded
     * yet").
     */
    @Test
    void compareAndUpdateRejectsNullArguments() {
        Fixture fixture = new Fixture(List.of());
        WriteFunnel funnel = fixture.funnel();
        String expectedHead = "https://w3id.org/arknet/revision/current";

        assertThrows(NullPointerException.class, () -> funnel.compareAndUpdate(null, GRAPH_IRI, SUBJECT_IRI,
                expectedHead, candidate(), null, Signals::unexpected, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.compareAndUpdate(fixture.dataset, null, SUBJECT_IRI,
                expectedHead, candidate(), null, Signals::unexpected, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class, () -> funnel.compareAndUpdate(fixture.dataset, GRAPH_IRI, null,
                expectedHead, candidate(), null, Signals::unexpected, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class,
                () -> funnel.compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, expectedHead, null, null,
                        Signals::unexpected, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class,
                () -> funnel.compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, expectedHead, candidate(),
                        null, null, Signals::unexpected, Signals.noBody()));
        assertThrows(NullPointerException.class,
                () -> funnel.compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, expectedHead, candidate(),
                        null, Signals::unexpected, null, Signals.noBody()));
        assertThrows(NullPointerException.class,
                () -> funnel.compareAndUpdate(fixture.dataset, GRAPH_IRI, SUBJECT_IRI, expectedHead, candidate(),
                        null, Signals::unexpected, Signals::unexpected, null));
    }

    /** A conforming, minimal gate wired the same way {@link Fixture#funnelAt} builds one. */
    private ShaclWriteGate validGate() {
        RDF graphs = new SimpleRdf();
        return new ShaclWriteGate(new RecordingValidation(new ShaclReport(true, List.of())), graphs.createGraph(),
                graphs.createGraph(), ValidationOptions.defaults(), DisplayLocale.DEFAULT);
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

    /** Asserts a {@code contains} call checking whether {@code subject} exists at all. */
    private static void assertSubjectExistenceCheck(FakeTx.ContainsCall call, String graph, String subject) {
        assertEquals(graph, call.namedGraph().getIRIString());
        assertEquals(subject, ((IRI) call.subject()).getIRIString());
        assertNull(call.predicate(), "a subject existence check leaves the predicate wildcarded");
        assertNull(call.object(), "a subject existence check leaves the object wildcarded");
    }

    /**
     * Asserts a {@code contains} call checking whether any subject already carries {@code code}
     * on {@code dcterms:identifier}.
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

        private final DatasetId dataset = new DatasetId("project");
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

        /** Scripted rows {@link #sparqlQuery} answers with - {@link #findRetainedCodes}'s only read. */
        private final List<String> retainedIdentifierAnswers = new ArrayList<>();
        private final List<String> sparqlSelectQueries = new ArrayList<>();

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
            return new SparqlQuery() {
                @Override
                public Stream<BindingSet> select(String sparql) {
                    sparqlSelectQueries.add(sparql);
                    RDF terms = new SimpleRdf();
                    return retainedIdentifierAnswers.stream()
                            .map(value -> (BindingSet) new SingleBinding("identifier", terms.createLiteral(value)));
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

                @Override
                public boolean ask(String sparql) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean ask(String sparql, java.util.Map<String, RDFTerm> bindings) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public SparqlUpdate sparqlUpdate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DatasetExport datasetExport() {
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
