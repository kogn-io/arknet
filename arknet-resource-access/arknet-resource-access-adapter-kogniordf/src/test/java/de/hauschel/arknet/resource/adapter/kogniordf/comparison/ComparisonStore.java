// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.StoreResource;
import de.hauschel.arknet.persistence.VersionEntry;
import de.hauschel.arknet.resource.adapter.kogniordf.KognioRdfResourceRepositoryFactory;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.domain.ResourceSchema;

/**
 * One store for a whole comparison test class: one dataset lifecycle the typed cores of the class
 * are built over, the {@code submit} engine over the same lifecycle, and a fresh pair of projects
 * per case - so a class of many cases opens one store, not one per case.
 *
 * <p>The engine is built exactly as the composition root builds it, over the shipped shapes and
 * ontologies the test classpath carries. The comparison reads the content graph of each project
 * and, per resource, the shape of its history (entry count, tombstone, retained code); creators,
 * instants and comments of the history are not compared.</p>
 */
public final class ComparisonStore implements AutoCloseable {

    private static final String LEDGER = "ledger ";

    private final DatasetLifecycle lifecycle;
    private final SubmitService engine;
    private final StoreReader reader;
    private final ResourceSchema schema;
    private final AtomicInteger cases = new AtomicInteger();
    private final Map<ProjectId, Map<String, String>> seen = new ConcurrentHashMap<>();

    private ComparisonStore(final DatasetLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.schema = KognioRdfResourceRepositoryFactory.schema();
        this.engine = new SubmitService(schema,
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                Clock.systemDefaultZone());
        this.reader = new StoreReader(lifecycle);
    }

    /**
     * Opens a persistent store under a directory.
     *
     * @param storageRoot the directory, typically a class-wide {@code @TempDir}
     * @return the store
     */
    public static ComparisonStore under(final Path storageRoot) {
        return new ComparisonStore(new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot));
    }

    /**
     * The lifecycle to build the typed cores of a test class over.
     *
     * @return the lifecycle
     */
    public DatasetLifecycle lifecycle() {
        return lifecycle;
    }

    /**
     * Two fresh, empty projects for one case.
     *
     * @param caseName a readable name, part of the project ids
     * @return the pair
     */
    public ProjectPair pair(final String caseName) {
        String stem = "cmp-" + cases.incrementAndGet() + "-" + caseName;
        return new ProjectPair(new ProjectId(stem + "-core"), new ProjectId(stem + "-submit"));
    }

    /**
     * Applies a document to a project.
     *
     * @param project  the project
     * @param document the document
     */
    void submit(final ProjectId project, final Document document) {
        engine.submit(project, document.turtle());
    }

    /**
     * The canonical content graph of a project, together with the history of every resource the
     * project has shown so far - also one deleted since: its number of history entries, whether the
     * last one is a tombstone, and whether its code is retained. A delete that drops the content but
     * writes no tombstone, or frees the code, thus differs from one that does. Children have no
     * history of their own.
     *
     * @param project the project
     * @return the graph
     */
    CanonicalGraph graph(final ProjectId project) {
        CanonicalGraph graph = CanonicalGraph.of(reader.readSnapshot(project).resources().stream()
                .map(StoreResource::outgoing)
                .flatMap(List::stream)
                .toList(), schema);
        Map<String, String> known = seen.computeIfAbsent(project, ignored -> new ConcurrentHashMap<>());
        known.putAll(graph.resources());
        Set<String> ledger = new TreeSet<>();
        known.forEach((name, iri) -> {
            List<VersionEntry> history = reader.history(project, iri);
            String last = history.isEmpty() ? "none" : history.getLast().tombstone() ? "tombstone" : "version";
            ledger.add(LEDGER + name + " history " + history.size() + " last " + last);
            if (name.startsWith(CanonicalGraph.CODE_SCHEME)) {
                ledger.add(LEDGER + name + " retained "
                        + reader.findDeletedByCode(project, name.substring(CanonicalGraph.CODE_SCHEME.length())).isPresent());
            }
        });
        return graph.plus(ledger);
    }

    @Override
    public void close() {
        lifecycle.shutDownAll();
    }

    /**
     * The two projects of one case: the typed core writes into {@link #core()}, {@code submit} into
     * {@link #submit()}. Preconditions run on both through the core, so both start from the same graph.
     *
     * @param core   the project the core operation runs on
     * @param submit the project the document is applied to
     */
    public record ProjectPair(ProjectId core, ProjectId submit) {

        /**
         * Runs a precondition through the core on both projects.
         *
         * @param step the step, given the project
         * @param <T>  what the step returns
         * @return what it returned on each project
         */
        public <T> Given<T> given(final Function<ProjectId, T> step) {
            return new Given<>(step.apply(core), step.apply(submit));
        }

        /**
         * Runs a precondition without a result through the core on both projects.
         *
         * @param step the step, given the project
         */
        public void givenOnBoth(final Consumer<ProjectId> step) {
            step.accept(core);
            step.accept(submit);
        }
    }

    /**
     * What a precondition returned on each project of a pair - the same resource, with the identity
     * each project gave it.
     *
     * @param core   the result on the core project
     * @param submit the result on the submit project
     * @param <T>    the result type
     */
    public record Given<T>(T core, T submit) {
    }
}
