// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetShutdownOutcome;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.InfrastructureGraphs;
import de.hauschel.arknet.prj.domain.DatasetInUseException;

/**
 * Integration test for {@link KognioRdfProjectDatasets} against a persistent RDF4J-backed store -
 * persistent, because what deletion has to remove is the on-disk dataset, and because the store's
 * dataset listing is a directory listing an in-memory store has nothing to say about.
 */
class KognioRdfProjectDatasetsTest {

    private static final ProjectId PROJECT = new ProjectId("arknet-plugin");
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String SKOS_CONCEPT = "http://www.w3.org/2004/02/skos/core#Concept";

    @TempDir
    Path storageDir;

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfProjectDatasets datasets;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(storageDir);
        final DatasetLifecycle datasetLifecycle =
                new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageDir);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        datasets = new KognioRdfProjectDatasets(datasetLifecycle, 2, Duration.ofMillis(1));
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void countsTheModelResourcesOfEachTypeByTheirPrefixedTypeName() {
        insert(REQUIREMENTS_GRAPH, "<urn:req:1> a <" + ARKREQ + "FunctionalRequirement> . "
                + "<urn:req:2> a <" + ARKREQ + "FunctionalRequirement> . "
                + "<urn:req:3> a <" + ARKREQ + "NonFunctionalRequirement> .");
        insert(TERMS_GRAPH, "<urn:term:1> a <" + SKOS_CONCEPT + "> .");

        assertEquals(Map.of("arkreq:FunctionalRequirement", 2L, "arkreq:NonFunctionalRequirement", 1L,
                "skos:Concept", 1L), datasets.modelResourceCounts(PROJECT));
    }

    /**
     * Every registered project carries its self-description, and every write leaves a revision -
     * neither is model content, so a project holding nothing else counts as empty and needs no
     * confirmation to be deleted.
     */
    @Test
    void theSelfDescriptionAndTheRevisionTrailAreNotModelResources() {
        insert(ArkprjVocabulary.IDENTITY_GRAPH, "<urn:project> a <" + ArkprjVocabulary.PROJECT_TYPE + "> .");
        insert(ArkprovVocabulary.PROVENANCE_GRAPH, "<urn:rev:1> a <http://www.w3.org/ns/prov#Entity> .");

        assertEquals(Map.of(), datasets.modelResourceCounts(PROJECT));
    }

    /** Writes via the funnel leave one {@code arkprov:Revision} each; activities and heads do not count. */
    @Test
    void countsTheRevisionsOfTheProvenanceTrailAndNothingElseInIt() {
        insert(ArkprovVocabulary.PROVENANCE_GRAPH, "<urn:rev:1> a <" + ArkprovVocabulary.REVISION_TYPE + "> , <"
                + ArkprovVocabulary.ENTITY_TYPE + "> . "
                + "<urn:rev:2> a <" + ArkprovVocabulary.REVISION_TYPE + "> , <" + ArkprovVocabulary.ENTITY_TYPE
                + "> . <urn:act:1> a <" + ArkprovVocabulary.ACTIVITY_TYPE + "> .");

        assertEquals(2L, datasets.revisionCount(PROJECT));
        assertEquals(Map.of(), datasets.modelResourceCounts(PROJECT));
    }

    @Test
    void aDatasetWithoutAProvenanceTrailHoldsNoRevisions() {
        insert(REQUIREMENTS_GRAPH, "<urn:req:1> a <" + ARKREQ + "FunctionalRequirement> .");

        assertEquals(0L, datasets.revisionCount(PROJECT));
    }

    @Test
    void countingTheRevisionsOfAMissingDatasetReportsNoneAndDoesNotCreateIt() {
        assertEquals(0L, datasets.revisionCount(PROJECT));

        assertFalse(lifecycle.list().contains(new DatasetId(PROJECT.value())));
    }

    /** The filter comes from the shared infrastructure-graph set, so every graph in it is skipped. */
    @Test
    void noInfrastructureGraphContributesToTheModelCount() {
        for (final String graph : InfrastructureGraphs.IRIS) {
            insert(graph, "<urn:infra:" + graph.hashCode() + "> a <" + ARKREQ + "FunctionalRequirement> .");
        }

        assertEquals(Map.of(), datasets.modelResourceCounts(PROJECT));
    }

    @Test
    void countingAMissingDatasetReportsNothingAndDoesNotCreateIt() {
        assertEquals(Map.of(), datasets.modelResourceCounts(PROJECT));

        assertFalse(lifecycle.list().contains(new DatasetId(PROJECT.value())));
    }

    @Test
    void deleteRemovesTheDatasetFromTheStore() {
        insert(REQUIREMENTS_GRAPH, "<urn:req:1> a <" + ARKREQ + "FunctionalRequirement> .");

        datasets.delete(PROJECT);

        assertFalse(lifecycle.list().contains(new DatasetId(PROJECT.value())));
    }

    @Test
    void deleteLeavesOtherDatasetsAlone() {
        final ProjectId other = new ProjectId("arknet");
        insert(REQUIREMENTS_GRAPH, "<urn:req:1> a <" + ARKREQ + "FunctionalRequirement> .");
        insertInto(other, REQUIREMENTS_GRAPH, "<urn:req:1> a <" + ARKREQ + "FunctionalRequirement> .");

        datasets.delete(PROJECT);

        assertTrue(lifecycle.list().contains(new DatasetId(other.value())));
        assertEquals(Map.of("arkreq:FunctionalRequirement", 1L), datasets.modelResourceCounts(other));
    }

    @Test
    void deletingAMissingDatasetIsNoErrorAndDoesNotCreateIt() {
        datasets.delete(PROJECT);

        assertFalse(lifecycle.list().contains(new DatasetId(PROJECT.value())));
    }

    /**
     * A concurrent call holding the dataset open must not have it torn down underneath it; once
     * the bounded wait is used up the removal is reported as refused and the dataset survives.
     */
    @Test
    void aDatasetHeldOpenByAConcurrentCallIsKeptAndReportedAsInUse() {
        insert(REQUIREMENTS_GRAPH, "<urn:req:1> a <" + ARKREQ + "FunctionalRequirement> .");

        try (DatasetHandle lease = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            final DatasetInUseException ex = assertThrows(DatasetInUseException.class,
                    () -> datasets.delete(PROJECT));
            assertEquals(PROJECT, ex.datasetId());
        }

        assertEquals(Map.of("arkreq:FunctionalRequirement", 1L), datasets.modelResourceCounts(PROJECT));
    }

    /**
     * An interrupt ends the wait: the removal is not retried any further and is reported as in
     * use, with the interrupt flag left set for the caller (review of kogn-io/arknet#712).
     */
    @Test
    void anInterruptStopsRetryingAndKeepsTheInterruptFlag() {
        insert(REQUIREMENTS_GRAPH, "<urn:req:1> a <" + ARKREQ + "FunctionalRequirement> .");
        final AtomicInteger deleteCalls = new AtomicInteger();
        final DatasetLifecycle counting = new DatasetLifecycle() {
            @Override
            public DatasetHandle acquire(final DatasetId id) {
                return lifecycle.acquire(id);
            }

            @Override
            public DatasetShutdownOutcome shutDown(final DatasetId id) {
                return lifecycle.shutDown(id);
            }

            @Override
            public void shutDownAll() {
                lifecycle.shutDownAll();
            }

            @Override
            public void delete(final DatasetId id) {
                deleteCalls.incrementAndGet();
                lifecycle.delete(id);
            }

            @Override
            public Set<DatasetId> list() {
                return lifecycle.list();
            }
        };
        final KognioRdfProjectDatasets patient = new KognioRdfProjectDatasets(counting, 50, Duration.ofSeconds(1));

        try (DatasetHandle lease = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(DatasetInUseException.class, () -> patient.delete(PROJECT));
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt must not be swallowed");
            } finally {
                Thread.interrupted();
            }
        }

        assertEquals(1, deleteCalls.get(), "no further attempt after the interrupt");
    }

    private void insert(final String graph, final String triples) {
        insertInto(PROJECT, graph, triples);
    }

    private void insertInto(final ProjectId project, final String graph, final String triples) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.value()))) {
            handle.sparqlUpdate().update("INSERT DATA { GRAPH <" + graph + "> { " + triples + " } }");
        }
    }
}
