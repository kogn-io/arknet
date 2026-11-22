// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.cid.sexpr.ContentAddressedIRIGeneratorSexpr;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.shacl.ShaclMessage;
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
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.versioning.ContentGraphMismatchException;
import io.kogn.rdf.versioning.EntryDetails;
import io.kogn.rdf.versioning.EntryKind;
import io.kogn.rdf.versioning.HistoryEntry;
import io.kogn.rdf.versioning.PublishResult;
import io.kogn.rdf.versioning.PublishState;
import io.kogn.rdf.versioning.ResourceVersioning;
import io.kogn.rdf.versioning.ResourceVersioningNamedGraphs;
import io.kogn.rdf.versioning.RevisionToken;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.testsupport.GuardedLifecycle;
import de.hauschel.arknet.persistence.testsupport.PausingOnFirstWriteTx;

/**
 * {@link VersionedWriteFunnel} against a real on-disk {@code NativeStore}: draft, publish, history,
 * token conflicts, tombstones and the content-graph binding, with fragment children in the slice
 * (ADR-61..64).
 */
class VersionedWriteFunnelRealStoreTest {

    private static final DatasetId DATASET = new DatasetId("test-project");
    private static final String NS = "https://example.org/";
    private static final String CONTENT_GRAPH = NS + "graph/content";
    private static final String SUBJECT = NS + "req/1";
    private static final String OTHER = NS + "req/2";
    private static final String CODE = "FR-1";
    private static final Instant START = Instant.parse("2026-11-17T10:00:00Z");

    private final RDF rdf = new SimpleRdf();
    private final IRI title = rdf.createIRI(NS + "title");
    private final IRI criterion = rdf.createIRI(NS + "criterion");
    private final IRI text = rdf.createIRI(NS + "text");
    private final IRI alice = rdf.createIRI(NS + "agent/alice");
    private final IRI bob = rdf.createIRI(NS + "agent/bob");
    private final MutableClock clock = new MutableClock(START);
    private final AtomicReference<ReadableGraph> validated = new AtomicReference<>();

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private VersionedWriteFunnel funnel;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        funnel = new VersionedWriteFunnel(lifecycle, recordingGate(false), CONTENT_GRAPH,
                VersionedWriteFunnel.DEFAULT_WRITE_CONFLICT, clock);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void draftPublishUpdatePublish_historyCarriesBothVersionsWithTheirFragments() {
        // given - a draft with two fragment children
        create(SUBJECT, CODE, "v1", "crit one", "crit two");
        assertEquals(PublishState.NEVER_PUBLISHED, funnel.publishState(DATASET, SUBJECT));
        assertTrue(funnel.history(DATASET, SUBJECT).isEmpty());

        // when - published by alice, then republished unchanged by bob
        PublishResult first = funnel.publish(DATASET, List.of(SUBJECT), EntryDetails.NONE.withCreator(alice),
                NotFound::of, Signals::unexpected);
        clock.advance(Duration.ofMinutes(5));
        PublishResult unchanged = funnel.publish(DATASET, List.of(SUBJECT), EntryDetails.NONE.withCreator(bob),
                NotFound::of, Signals::unexpected);

        // then - one entry only, the repeated publish names it
        assertEquals(PublishState.PUBLISHED_UNCHANGED, funnel.publishState(DATASET, SUBJECT));
        assertEquals(first.results(), unchanged.results());
        assertEquals(1, funnel.history(DATASET, SUBJECT).size());

        // when - one criterion changes under its stable fragment name, bob publishes
        update(SUBJECT, "v1", "crit one CHANGED", "crit two");
        assertEquals(PublishState.PUBLISHED_WITH_UNPUBLISHED_CHANGES, funnel.publishState(DATASET, SUBJECT));
        funnel.publish(DATASET, List.of(SUBJECT), EntryDetails.NONE.withCreator(bob).withComment("changed ac-1"),
                NotFound::of, Signals::unexpected);

        // then
        List<HistoryEntry> history = funnel.history(DATASET, SUBJECT);
        assertEquals(2, history.size());
        HistoryEntry h1 = history.get(0);
        HistoryEntry h2 = history.get(1);
        assertEquals(EntryKind.PUBLISH, h1.kind());
        assertEquals(Optional.of(alice), h1.creator());
        assertEquals(Optional.of(bob), h2.creator());
        assertEquals(Optional.of("changed ac-1"), h2.comment());
        assertEquals(START, h1.created());
        assertEquals(START.plus(Duration.ofMinutes(5)), h2.created());
        assertTrue(h2.cid().isPresent());

        ResourceVersioning library = sameContentGraph();
        ReadableGraph v1 = tx(t -> library.readVersion(t, rdf.createIRI(SUBJECT), 1)).orElseThrow();
        assertEquals(6, v1.size(), "identifier, title, two criterion edges, two criterion texts");
        assertEquals(Set.of("crit one", "crit two"), texts(v1));
        assertEquals(Set.of("crit one CHANGED", "crit two"),
                texts(tx(t -> library.readVersionByCID(t, h2.cid().orElseThrow())).orElseThrow()));
        assertEquals(6, tx(t -> library.readPublished(t, rdf.createIRI(SUBJECT))).size());
    }

