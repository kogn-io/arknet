// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;

class LegacyLayoutRefusingDatasetLifecycleTest {

    private static final DatasetId PROJECT = new DatasetId("legacy-layout-test");
    private static final DatasetId SYSTEM = new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET);

    @TempDir
    Path storageDir;

    private DatasetLifecycle raw;
    private LegacyLayoutRefusingDatasetLifecycle guarded;

    @BeforeEach
    void setUp() {
        raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        guarded = new LegacyLayoutRefusingDatasetLifecycle(raw);
    }

    @AfterEach
    void tearDown() {
        raw.shutDownAll();
    }

    @Test
    void aDatasetWithAComponentGraphIsRefusedWithAMessageNamingIt() {
        insert(PROJECT, "https://w3id.org/arknet/model/requirements");

        assertThatThrownBy(() -> guarded.acquire(PROJECT))
                .isInstanceOf(LegacyDatasetLayoutException.class)
                .hasMessageContaining("legacy-layout-test")
                .hasMessageContaining("earlier arknet version");
    }

    /** The refusal tells the user what to do, not only what is wrong. */
    @Test
    void theRefusalNamesWhatToDo() {
        insert(PROJECT, "https://w3id.org/arknet/model/requirements");

        assertThatThrownBy(() -> guarded.acquire(PROJECT))
                .hasMessageContaining("keep running the arknet version that wrote it")
                .hasMessageContaining("an arknet version that migrates this layout")
                .hasMessageContaining("copy of the storage directory");
    }

    @Test
    void aDatasetWithOnlyTheProvenanceGraphIsRefusedToo() {
        insert(PROJECT, "https://w3id.org/arknet/model/provenance");

        assertThatThrownBy(() -> guarded.acquire(PROJECT)).isInstanceOf(LegacyDatasetLayoutException.class);
    }

    @Test
    void aRefusedDatasetIsRefusedOnEveryAcquisition() {
        insert(PROJECT, "https://w3id.org/arknet/model/adr");

        assertThatThrownBy(() -> guarded.acquire(PROJECT)).isInstanceOf(LegacyDatasetLayoutException.class);
        assertThatThrownBy(() -> guarded.acquire(PROJECT)).isInstanceOf(LegacyDatasetLayoutException.class);
    }

    @Test
    void aDatasetInTheContentGraphLayoutIsHandedOut() {
        insert(PROJECT, ContentGraph.GRAPH_IRI);

        try (DatasetHandle handle = guarded.acquire(PROJECT)) {
            assertThat(handle.sparqlQuery().ask("ASK { GRAPH <" + ContentGraph.GRAPH_IRI + "> { ?s ?p ?o } }"))
                    .isTrue();
        }
    }

    @Test
    void theSystemDatasetIsExemptEvenWithAnOldProvenanceGraph() {
        insert(SYSTEM, "https://w3id.org/arknet/model/provenance");

        try (DatasetHandle handle = guarded.acquire(SYSTEM)) {
            assertThat(handle).isNotNull();
        }
    }

    private void insert(DatasetId dataset, String graph) {
        try (DatasetHandle handle = raw.acquire(dataset)) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <" + graph + "> { <urn:s> <urn:p> \"o\" } }");
                return null;
            });
        }
    }
}
