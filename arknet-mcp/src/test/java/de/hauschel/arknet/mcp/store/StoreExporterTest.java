// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Unit tests for {@link StoreExporter}: unlike {@link StoreReader}, the backup export must
 * surface every named graph, including the infrastructure ones {@link StoreReader} hides.
 */
class StoreExporterTest {

    private static final ProjectId PROJECT = new ProjectId("store-exporter-test");
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/store-exporter-test-fr-1";
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";

    @TempDir
    Path storageDir;

    private DatasetLifecycle lifecycle;
    private StoreExporter exporter;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        RequirementRepository requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        requirements.create(PROJECT, requirementTitled("Login"));
        exporter = new StoreExporter(lifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(PROJECT.value()));
    }

    private static Requirement requirementTitled(String title) {
        return new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), title,
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of("Login succeeds with valid credentials"));
    }

    @Test
    void exportTrigContainsTheRequirementsGraphBlock() {
        String trig = exporter.exportTrig(PROJECT);

        assertThat(trig).contains("<" + REQUIREMENTS_GRAPH + "> {");
        assertThat(trig).contains("<" + FR_1_IRI + ">");
        assertThat(trig).contains("\"Login\"");
    }

    /**
     * A backup must not hide the provenance graph {@link StoreReader} excludes for report
     * purposes - every guarded write records a PROV-O revision there (ADR-014), and losing it
     * would silently drop change history from the backup.
     */
    @Test
    void exportTrigContainsBothTheRequirementsGraphAndTheProvenanceGraph() {
        String trig = exporter.exportTrig(PROJECT);

        assertThat(trig).contains("<" + REQUIREMENTS_GRAPH + "> {");
        assertThat(trig).contains("<" + ArkprovVocabulary.PROVENANCE_GRAPH + "> {");
    }

    @Test
    void exportTrigOrdersGraphBlocksAlphabeticallyForADeterministicDiffStableOutput() {
        String trig = exporter.exportTrig(PROJECT);

        int provenanceIndex = trig.indexOf("<" + ArkprovVocabulary.PROVENANCE_GRAPH + "> {");
        int requirementsIndex = trig.indexOf("<" + REQUIREMENTS_GRAPH + "> {");
        assertThat(provenanceIndex).isNotEqualTo(-1);
        assertThat(requirementsIndex).isNotEqualTo(-1);
        assertThat(provenanceIndex).isLessThan(requirementsIndex);
    }

    /**
     * Regression test: {@code arkreq:usesTerm} carries no {@code sh:nodeKind} constraint, so its
     * target is RDF-legally allowed to be a blank node (see
     * {@code KognioRdfRequirementRepository#replaceTriples}), and a store-first edge can and does
     * point at one. Seeded directly through the raw dataset API - the requirement domain path
     * never produces one itself - to prove {@code exportTrig} serialises a blank-node subject's
     * own triples instead of crashing on the first non-IRI subject it meets.
     */
    @Test
    void exportTrigDoesNotCrashOnABlankNodeSubjectAndSerialisesItsOwnTriples() {
        String testGraph = "https://w3id.org/arknet/id/store-exporter-test-blank-node-graph";
        String predicate = "https://w3id.org/arknet/requirements#usesTerm";
        seedBlankNodeSubjectTriple(testGraph, predicate);

        String trig = exporter.exportTrig(PROJECT);

        assertThat(trig).contains("<" + testGraph + "> {");
        assertThat(trig).containsPattern(
                Pattern.compile("_:\\S+ <" + Pattern.quote(predicate) + "> \"blank node target\" \\."));
    }

    /** Writes a single blank-node-subject triple straight into its own named graph. */
    private void seedBlankNodeSubjectTriple(String testGraph, String predicate) {
        RDF rdf = new SimpleRdf();
        Graph graph = rdf.createGraph();
        BlankNode subject = rdf.createBlankNode();
        graph.add(subject, rdf.createIRI(predicate), rdf.createLiteral("blank node target"));
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.add(rdf.createIRI(testGraph), graph);
                return null;
            });
        }
    }
}