    /**
     * Every fragment a written slice holds is recorded as issued, and the record outlives the
     * fragment: a later slice without it does not take the number back (ADR-64, stable names).
     */
    @Test
    void everyWrittenFragmentIsRecordedAsIssuedAndStaysIssuedAfterItsRemoval() {
        create(SUBJECT, CODE, "v1", "crit one", "crit two");
        update(SUBJECT, "v2", "crit one");

        Set<String> issued = tx(t -> t.match(rdf.createIRI(ChildFragments.ISSUED_FRAGMENTS_GRAPH),
                rdf.createIRI(SUBJECT), null, null).stream()
                .map(triple -> ((IRI) triple.getObject()).getIRIString())
                .collect(Collectors.toSet()));

        assertEquals(Set.of(SUBJECT + "#AC-1", SUBJECT + "#AC-2"), issued);
    }

    @Test
    void sliceBuilderSeesTheCurrentDraftAndTheGateValidatesTheBuiltSlice() {
        create(SUBJECT, CODE, "v1", "crit one");
        RevisionToken token = currentToken(SUBJECT);

        funnel.compareAndUpdate(DATASET, SUBJECT, token, null, Signals::unexpected, Signals::unexpected,
                (tx, current) -> {
                    Graph next = rdf.createGraph();
                    current.stream().forEach(next::add);
                    next.add(rdf.createIRI(SUBJECT), title, rdf.createLiteral("v1", "de"));
                    return next;
                });

        assertEquals(5, validated.get().size(), "the gate saw the merged slice, not a partial candidate");
        assertEquals(5, draft(SUBJECT).size());
    }

    @Test
    void aRejectingGateLeavesTheDraftUntouched() {
        create(SUBJECT, CODE, "v1", "crit one");
        RevisionToken token = currentToken(SUBJECT);
        VersionedWriteFunnel rejecting = new VersionedWriteFunnel(lifecycle, recordingGate(true), CONTENT_GRAPH,
                VersionedWriteFunnel.DEFAULT_WRITE_CONFLICT, clock);

        assertThrows(WriteConstraintViolationException.class,
                () -> rejecting.compareAndUpdate(DATASET, SUBJECT, token, null, Signals::unexpected,
                        Signals::unexpected, (tx, current) -> requirement(SUBJECT, CODE, "v2")));

        assertEquals(Set.of("crit one"), texts(draft(SUBJECT)));
        assertEquals(token, currentToken(SUBJECT), "a rejected write does not raise the counter");
    }

