// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Verifies {@link KognioRdfDatasetInventory} against a real store: what it reports is what is
 * physically there.
 *
 * <p>The reserved system dataset is the case worth a test of its own. It is always present once
 * anything has been registered, is not a project, and {@link ProjectId} rejects its value in its
 * constructor - so an implementation that mapped before filtering would not merely report one entry
 * too many, it would throw on every call and take adoption down with it.</p>
 */
class KognioRdfDatasetInventoryTest {

    @TempDir
    Path storageDir;

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfDatasetInventory inventory;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(storageDir);
        // PERSISTENT, unlike the sibling registry test: what is inventoried here is the store's
        // on-disk directory listing, which an in-memory store has nothing to say about.
        final DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                DatasetStoreConfig.persistentDefault(), storageDir);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        inventory = new KognioRdfDatasetInventory(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void reportsNothingForAnEmptyStore() {
        assertTrue(inventory.existingDatasets().isEmpty());
    }

    @Test
    void reportsEveryDatasetPresentInTheStore() {
        create("arknet");
        create("second-project");

        assertEquals(Set.of(new ProjectId("arknet"), new ProjectId("second-project")),
                inventory.existingDatasets());
    }

    @Test
    void excludesTheReservedSystemDatasetTheRegistryItselfLivesIn() {
        create(ProjectId.RESERVED_SYSTEM_DATASET);
        create("arknet");

        assertEquals(Set.of(new ProjectId("arknet")), inventory.existingDatasets());
    }

    /** Materialises a dataset by acquiring it and writing one statement into it. */
    private void create(final String datasetId) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(datasetId))) {
            handle.sparqlUpdate().update("INSERT DATA { GRAPH <urn:test> { <urn:s> <urn:p> <urn:o> } }");
        }
    }
}
