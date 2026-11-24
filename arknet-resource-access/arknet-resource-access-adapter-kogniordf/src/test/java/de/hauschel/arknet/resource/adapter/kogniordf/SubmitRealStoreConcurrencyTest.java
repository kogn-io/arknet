// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ChildFragments;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.out.ResourceBatch;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The engine reads other resources inside its transaction - every neighbour a written slice reaches
 * goes into the gate's asserted context. Here a concurrent writer deletes such a neighbour after the
 * engine read it and before it commits: the engine's commit must not land a reference to the
 * deleted resource. It loses the commit race, runs the document again against the new state, and
 * the shapes reject the reference.
 *
 * <p>The other direction pins the delete guard: a concurrent writer adds an edge to a resource after
 * the engine read the edges pointing at it and before the delete commits. The read happens inside
 * the write transaction, so the delete loses its commit, runs again, and the guard finds the new
 * edge.</p>
 *
 * <p>A third pins the condition of a status transition: the condition reads the edge target after
 * the document inside the write transaction, so a concurrent writer that changes the target between
 * that read and the commit costs the transition its commit, and the second run checks the condition
 * against the new state.</p>
 *
 * <p>A fourth pins the rules that compare a resource before and after a document: the state before
 * is the slice read inside the write transaction, so a concurrent writer that publishes a paper
 * between that read and the commit costs an edit of its frozen title the commit, and the second run
 * judges the edit by the published status.</p>
 */
class SubmitRealStoreConcurrencyTest {