    @Test
    void createGuardsSubjectAndCode_andRejectsATripleOutsideTheSlice() {
        create(SUBJECT, CODE, "v1", "crit one");

        assertThrows(AlreadyExists.class, () -> funnel.create(DATASET, SUBJECT, "FR-9", null,
                AlreadyExists::new, Signals::unexpected, (tx, current) -> requirement(SUBJECT, "FR-9", "x")));
        assertThrows(DuplicateCode.class, () -> funnel.create(DATASET, OTHER, CODE, null,
                Signals::unexpected, DuplicateCode::new, (tx, current) -> requirement(OTHER, CODE, "x")));
        assertThrows(IllegalArgumentException.class, () -> funnel.create(DATASET, OTHER, "FR-2", null,
                Signals::unexpected, Signals::unexpected, (tx, current) -> requirement(SUBJECT, "FR-2", "x")));

        assertTrue(draft(OTHER).isEmpty());
    }

    @Test
    void createIfAbsentReturnsTheExistingResourceAndWritesNothing() {
        create(SUBJECT, CODE, "v1", "crit one");
        RevisionToken before = currentToken(SUBJECT);

        String result = funnel.createIfAbsent(DATASET, OTHER, null, null,
                tx -> Optional.of(SUBJECT), Signals::unexpected, Signals::unexpected,
                (tx, current) -> requirement(OTHER, "FR-2", "x"));

        assertEquals(SUBJECT, result);
        assertTrue(draft(OTHER).isEmpty());
        assertEquals(before, currentToken(SUBJECT));
    }

    @Test
    void aStaleTokenFailsTheUpdateAndWritesNothing() {
        create(SUBJECT, CODE, "v1", "crit one");
        RevisionToken stale = currentToken(SUBJECT);
        update(SUBJECT, "v2", "crit one");

        assertThrows(RevisionMismatch.class, () -> funnel.compareAndUpdate(DATASET, SUBJECT, stale, null,
                Signals::unexpected, RevisionMismatch::new, (tx, current) -> requirement(SUBJECT, CODE, "v3")));
        assertThrows(RevisionMismatch.class, () -> funnel.compareAndUpdate(DATASET, SUBJECT, null, null,
                Signals::unexpected, RevisionMismatch::new, (tx, current) -> requirement(SUBJECT, CODE, "v3")));

        assertEquals(Set.of("v2"), titles(draft(SUBJECT)));
    }

    @Test
    void anUpdateOfAnUnknownSubjectIsNotFound() {
        RevisionToken token = currentToken(OTHER);
        assertThrows(NotFound.class, () -> funnel.compareAndUpdate(DATASET, OTHER, token, null,
                NotFound::new, Signals::unexpected, (tx, current) -> requirement(OTHER, "FR-2", "x")));
    }

    /**
     * Both writers read the same token inside their own transaction and meet at a barrier before
     * either replaces the draft; under {@code SERIALIZABLE} only one commit lands, and the loser
     * surfaces as {@code revisionMismatch} instead of overwriting the winner.
     */
    @Test
    void twoOverlappingUpdatesFromTheSameToken_oneCommitsTheOtherIsAMismatch() throws InterruptedException {
        create(SUBJECT, CODE, "v1", "crit one");
        RevisionToken token = currentToken(SUBJECT);
        CyclicBarrier bothInside = new CyclicBarrier(2);
        AtomicReference<Optional<RuntimeException>> outcomeA = new AtomicReference<>();
        AtomicReference<Optional<RuntimeException>> outcomeB = new AtomicReference<>();

        Thread racerA = new Thread(() -> outcomeA.set(race(token, "racer-A", bothInside)), "racer-A");
        Thread racerB = new Thread(() -> outcomeB.set(race(token, "racer-B", bothInside)), "racer-B");
        racerA.start();
        racerB.start();
        racerA.join();
        racerB.join();

        assertNotNull(outcomeA.get());
        assertNotNull(outcomeB.get());
        boolean aWon = outcomeA.get().isEmpty();
        assertNotEquals(aWon, outcomeB.get().isEmpty(), "exactly one racer must succeed");
        RuntimeException loser = aWon ? outcomeB.get().orElseThrow() : outcomeA.get().orElseThrow();
        assertSame(RevisionMismatch.class, loser.getClass());
        assertEquals(Set.of(aWon ? "racer-A" : "racer-B"), titles(draft(SUBJECT)));
    }

