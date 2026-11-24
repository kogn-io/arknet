// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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

    private SubmitService service(final ResourceRepository store) {
        return new SubmitService(KognioRdfResourceRepositoryFactory.schema(), store,
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle));
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
