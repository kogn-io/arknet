// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
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
 *
 * <p>Serialisation itself is {@link DatasetHandle#datasetExport()} (kognio-rdf 0.2.2), an
 * RDF4J-Rio-backed writer - these tests exercise {@link StoreExporter}'s own wiring around it
 * (dataset acquisition, which graphs come back), plus one end-to-end regression for the defect
 * that motivated the switch: the previous hand-rolled serialisation fell back to
 * {@code RDFTerm#ntriplesString()} for literal objects, which resolves to
 * {@code Value#toString()} on the RDF4J-backed term implementation this store runs on - a method
 * that does not escape an embedded {@code "}, {@code \} or newline in the lexical form.</p>
 */
class StoreExporterTest {

    private static final ProjectId PROJECT = new ProjectId("store-exporter-test");
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/store-exporter-test-fr-1";
    private static final String FR_2_IRI = "https://w3id.org/arknet/id/store-exporter-test-fr-2";
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";
    private static final String DCTERMS_TITLE = "http://purl.org/dc/terms/title";

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
        return requirementIdentifiedAndTitled(FR_1_IRI, "FR-1", title);
    }

    private static Requirement requirementIdentifiedAndTitled(String iri, String code, String title) {
        return new Requirement(
                new RequirementId(ResourceId.of(iri)), new RequirementCode(code), title,
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of("Login succeeds with valid credentials"));
    }

    private String exportTrig() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.exportTrig(PROJECT, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void exportTrigContainsTheRequirementsGraphBlock() {
        String trig = exportTrig();

        assertThat(trig).contains("<" + REQUIREMENTS_GRAPH + ">");
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
        String trig = exportTrig();

        assertThat(trig).contains("<" + REQUIREMENTS_GRAPH + ">");
        assertThat(trig).contains("<" + ArkprovVocabulary.PROVENANCE_GRAPH + ">");
    }

    /**
     * Regression test for the defect that motivated moving serialisation onto
     * {@link DatasetHandle#datasetExport()}: a literal value carrying an embedded quote and a
     * newline must still round-trip through a real TriG parser instead of breaking the grammar.
     *
     * <p>Asserts on the <em>parsed</em> literal value rather than a raw substring of the TriG
     * text: Rio is free to render such a literal as a long (triple-quoted) string, which keeps
     * the newline literal and only escapes a quote where it would clash with the closing
     * delimiter - a different, equally valid rendering than the short-string-literal escaping an
     * earlier version of this test assumed.</p>
     */
    @Test
    void exportTrigEscapesEmbeddedQuotesAndNewlinesInLiteralValues() throws Exception {
        String awkwardTitle = "Say \"hi\" then\nnew line";
        RequirementRepository requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        requirements.create(PROJECT, requirementIdentifiedAndTitled(FR_2_IRI, "FR-2", awkwardTitle));
        // A distinct IRI/code, not FR_1_IRI: create() rejects a subject that already exists
        // (the "Login" seed from setUp), so the awkward-title requirement is a second one.

        String trig = exportTrig();

        Model model = Rio.parse(new ByteArrayInputStream(trig.getBytes(StandardCharsets.UTF_8)), RDFFormat.TRIG);
        Model titleStatements = model.filter(Values.iri(FR_2_IRI), Values.iri(DCTERMS_TITLE), null);
        assertThat(Models.objectString(titleStatements)).contains(awkwardTitle);
    }

    /**
     * Regression test: {@code arkreq:usesTerm} carries no {@code sh:nodeKind} constraint, so its
     * target is RDF-legally allowed to be a blank node (see
     * {@code KognioRdfRequirementRepository#replaceTriples}), and a store-first edge can and does
     * point at one. Seeded directly through the raw dataset API - the requirement domain path
     * never produces one itself - to prove the export path serialises a blank-node subject's own
     * triples instead of silently dropping them.
     */
    @Test
    void exportTrigDoesNotCrashOnABlankNodeSubjectAndSerialisesItsOwnTriples() {
        String testGraph = "https://w3id.org/arknet/id/store-exporter-test-blank-node-graph";
        String predicate = "https://w3id.org/arknet/requirements#usesTerm";
        seedBlankNodeSubjectTriple(testGraph, predicate);

        String trig = exportTrig();

        assertThat(trig).contains("<" + testGraph + ">");
        assertThat(trig).containsPattern(
                Pattern.compile("_:\\S+\\s+<" + Pattern.quote(predicate) + ">\\s+\"blank node target\"\\s*\\."));
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