    @Test
    void deleteRecordsATombstoneNamingTheLastVersionAndRetainsTheCode() {
        create(SUBJECT, CODE, "v1", "crit one");
        funnel.publish(DATASET, List.of(SUBJECT), EntryDetails.NONE, NotFound::of, Signals::unexpected);
        HistoryEntry published = funnel.history(DATASET, SUBJECT).get(0);

        String tombstoneIri = funnel.delete(DATASET, SUBJECT, CODE, EntryDetails.NONE.withCreator(alice),
                Signals::unexpected, Signals::unexpected, tx -> { });

        assertEquals(PublishState.DELETED, funnel.publishState(DATASET, SUBJECT));
        List<HistoryEntry> history = funnel.history(DATASET, SUBJECT);
        assertEquals(2, history.size());
        HistoryEntry tombstone = history.get(1);
        assertEquals(EntryKind.TOMBSTONE, tombstone.kind());
        assertEquals(tombstoneIri, tombstone.iri().getIRIString());
        assertEquals(published.cid(), tombstone.cid());
        assertTrue(draft(SUBJECT).isEmpty());
        assertTrue(tx(t -> sameContentGraph().readPublished(t, rdf.createIRI(SUBJECT))).isEmpty());
        assertEquals(List.of(CODE), funnel.findRetainedCodes(DATASET, "FR-"));
        assertEquals(List.of(), funnel.findRetainedCodes(DATASET, "NFR-"));
        assertThrows(NotFound.class, () -> funnel.delete(DATASET, SUBJECT, CODE, EntryDetails.NONE,
                NotFound::new, Signals::unexpected, tx -> { }));
    }

    /**
     * A never-published resource leaves a tombstone without a version: its last content is gone
     * from the library, only the retained code survives.
     */
    @Test
    void deletingANeverPublishedDraftLeavesATombstoneWithoutContentButKeepsTheCode() {
        create(SUBJECT, CODE, "v1", "crit one");

        funnel.delete(DATASET, SUBJECT, CODE, EntryDetails.NONE, Signals::unexpected, Signals::unexpected,
                tx -> { });

        HistoryEntry tombstone = funnel.history(DATASET, SUBJECT).get(0);
        assertEquals(EntryKind.TOMBSTONE, tombstone.kind());
        assertTrue(tombstone.cid().isEmpty());
        assertEquals(List.of(CODE), funnel.findRetainedCodes(DATASET, "FR-"));
    }

    @Test
    void aGuardThrowingInsideDeleteKeepsTheResource() {
        create(SUBJECT, CODE, "v1", "crit one");

        assertThrows(Referenced.class, () -> funnel.delete(DATASET, SUBJECT, CODE, EntryDetails.NONE,
                Signals::unexpected, Signals::unexpected, tx -> {
                    throw new Referenced();
                }));

        assertEquals(PublishState.NEVER_PUBLISHED, funnel.publishState(DATASET, SUBJECT));
        assertEquals(Set.of("v1"), titles(draft(SUBJECT)));
        assertTrue(funnel.findRetainedCodes(DATASET, "FR-").isEmpty());
    }

    @Test
    void publishOfSeveralResourcesCreatesOneEntryEach() {
        create(SUBJECT, CODE, "a", "x");
        create(OTHER, "FR-2", "b", "y");

        PublishResult result = funnel.publish(DATASET, List.of(SUBJECT, OTHER), EntryDetails.NONE, NotFound::of,
                Signals::unexpected);

        assertEquals(2, result.results().size());
        update(SUBJECT, "a2", "x");
        long dirty = Set.of(SUBJECT, OTHER).stream()
                .filter(iri -> funnel.publishState(DATASET, iri) != PublishState.PUBLISHED_UNCHANGED).count();
        assertEquals(1, dirty);
    }

