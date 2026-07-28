// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * Unit tests for {@link TraceabilityRenderer}. Builds snapshots from hand-made triples (same
 * pattern as {@code DigestRendererTest}) so the rendering is exercised without any store,
 * including the one case that is hard to provoke through the real repositories: a step-typed
 * intermediate node that must be traversed but never reported.
 */
class TraceabilityRendererTest {

    private static final String ID = "https://w3id.org/arknet/id/";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String TITLE = "http://purl.org/dc/terms/title";
    private static final String PREF_LABEL = SKOS + "prefLabel";
    private static final String IDENTIFIER = "http://purl.org/dc/terms/identifier";
    private static final String USES_TERM = ARKREQ + "usesTerm";
    private static final String MAIN_STEP = ARKREQ + "mainStep";
    private static final String STEP_REALISES = ARKREQ + "stepRealises";

    private static final String FR_1 = ID + "fr-1";
    private static final String FR_2 = ID + "fr-2";
    private static final String TERM_1 = ID + "term-1";
    private static final String STEP_1 = ID + "step-1";
    private static final String UC_1 = ID + "uc-1";

    private final TraceabilityRenderer renderer = new TraceabilityRenderer(Prefixes.defaults());
    private static final ProjectId WORKSPACE = new ProjectId("noistill");

    @Test
    void traceMatrixReportsUsedTermsAndRealisingUseCasePerRequirement() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot());

        String matrix = renderer.traceMatrix(WORKSPACE, graph);

        assertThat(matrix).contains("# Traceability matrix -- project noistill -- 2 requirement(s)");
        assertThat(matrix).contains("FR-1 [FunctionalRequirement] \"Login\"");
        assertThat(matrix).contains("uses terms  : TERM-1");
        assertThat(matrix).contains("realised by : UC1");
        assertThat(matrix).contains("FR-2 [FunctionalRequirement] \"Logout\"");
        assertThat(matrix).contains("uses terms  : (none)");
        assertThat(matrix).contains("realised by : (none)");
    }

    @Test
    void orphanCheckListsFr2AsUnrealisedRequirement() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot());

        String report = renderer.orphanCheck(WORKSPACE, graph);

        assertThat(report).contains("# Orphan check -- project noistill");
        assertThat(report).contains("## Requirements without a realising use case (1)");
        assertThat(report).contains("FR-2");
        assertThat(report).contains("## Terms never referenced (0)");
        assertThat(report).contains("- none");
    }

    /**
     * The load-bearing assertion: {@code impact_analysis} on TERM-1 must reach FR-1 (direct
     * {@code usesTerm} reference) and UC1 (through the {@code stepRealises} then {@code
     * mainStep} hop) - but never STEP-1 itself, which is an aggregate-internal value object,
     * not a reportable artifact (see {@link TraceabilityGraph#dependents(String)}).
     */
    @Test
    void impactAnalysisCollapsesTheStepHopAndNeverReportsTheStepItself() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot());

        String impact = renderer.impactAnalysis(WORKSPACE, graph, TERM_1);

        assertThat(impact).contains("# Impact analysis -- project noistill -- target: TERM-1");
        assertThat(impact).contains("## Transitively affected (2)");
        assertThat(impact).contains("FR-1").contains("UC1");
        assertThat(impact).doesNotContain(STEP_1);
    }

    @Test
    void impactAnalysisOfAnUnreferencedResourceReportsNone() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot());

        String impact = renderer.impactAnalysis(WORKSPACE, graph, FR_2);

        assertThat(impact).contains("## Transitively affected (0)");
        assertThat(impact).contains("- none");
    }

    private static StoreSnapshot fixtureSnapshot() {
        return StoreSnapshot.of(List.of(
                iri(FR_1, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(FR_1, TITLE, "Login"),
                lit(FR_1, IDENTIFIER, "FR-1"),
                iri(FR_1, USES_TERM, TERM_1),

                iri(FR_2, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(FR_2, TITLE, "Logout"),
                lit(FR_2, IDENTIFIER, "FR-2"),

                iri(TERM_1, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_1, PREF_LABEL, "Anmeldung"),
                lit(TERM_1, IDENTIFIER, "TERM-1"),

                iri(STEP_1, RDF_TYPE, ARKREQ + "Step"),
                iri(STEP_1, STEP_REALISES, FR_1),

                iri(UC_1, RDF_TYPE, ARKREQ + "UseCase"),
                lit(UC_1, TITLE, "Log in"),
                lit(UC_1, IDENTIFIER, "UC1"),
                iri(UC_1, MAIN_STEP, STEP_1)));
    }

    private static Triple iri(String subject, String predicate, String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }

    private static Triple lit(String subject, String predicate, String lexical) {
        return new Triple(subject, predicate,
                new RdfNode.Literal(lexical, "http://www.w3.org/2001/XMLSchema#string", null));
    }
}