    private static final long WAIT_SECONDS = 30;

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private ResourceRepository repository;
    private StoreReader reader;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        repository = KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        reader = new StoreReader(lifecycle);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        lifecycle.shutDownAll();
    }

    @Test
    void aNeighbourDeletedAfterTheEngineReadItIsNotReferencedByTheCommit() throws Exception {
        // given
        SubmitService plain = service(repository);
        String term = SubmitRealStoreTest.iriOf(plain.submit(SubmitRealStoreTest.PROJECT, SubmitRealStoreTest.PREFIXES
                + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ; skos:prefLabel \"A\"@en ;"
                + " skos:definition \"Defined.\"@en .\n"), "TERM-1");
        CountDownLatch termRead = new CountDownLatch(1);
        CountDownLatch termDeleted = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        ResourceRepository pausing = (projectId, writes) -> {
            attempts.incrementAndGet();
            repository.writeAll(projectId, batch -> writes.accept(
                    new PausingBatch(batch, term, false, firstRead, termRead, termDeleted)));
        };

        // when
        Future<SubmitResult> racer = executor.submit(() -> service(pausing).submit(SubmitRealStoreTest.PROJECT,
                SubmitRealStoreTest.PREFIXES + "[] a arkres:Create ; arkres:object _:fr .\n"
                        + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
                        + " dcterms:description \"Writes it.\"@en ; arkreq:usesTerm <" + term + "> ;"
                        + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Works.\"@en ] .\n"));
        assertTrue(termRead.await(WAIT_SECONDS, TimeUnit.SECONDS), "the engine never read the term");
        Future<SubmitResult> deleter = executor.submit(() -> plain.submit(SubmitRealStoreTest.PROJECT,
                SubmitRealStoreTest.PREFIXES + "[] a arkres:Delete ; arkres:object <" + term + "> .\n"));
        deleter.get(WAIT_SECONDS, TimeUnit.SECONDS);
        termDeleted.countDown();

        // then
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> racer.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(SubmitRejectedException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("https://w3id.org/arknet/requirements#usesTerm"),
                failure.getCause().getMessage());
        assertEquals(2, attempts.get(), "the lost commit runs the document once more");
        assertTrue(reader.findByIdentifier(SubmitRealStoreTest.PROJECT, "FR-1").isEmpty());
        assertTrue(reader.outgoing(SubmitRealStoreTest.PROJECT, term).isEmpty());
    }

    @Test
    void anEdgeCommittedAfterTheDeleteReadTheEdgesKeepsTheResource() throws Exception {
        // given
        SubmitService plain = service(repository);
        String term = SubmitRealStoreTest.iriOf(plain.submit(SubmitRealStoreTest.PROJECT, SubmitRealStoreTest.PREFIXES
                + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ; skos:prefLabel \"A\"@en ;"
                + " skos:definition \"Defined.\"@en .\n"), "TERM-1");
        CountDownLatch edgesRead = new CountDownLatch(1);
        CountDownLatch edgeCommitted = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        ResourceRepository pausing = (projectId, writes) -> {
            attempts.incrementAndGet();
            repository.writeAll(projectId, batch -> writes.accept(
                    new PausingBatch(batch, term, true, firstRead, edgesRead, edgeCommitted)));
        };

        // when
        Future<SubmitResult> deleter = executor.submit(() -> service(pausing).submit(SubmitRealStoreTest.PROJECT,
                SubmitRealStoreTest.PREFIXES + "[] a arkres:Delete ; arkres:object <" + term + "> .\n"));
        assertTrue(edgesRead.await(WAIT_SECONDS, TimeUnit.SECONDS), "the engine never read the edges to the term");
        Future<SubmitResult> writer = executor.submit(() -> plain.submit(SubmitRealStoreTest.PROJECT,
                SubmitRealStoreTest.PREFIXES + "[] a arkres:Create ; arkres:object _:fr .\n"
                        + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
                        + " dcterms:description \"Writes it.\"@en ; arkreq:usesTerm <" + term + "> ;"
                        + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Works.\"@en ] .\n"));
        writer.get(WAIT_SECONDS, TimeUnit.SECONDS);
        edgeCommitted.countDown();

        // then
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> deleter.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(SubmitRejectedException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("https://w3id.org/arknet/requirements#usesTerm"),
                failure.getCause().getMessage());
        assertEquals(2, attempts.get(), "the lost commit runs the document once more");
        assertFalse(reader.findByIdentifier(SubmitRealStoreTest.PROJECT, "TERM-1").isEmpty());
        assertFalse(reader.findByIdentifier(SubmitRealStoreTest.PROJECT, "FR-1").isEmpty());
    }

    @Test
    void anEdgeTargetChangedAfterTheConditionReadItFailsTheTransitionOnItsSecondRun() throws Exception {
        // given a done ticket replaced by a done ticket, over the ticket fixture's rule data
        ResourceRepository tickets = StatusTransitionsRealStoreTest.repository(lifecycle);
        SubmitService plain = StatusTransitionsRealStoreTest.service(lifecycle, tickets);
        String replacement = doneTicket(plain, "New");
        String old = doneTicket(plain, "Old");
        plain.submit(StatusTransitionsRealStoreTest.PROJECT, StatusTransitionsRealStoreTest.PREFIXES
                + "[] a arkres:Update ; arkres:object <" + old + "> ; arkres:set [ fx:replacedBy <" + replacement
                + "> ] .\n");
        CountDownLatch targetRead = new CountDownLatch(1);
        CountDownLatch targetReopened = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        ResourceRepository pausing = (projectId, writes) -> {
            attempts.incrementAndGet();
            tickets.writeAll(projectId, batch -> writes.accept(
                    new PausingBatch(batch, replacement, false, firstRead, targetRead, targetReopened)));
        };

        // when the old ticket closes while the replacement is reopened between the read and the commit
        Future<SubmitResult> closer = executor.submit(() -> StatusTransitionsRealStoreTest.service(lifecycle, pausing)
                .submit(StatusTransitionsRealStoreTest.PROJECT, StatusTransitionsRealStoreTest.PREFIXES
                        + "[] a arkres:Update ; arkres:object <" + old + "> ; arkres:set [ fx:state fx:Closed ] .\n"));
        assertTrue(targetRead.await(WAIT_SECONDS, TimeUnit.SECONDS), "the engine never read the edge target");
        executor.submit(() -> plain.submit(StatusTransitionsRealStoreTest.PROJECT,
                StatusTransitionsRealStoreTest.PREFIXES + "[] a arkres:Update ; arkres:object <" + replacement
                        + "> ; arkres:set [ fx:state fx:Open ] .\n")).get(WAIT_SECONDS, TimeUnit.SECONDS);
        targetReopened.countDown();

        // then
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> closer.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(SubmitRejectedException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("A ticket is closed only when a done ticket replaces it."),
                failure.getCause().getMessage());
        assertEquals(2, attempts.get(), "the lost commit runs the document once more");
        assertTrue(reader.outgoing(StatusTransitionsRealStoreTest.PROJECT, old).stream()
                .anyMatch(triple -> triple.predicate().equals(StatusTransitionsRealStoreTest.FX + "state")
                        && triple.object().equals(new RdfNode.Resource(StatusTransitionsRealStoreTest.FX + "Done"))));
    }

    @Test
    void aStatusCommittedAfterTheSliceReadFreezesTheGroupOnTheSecondRun() throws Exception {
        // given a draft paper, over the paper fixture's rule data
        ResourceRepository papers = BeforeAfterRulesRealStoreTest.repository(lifecycle);
        SubmitService plain = BeforeAfterRulesRealStoreTest.service(lifecycle, papers);
        String paper = BeforeAfterRulesRealStoreTest.created(plain.submit(BeforeAfterRulesRealStoreTest.PROJECT,
                BeforeAfterRulesRealStoreTest.PREFIXES + "[] a arkres:Create ; arkres:object _:p .\n"
                        + "_:p a fx:Paper ; fx:title \"Title\"@en ; fx:acronym \"PP\"@en .\n"));
        CountDownLatch sliceRead = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        ResourceRepository pausing = (projectId, writes) -> {
            attempts.incrementAndGet();
            papers.writeAll(projectId, batch -> writes.accept(
                    new PausingBatch(batch, paper, false, firstRead, sliceRead, published)));
        };

        // when the title is edited while a concurrent writer publishes the paper
        Future<SubmitResult> editor = executor.submit(() -> BeforeAfterRulesRealStoreTest.service(lifecycle, pausing)
                .submit(BeforeAfterRulesRealStoreTest.PROJECT, BeforeAfterRulesRealStoreTest.PREFIXES
                        + "[] a arkres:Update ; arkres:object <" + paper + "> ;"
                        + " arkres:set [ fx:title \"Rewritten\"@en ] .\n"));
        assertTrue(sliceRead.await(WAIT_SECONDS, TimeUnit.SECONDS), "the engine never read the paper");
        executor.submit(() -> plain.submit(BeforeAfterRulesRealStoreTest.PROJECT,
                BeforeAfterRulesRealStoreTest.PREFIXES + "[] a arkres:Update ; arkres:object <" + paper
                        + "> ; arkres:set [ fx:state fx:Published ] .\n")).get(WAIT_SECONDS, TimeUnit.SECONDS);
        published.countDown();

        // then
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> editor.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(SubmitRejectedException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("https://w3id.org/arknet/resource#fieldGroup"),
                failure.getCause().getMessage());
        assertEquals(2, attempts.get(), "the lost commit runs the document once more");
        assertTrue(reader.outgoing(BeforeAfterRulesRealStoreTest.PROJECT, paper).stream()
                .anyMatch(triple -> triple.predicate().equals(BeforeAfterRulesRealStoreTest.FX + "title")
                        && triple.object() instanceof RdfNode.Literal literal
                        && literal.lexicalForm().equals("Title")));
    }

    private static String doneTicket(final SubmitService service, final String title) {
        String ticket = StatusTransitionsRealStoreTest.created(service.submit(StatusTransitionsRealStoreTest.PROJECT,
                StatusTransitionsRealStoreTest.PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n"
                        + "_:t a fx:Ticket ; fx:title \"" + title + "\" .\n"));
        service.submit(StatusTransitionsRealStoreTest.PROJECT, StatusTransitionsRealStoreTest.PREFIXES
                + "[] a arkres:Update ; arkres:object <" + ticket + "> ;"
                + " arkres:set [ fx:state fx:Done ; fx:note \"Fixed.\" ] .\n");
        return ticket;
    }

    private SubmitService service(final ResourceRepository store) {
        return new SubmitService(KognioRdfResourceRepositoryFactory.schema(), store,
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                Clock.systemDefaultZone());
    }

    /**
     * Pauses the first read of one resource - its slice, or the edges pointing at it - until a
     * concurrent writer has committed.
     */
    private static final class PausingBatch implements ResourceBatch {

        private final ResourceBatch delegate;
        private final String pauseOn;
        private final boolean onEdges;
        private final AtomicBoolean firstRead;
        private final CountDownLatch read;
        private final CountDownLatch resume;

        private PausingBatch(final ResourceBatch delegate, final String pauseOn, final boolean onEdges,
                final AtomicBoolean firstRead, final CountDownLatch read, final CountDownLatch resume) {
            this.delegate = delegate;
            this.pauseOn = pauseOn;
            this.onEdges = onEdges;
            this.firstRead = firstRead;
            this.read = read;
            this.resume = resume;
        }

        @Override
        public ReadableGraph slice(final IRI subject) {
            ReadableGraph slice = delegate.slice(subject);
            if (!onEdges) {
                pauseOnFirstRead(subject);
            }
            return slice;
        }

        @Override
        public ReadableGraph pointingAt(final IRI resource) {
            ReadableGraph edges = delegate.pointingAt(resource);
            if (onEdges) {
                pauseOnFirstRead(resource);
            }
            return edges;
        }

        private void pauseOnFirstRead(final IRI subject) {
            if (subject.getIRIString().equals(pauseOn) && firstRead.getAndSet(false)) {
                read.countDown();
                try {
                    if (!resume.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the concurrent write never finished");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public int highestRunningNumber(final String codePrefix) {
            return delegate.highestRunningNumber(codePrefix);
        }

        @Override
        public ChildFragments childFragments(final IRI parent, final ReadableGraph current) {
            return delegate.childFragments(parent, current);
        }

        @Override
        public List<String> violations(final IRI shape, final IRI focus, final ReadableGraph data) {
            return delegate.violations(shape, focus, data);
        }

        @Override
        public void create(final IRI subject, final String code, final ReadableGraph slice,
                final ReadableGraph context) {
            delegate.create(subject, code, slice, context);
        }

        @Override
        public void update(final IRI subject, final ReadableGraph slice, final ReadableGraph context) {
            delegate.update(subject, slice, context);
        }

        @Override
        public void delete(final IRI subject, final String code) {
            delegate.delete(subject, code);
        }
    }
}