    /**
     * The publishable subject comes first, the unknown one second: a publish running one
     * transaction per subject, or writing before it checked every subject, would leave the first
     * one published.
     */
    @Test
    void publishIsAllOrNone_anUnknownSubjectLeavesTheOthersUnpublished() {
        create(SUBJECT, CODE, "a", "x");

        NotFound thrown = assertThrows(NotFound.class, () -> funnel.publish(DATASET, List.of(SUBJECT, OTHER),
                EntryDetails.NONE, NotFound::of, Signals::unexpected));

        assertEquals(OTHER, thrown.getMessage());
        assertEquals(PublishState.NEVER_PUBLISHED, funnel.publishState(DATASET, SUBJECT));
        assertTrue(funnel.history(DATASET, SUBJECT).isEmpty());
    }

    @Test
    void publishOfADeletedSubjectIsNotFound() {
        create(SUBJECT, CODE, "a", "x");
        funnel.delete(DATASET, SUBJECT, CODE, EntryDetails.NONE, Signals::unexpected, Signals::unexpected,
                tx -> { });

        NotFound thrown = assertThrows(NotFound.class, () -> funnel.publish(DATASET, List.of(SUBJECT),
                EntryDetails.NONE, NotFound::of, Signals::unexpected));

        assertEquals(SUBJECT, thrown.getMessage());
        assertEquals(1, funnel.history(DATASET, SUBJECT).size(), "only the tombstone");
    }

    @Test
    void aDeletedSubjectCanBeCreatedAgain() {
        create(OTHER, "FR-2", "b", "y");
        funnel.delete(DATASET, OTHER, "FR-2", EntryDetails.NONE, Signals::unexpected, Signals::unexpected,
                tx -> { });

        create(OTHER, "FR-3", "c", "z");

        assertEquals(Set.of("c"), titles(draft(OTHER)));
    }

    /**
     * Two creates of different subjects with the same code, on a dataset whose content-graph
     * binding is already committed: both pass the synchronous code check before either commits,
     * so the store's commit-time conflict decides, and the loser surfaces as {@code duplicateCode}.
     */
    @Test
    void twoOverlappingCreatesWithTheSameCode_oneCommitsTheOtherIsADuplicateCode() throws InterruptedException {
        create(NS + "req/seed", "FR-0", "seed");
        CyclicBarrier bothInside = new CyclicBarrier(2);

        List<Optional<RuntimeException>> outcomes = runTwo(
                () -> funnel.create(DATASET, SUBJECT, CODE, null, Signals::unexpected, DuplicateCode::new,
                        (tx, current) -> awaitThen(bothInside, requirement(SUBJECT, CODE, "racer-A"))),
                () -> funnel.create(DATASET, OTHER, CODE, null, Signals::unexpected, DuplicateCode::new,
                        (tx, current) -> awaitThen(bothInside, requirement(OTHER, CODE, "racer-B"))));

        RuntimeException loser = exactlyOneLoser(outcomes);
        assertSame(DuplicateCode.class, loser.getClass());
        assertEquals(1, Set.of(SUBJECT, OTHER).stream().filter(s -> !draft(s).isEmpty()).count(),
                "exactly one subject holds a draft");
    }

    /**
     * Two idempotent creates of the same logical resource under different subject IRIs: both find
     * nothing, both build, one loses at commit and answers with the winner's IRI from the retry.
     */
    @Test
    void twoOverlappingCreateIfAbsent_bothReturnTheWinnersIri() throws InterruptedException {
        create(NS + "req/seed", "FR-0", "seed");
        CyclicBarrier bothInside = new CyclicBarrier(2);
        AtomicReference<String> resultA = new AtomicReference<>();
        AtomicReference<String> resultB = new AtomicReference<>();

        List<Optional<RuntimeException>> outcomes = runTwo(
                () -> resultA.set(funnel.createIfAbsent(DATASET, SUBJECT, CODE, null, this::findByCode,
                        Signals::unexpected, Signals::unexpected,
                        (tx, current) -> awaitThen(bothInside, requirement(SUBJECT, CODE, "racer-A")))),
                () -> resultB.set(funnel.createIfAbsent(DATASET, OTHER, CODE, null, this::findByCode,
                        Signals::unexpected, Signals::unexpected,
                        (tx, current) -> awaitThen(bothInside, requirement(OTHER, CODE, "racer-B")))));

        assertEquals(List.of(Optional.empty(), Optional.empty()), outcomes);
        assertEquals(resultA.get(), resultB.get());
        String loser = resultA.get().equals(SUBJECT) ? OTHER : SUBJECT;
        assertTrue(draft(loser).isEmpty(), "the loser's write never landed");
    }

