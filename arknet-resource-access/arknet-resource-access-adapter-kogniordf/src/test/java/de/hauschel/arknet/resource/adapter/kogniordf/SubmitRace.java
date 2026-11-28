// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.Triple;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;

/**
 * Two writers of one project through {@code submit} on the real on-disk store, one of them pinned
 * after a chosen read inside its write transaction until the other has committed. The counterpart
 * of the core's {@code *RealStoreConcurrencyTest} interleavings: the same invariant, held by the
 * engine's commit race and second run instead of a typed repository's compare-and-set.
 */
final class SubmitRace implements AutoCloseable {

    private final DatasetLifecycle lifecycle;
    private final ResourceRepository repository;
    private final StoreReader reader;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    SubmitRace(final Path storageRoot) {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        repository = KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        reader = new StoreReader(lifecycle);
    }

    /**
     * Submits a document without any pause, as the given state and the concurrent writer do.
     *
     * @param document the document
     * @return the result
     */
    SubmitResult submit(final Document document) {
        return service(repository).submit(SubmitRealStoreTest.PROJECT, document.turtle());
    }

    /**
     * Submits a document, as {@link #submit}, and returns the IRI of the one resource it created.
     *
     * @param document a document with one create
     * @return the IRI of the created resource
     */
    String created(final Document document) {
        return submit(document).changes().stream().filter(change -> change.code() != null)
                .findFirst().orElseThrow().iri();
    }

    /**
     * Runs the racer until it has done the pinned read, then the concurrent writer to its commit, then
     * lets the racer go on.
     *
     * @param pauseAfter the read the racer pauses after
     * @param pauseOn    the resource IRI or code prefix of that read
     * @param racer      the paused writer's document
     * @param concurrent the concurrent writer's document
     * @return the racer's outcome
     */
    Outcome race(final PausingBatch.Read pauseAfter, final String pauseOn, final Document racer,
            final Document concurrent) {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        ResourceRepository pausing = (projectId, writes) -> {
            attempts.incrementAndGet();
            repository.writeAll(projectId, batch -> writes.accept(
                    new PausingBatch(batch, pauseAfter, pauseOn, firstRead, read, resume)));
        };
        Future<SubmitResult> racing = executor.submit(
                () -> service(pausing).submit(SubmitRealStoreTest.PROJECT, racer.turtle()));
        try {
            assertTrue(read.await(PausingBatch.WAIT_SECONDS, TimeUnit.SECONDS),
                    "the racer never did the read it pauses after");
            SubmitResult winner = executor.submit(() -> submit(concurrent))
                    .get(PausingBatch.WAIT_SECONDS, TimeUnit.SECONDS);
            resume.countDown();
            return new Outcome(winner, racing, attempts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("the concurrent writer failed", e);
        }
    }

    /**
     * The values a resource holds in one field: a literal as {@code lexicalForm@language}, a
     * reference as its IRI.
     *
     * @param resource the resource IRI
     * @param field    the property IRI
     * @return the values
     */
    Set<String> values(final String resource, final String field) {
        Set<String> values = new TreeSet<>();
        for (Triple triple : reader.outgoing(SubmitRealStoreTest.PROJECT, resource)) {
            if (!triple.predicate().equals(field)) {
                continue;
            }
            if (triple.object() instanceof RdfNode.Literal literal) {
                values.add(literal.lexicalForm() + "@" + literal.languageTag());
            } else if (triple.object() instanceof RdfNode.Resource target) {
                values.add(target.iri());
            }
        }
        return values;
    }

    StoreReader reader() {
        return reader;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        lifecycle.shutDownAll();
    }

    private SubmitService service(final ResourceRepository store) {
        return new SubmitService(KognioRdfResourceRepositoryFactory.schema(), store,
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                Clock.systemDefaultZone());
    }

    /**
     * What a race left behind.
     *
     * @param winner   the concurrent writer's result
     * @param racer    the paused writer, finished or failed
     * @param attempts how often the paused writer ran its document
     */
    record Outcome(SubmitResult winner, Future<SubmitResult> racer, AtomicInteger attempts) {

        /**
         * The racer's result, once it committed.
         *
         * @return the result
         * @throws Exception when the racer failed
         */
        SubmitResult racerResult() throws Exception {
            return racer.get(PausingBatch.WAIT_SECONDS, TimeUnit.SECONDS);
        }

        int runs() {
            return attempts.get();
        }
    }
}