    /**
     * The first two writes on a fresh dataset both find the content graph unbound and both record
     * the binding; disjoint subjects and codes must still both succeed.
     */
    @Test
    void twoFirstCreatesOnAFreshDatasetWithDifferentCodesBothSucceed() throws InterruptedException {
        CyclicBarrier bothInside = new CyclicBarrier(2);

        List<Optional<RuntimeException>> outcomes = runTwo(
                () -> funnel.create(DATASET, SUBJECT, CODE, null, Signals::unexpected, DuplicateCode::new,
                        (tx, current) -> awaitThen(bothInside, requirement(SUBJECT, CODE, "racer-A"))),
                () -> funnel.create(DATASET, OTHER, "FR-2", null, Signals::unexpected, DuplicateCode::new,
                        (tx, current) -> awaitThen(bothInside, requirement(OTHER, "FR-2", "racer-B"))));

        assertEquals(List.of(Optional.empty(), Optional.empty()), outcomes);
        assertEquals(Set.of("racer-A"), titles(draft(SUBJECT)));
        assertEquals(Set.of("racer-B"), titles(draft(OTHER)));
    }

    /**
     * Both first binders read the unbound dataset before either writes, so one loses the commit;
     * its one retry reads the winner's binding back and both creates succeed. Without the retry
     * the loser's create would end in the raw store conflict.
     */
    @Test
    void aBindingLostToAConcurrentBinderIsReadBackOnTheRetry() throws InterruptedException {
        CyclicBarrier bothBindersRead = new CyclicBarrier(2);
        VersionedWriteFunnel racing = new VersionedWriteFunnel(pausingFirstTransactionOfEachThread(bothBindersRead),
                recordingGate(false), CONTENT_GRAPH, VersionedWriteFunnel.DEFAULT_WRITE_CONFLICT, clock);

        List<Optional<RuntimeException>> outcomes = runTwo(
                () -> racing.create(DATASET, SUBJECT, CODE, null, Signals::unexpected, DuplicateCode::new,
                        (tx, current) -> requirement(SUBJECT, CODE, "racer-A")),
                () -> racing.create(DATASET, OTHER, "FR-2", null, Signals::unexpected, DuplicateCode::new,
                        (tx, current) -> requirement(OTHER, "FR-2", "racer-B")));

        assertEquals(List.of(Optional.empty(), Optional.empty()), outcomes);
        assertEquals(Set.of("racer-A"), titles(draft(SUBJECT)));
        assertEquals(Set.of("racer-B"), titles(draft(OTHER)));
    }

    /** Two deletes of the same subject both pass their checks; the loser gets the caller's signal. */
    @Test
    void aDeleteThatLosesTheCommitThrowsTheLostCommitSignal() throws InterruptedException {
        create(SUBJECT, CODE, "v1", "crit one");
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        VersionedWriteFunnel racing = new VersionedWriteFunnel(pausingFirstTransactionOfEachThread(bothChecked),
                recordingGate(false), CONTENT_GRAPH, VersionedWriteFunnel.DEFAULT_WRITE_CONFLICT, clock);

        List<Optional<RuntimeException>> outcomes = runTwo(
                () -> racing.delete(DATASET, SUBJECT, CODE, EntryDetails.NONE, Signals::unexpected,
                        RevisionMismatch::new, tx -> { }),
                () -> racing.delete(DATASET, SUBJECT, CODE, EntryDetails.NONE, Signals::unexpected,
                        RevisionMismatch::new, tx -> { }));

        assertTrue(exactlyOneLoser(outcomes) instanceof RevisionMismatch);
        assertEquals(PublishState.DELETED, funnel.publishState(DATASET, SUBJECT));
    }

    /** Two publishes of the same changed draft both read it; the loser gets the caller's signal. */
    @Test
    void aPublishThatLosesTheCommitThrowsTheLostCommitSignal() throws InterruptedException {
        create(SUBJECT, CODE, "v1", "crit one");
        CyclicBarrier bothRead = new CyclicBarrier(2);
        VersionedWriteFunnel racing = new VersionedWriteFunnel(pausingFirstTransactionOfEachThread(bothRead),
                recordingGate(false), CONTENT_GRAPH, VersionedWriteFunnel.DEFAULT_WRITE_CONFLICT, clock);

        List<Optional<RuntimeException>> outcomes = runTwo(
                () -> racing.publish(DATASET, List.of(SUBJECT), EntryDetails.NONE, NotFound::of,
                        RevisionMismatch::new),
                () -> racing.publish(DATASET, List.of(SUBJECT), EntryDetails.NONE, NotFound::of,
                        RevisionMismatch::new));

        assertTrue(exactlyOneLoser(outcomes) instanceof RevisionMismatch);
        assertEquals(1, funnel.history(DATASET, SUBJECT).size());
    }

    /**
     * Holds the first transaction each racing thread opens at its first write, until both threads
     * got there - so both have read the store and neither has written yet.
     */
    private DatasetLifecycle pausingFirstTransactionOfEachThread(CyclicBarrier barrier) {
        ThreadLocal<Boolean> firstOfThread = ThreadLocal.withInitial(() -> Boolean.TRUE);
        return new GuardedLifecycle(lifecycle, tx -> {
            if (!firstOfThread.get()) {
                return tx;
            }
            firstOfThread.set(Boolean.FALSE);
            return new PausingOnFirstWriteTx(tx, () -> await(barrier));
        });
    }

    private Optional<String> findByCode(DatasetTx tx) {
        return tx.select("SELECT ?s WHERE { GRAPH <" + CONTENT_GRAPH + "> { ?s <"
                + VocabDct.IDENTIFIER.getIRIString() + "> \"" + CODE + "\" } }")
                .map(row -> ((IRI) row.getValue("s").orElseThrow()).getIRIString())
                .findFirst();
    }

    private static List<Optional<RuntimeException>> runTwo(Runnable a, Runnable b) throws InterruptedException {
        AtomicReference<Optional<RuntimeException>> outcomeA = new AtomicReference<>();
        AtomicReference<Optional<RuntimeException>> outcomeB = new AtomicReference<>();
        Thread racerA = new Thread(() -> outcomeA.set(outcomeOf(a)), "racer-A");
        Thread racerB = new Thread(() -> outcomeB.set(outcomeOf(b)), "racer-B");
        racerA.start();
        racerB.start();
        racerA.join();
        racerB.join();
        assertNotNull(outcomeA.get(), "racer-A must have finished");
        assertNotNull(outcomeB.get(), "racer-B must have finished");
        return List.of(outcomeA.get(), outcomeB.get());
    }

    private static Optional<RuntimeException> outcomeOf(Runnable work) {
        try {
            work.run();
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of(e);
        }
    }

    private static RuntimeException exactlyOneLoser(List<Optional<RuntimeException>> outcomes) {
        assertNotEquals(outcomes.get(0).isEmpty(), outcomes.get(1).isEmpty(), "exactly one racer must succeed");
        return outcomes.get(0).orElseGet(() -> outcomes.get(1).orElseThrow());
    }

    private static ReadableGraph awaitThen(CyclicBarrier barrier, ReadableGraph slice) {
        await(barrier);
        return slice;
    }

    @Test
    void aSecondContentGraphOnTheSameDatasetIsRejected() {
        create(SUBJECT, CODE, "v1", "crit one");
        VersionedWriteFunnel other = new VersionedWriteFunnel(lifecycle, recordingGate(false), NS + "graph/other",
                VersionedWriteFunnel.DEFAULT_WRITE_CONFLICT, clock);

        assertThrows(ContentGraphMismatchException.class, () -> other.create(DATASET, OTHER, "FR-2", null,
                Signals::unexpected, Signals::unexpected, (tx, current) -> requirement(OTHER, "FR-2", "x")));
    }

    private Optional<RuntimeException> race(RevisionToken token, String name, CyclicBarrier barrier) {
        try {
            funnel.compareAndUpdate(DATASET, SUBJECT, token, null, Signals::unexpected, RevisionMismatch::new,
                    (tx, current) -> {
                        await(barrier);
                        return requirement(SUBJECT, CODE, name);
                    });
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException e) {
            throw new IllegalStateException(e);
        }
    }

    private void create(String subject, String code, String titleText, String... criteria) {
        funnel.create(DATASET, subject, code, null, Signals::unexpected, Signals::unexpected,
                (tx, current) -> requirement(subject, code, titleText, criteria));
    }

    private void update(String subject, String titleText, String... criteria) {
        funnel.compareAndUpdate(DATASET, subject, currentToken(subject), null, Signals::unexpected,
                Signals::unexpected, (tx, current) -> requirement(subject, CODE, titleText, criteria));
    }

    private Graph requirement(String subject, String code, String titleText, String... criteria) {
        IRI s = rdf.createIRI(subject);
        Graph g = rdf.createGraph();
        g.add(s, VocabDct.IDENTIFIER, rdf.createLiteral(code));
        g.add(s, title, rdf.createLiteral(titleText));
        for (int i = 0; i < criteria.length; i++) {
            IRI ac = rdf.createIRI(subject + "#AC-" + (i + 1));
            g.add(s, criterion, ac);
            g.add(ac, text, rdf.createLiteral(criteria[i]));
        }
        return g;
    }

    private RevisionToken currentToken(String subject) {
        return tx(t -> funnel.currentToken(t, subject));
    }

    private ReadableGraph draft(String subject) {
        return tx(t -> sameContentGraph().readDraft(t, rdf.createIRI(subject)).triples());
    }

    private ResourceVersioning sameContentGraph() {
        return new ResourceVersioningNamedGraphs(rdf.createIRI(CONTENT_GRAPH), new ContentAddressedIRIGeneratorSexpr(),
                clock);
    }

    private Set<String> texts(ReadableGraph graph) {
        return lexicalForms(graph, text);
    }

    private Set<String> titles(ReadableGraph graph) {
        return lexicalForms(graph, title);
    }

    private static Set<String> lexicalForms(ReadableGraph graph, IRI predicate) {
        return graph.stream().filter(t -> t.getPredicate().equals(predicate))
                .map(t -> ((io.kogn.rdf.terms.Literal) t.getObject()).getLexicalForm())
                .collect(Collectors.toSet());
    }

    private <T> T tx(Function<DatasetTx, T> work) {
        try (DatasetHandle handle = lifecycle.acquire(DATASET)) {
            return handle.transactor().inTransaction(work);
        }
    }

    /** A gate recording the graph it validates; rejects every write if asked to. */
    private ShaclWriteGate recordingGate(boolean reject) {
        ShaclValidation validation = (data, shapes, options) -> {
            validated.set(data);
            if (!reject) {
                return new ShaclReport(true, List.of());
            }
            return new ShaclReport(false, List.of(new ShaclResult(rdf.createIRI(SUBJECT), NS + "title",
                    Severity.VIOLATION, rdf.createIRI("http://www.w3.org/ns/shacl#Violation"),
                    List.of(ShaclMessage.untagged("rejected")))));
        };
        return new ShaclWriteGate(validation, rdf.createGraph(), rdf.createGraph(), ValidationOptions.defaults(),
                DisplayLocale.DEFAULT);
    }

    private static final class Signals {
        static RuntimeException unexpected() {
            return new IllegalStateException("unexpected signal");
        }
    }

    private static final class AlreadyExists extends RuntimeException {
    }

    private static final class DuplicateCode extends RuntimeException {
    }

    private static final class RevisionMismatch extends RuntimeException {
    }

    private static final class NotFound extends RuntimeException {
        NotFound() {
        }

        NotFound(String subjectIri) {
            super(subjectIri);
        }

        static NotFound of(String subjectIri) {
            return new NotFound(subjectIri);
        }
    }

    private static final class Referenced extends RuntimeException {
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
