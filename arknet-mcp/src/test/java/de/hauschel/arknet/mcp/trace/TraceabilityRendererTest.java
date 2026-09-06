// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.DisplayLocale;
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
    private static final String ARKDDD = "https://w3id.org/arknet/ddd#";
    private static final String ARKPROC = "https://w3id.org/arknet/process#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String TITLE = "http://purl.org/dc/terms/title";
    private static final String DESCRIPTION = "http://purl.org/dc/terms/description";
    private static final String PREF_LABEL = SKOS + "prefLabel";
    private static final String IDENTIFIER = "http://purl.org/dc/terms/identifier";
    private static final String USES_TERM = ARKREQ + "usesTerm";
    private static final String MAIN_STEP = ARKREQ + "mainStep";
    private static final String STEP_REALISES = ARKREQ + "stepRealises";
    private static final String PRIMARY_ROLE = ARKREQ + "primaryRole";
    private static final String SUPPORTING_ROLE = ARKREQ + "supportingRole";
    private static final String USE_CASE_GOAL = ARKREQ + "useCaseGoal";
    private static final String ACCEPTANCE_CRITERION = ARKREQ + "acceptanceCriterion";
    private static final String CRITERION_TEXT = ARKREQ + "criterionText";
    private static final String DOMAIN_VISION = ARKDDD + "domainVision";
    private static final String UBIQUITOUS_LANGUAGE_TERM = ARKDDD + "ubiquitousLanguageTerm";
    private static final String ROLE_TYPE = ARKPROC + "Role";
    private static final String CONSTRAINED_BY = "http://open-services.net/ns/rm#constrainedBy";

    private static final String FR_1 = ID + "fr-1";
    private static final String FR_2 = ID + "fr-2";
    private static final String FR_3 = ID + "fr-3";
    private static final String FR_10 = ID + "fr-10";
    private static final String TERM_1 = ID + "term-1";
    private static final String TERM_8 = ID + "term-8";
    private static final String TERM_9 = ID + "term-9";
    private static final String TERM_10 = ID + "term-10";
    private static final String TERM_A = ID + "term-a";
    private static final String TERM_B = ID + "term-b";
    private static final String CRITERION_10 = ID + "criterion-10";
    private static final String TERM_C = ID + "term-c";
    private static final String STEP_1 = ID + "step-1";
    private static final String UC_1 = ID + "uc-1";
    private static final String UC_A = ID + "uc-a";
    private static final String UC_B = ID + "uc-b";
    private static final String UC_10 = ID + "uc-10";
    private static final String ROLE_A = ID + "role-a";
    private static final String ROLE_B = ID + "role-b";
    private static final String ROLE_C = ID + "role-c";
    private static final String BC_2 = ID + "bc-2";
    private static final String CON_1 = ID + "con-1";
    private static final String CON_2 = ID + "con-2";

    private final TraceabilityRenderer renderer = new TraceabilityRenderer(Prefixes.defaults());
    private static final ProjectId PROJECT = new ProjectId("sample-project");

    @Test
    void traceMatrixReportsUsedTermsAndRealisingUseCasePerRequirement() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot(), DisplayLocale.DEFAULT);

        String matrix = renderer.traceMatrix(PROJECT, graph);

        assertThat(matrix).contains("# Traceability matrix -- project sample-project -- 2 requirement(s)");
        assertThat(matrix).contains("FR-1 [FunctionalRequirement] \"Login\"");
        assertThat(matrix).contains("uses terms  : TERM-1");
        assertThat(matrix).contains("realised by : UC1");
        assertThat(matrix).contains("FR-2 [FunctionalRequirement] \"Logout\"");
        assertThat(matrix).contains("uses terms  : (none)");
        assertThat(matrix).contains("realised by : (none)");
    }

    @Test
    void orphanCheckListsFr2AsUnrealisedRequirement() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot(), DisplayLocale.DEFAULT);

        String report = renderer.orphanCheck(PROJECT, graph);

        assertThat(report).contains("# Orphan check -- project sample-project");
        assertThat(report).contains("## Requirements without a realising use case (1)");
        assertThat(report).contains("FR-2");
        assertThat(report).contains("## Terms never referenced (0)");
        assertThat(report).contains("- none");
        assertThat(report).contains("## Mentioned in text but not linked (0)");
        assertThat(report).contains("## Constraints not attached to any requirement or use case (0)");
    }

    /**
     * CON-2 is a constraint (issue #223) no requirement is bound by via
     * {@code oslc_rm:constrainedBy}; CON-1 is bound by FR-1 and must not appear.
     */
    @Test
    void orphanCheckListsAnUnattachedConstraint() {
        TraceabilityGraph graph = TraceabilityGraph.of(constraintFixtureSnapshot(), DisplayLocale.DEFAULT);

        String report = renderer.orphanCheck(PROJECT, graph);

        assertThat(report).contains("## Constraints not attached to any requirement or use case (1)");
        assertThat(report).contains("CON-2");
        assertThat(report).doesNotContain("CON-1");
    }

    private static StoreSnapshot constraintFixtureSnapshot() {
        return StoreSnapshot.of(List.of(
                iri(FR_1, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(FR_1, TITLE, "Login"),
                lit(FR_1, IDENTIFIER, "FR-1"),
                iri(FR_1, CONSTRAINED_BY, CON_1),

                iri(CON_1, RDF_TYPE, ARKREQ + "TechnicalConstraint"),
                lit(CON_1, TITLE, "JVM only"),
                lit(CON_1, IDENTIFIER, "CON-1"),

                iri(CON_2, RDF_TYPE, ARKREQ + "TechnicalConstraint"),
                lit(CON_2, TITLE, "PostgreSQL only"),
                lit(CON_2, IDENTIFIER, "CON-2")));
    }

    /**
     * FR-3's description names "Kunde" (TERM-9) without a {@code usesTerm} edge, and BC-2's
     * domain vision names "Bestellung" (TERM-8) without a {@code ubiquitousLanguageTerm} edge -
     * exactly the misleading gap this behaviour closes: today's two lists would call TERM-8/TERM-9
     * orphaned even though the text is using them.
     */
    @Test
    void orphanCheckListsMentionsThatNameATermWithoutTheEdgeToBackItUp() {
        TraceabilityGraph graph = TraceabilityGraph.of(unlinkedMentionFixtureSnapshot(), DisplayLocale.DEFAULT);

        String report = renderer.orphanCheck(PROJECT, graph);

        assertThat(report).contains("## Mentioned in text but not linked (2)");
        assertThat(report).contains("FR-3 mentions \"Kunde\" (TERM-9) -- no usesTerm edge");
        assertThat(report).contains("BC-2 mentions \"Bestellung\" (TERM-8) -- no ubiquitousLanguageTerm edge");
    }

    /**
     * A term linked only through a bounded context's ubiquitous language must not show up as
     * "never referenced" - {@code arkddd:ubiquitousLanguageTerm} is as much a reference as
     * {@code arkreq:usesTerm}.
     */
    @Test
    void orphanCheckDoesNotCountATermLinkedOnlyViaTheBoundedContextAsOrphaned() {
        TraceabilityGraph graph = TraceabilityGraph.of(unlinkedMentionFixtureSnapshot(), DisplayLocale.DEFAULT);

        String report = renderer.orphanCheck(PROJECT, graph);

        assertThat(report).doesNotContain("TERM-10");
    }

    private static StoreSnapshot unlinkedMentionFixtureSnapshot() {
        return StoreSnapshot.of(List.of(
                iri(FR_3, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(FR_3, TITLE, "Bestandsdaten"),
                lit(FR_3, IDENTIFIER, "FR-3"),
                lit(FR_3, DESCRIPTION, "Der Kunde sieht seine Bestandsdaten ein."),

                iri(TERM_9, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_9, PREF_LABEL, "Kunde"),
                lit(TERM_9, IDENTIFIER, "TERM-9"),

                iri(TERM_8, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_8, PREF_LABEL, "Bestellung"),
                lit(TERM_8, IDENTIFIER, "TERM-8"),

                // Linked via ubiquitousLanguageTerm but never named in the vision - must stay
                // invisible to the unlinked-mention check and must not be reported as orphaned.
                iri(TERM_10, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_10, PREF_LABEL, "Vertrag"),
                lit(TERM_10, IDENTIFIER, "TERM-10"),

                iri(BC_2, RDF_TYPE, ARKDDD + "BoundedContext"),
                lit(BC_2, IDENTIFIER, "BC-2"),
                lit(BC_2, DOMAIN_VISION, "Wir verwalten die Bestellung."),
                iri(BC_2, UBIQUITOUS_LANGUAGE_TERM, TERM_10)));
    }

    /**
     * The load-bearing assertion: {@code impact_analysis} on TERM-1 must reach FR-1 (direct
     * {@code usesTerm} reference) and UC1 (through the {@code stepRealises} then {@code
     * mainStep} hop) - but never STEP-1 itself, which is an aggregate-internal value object,
     * not a reportable artifact (see {@link TraceabilityGraph#dependents(String)}).
     */
    @Test
    void impactAnalysisCollapsesTheStepHopAndNeverReportsTheStepItself() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot(), DisplayLocale.DEFAULT);

        String impact = renderer.impactAnalysis(PROJECT, graph, TERM_1);

        assertThat(impact).contains("# Impact analysis -- project sample-project -- target: TERM-1");
        assertThat(impact).contains("## Transitively affected (2)");
        assertThat(impact).contains("FR-1").contains("UC1");
        assertThat(impact).doesNotContain(STEP_1);
    }

    @Test
    void impactAnalysisOfAnUnreferencedResourceReportsNone() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot(), DisplayLocale.DEFAULT);

        String impact = renderer.impactAnalysis(PROJECT, graph, FR_2);

        assertThat(impact).contains("## Transitively affected (0)");
        assertThat(impact).contains("- none");
    }

    /**
     * FR_2 carries no statements of its own kind here - it exists in the graph, just with no
     * incoming edge, which is what {@link #impactAnalysisOfAnUnreferencedResourceReportsNone}
     * covers. This is the different, previously unguarded case: a target IRI the graph has never
     * seen at all must render the same not-found notice {@code resource_get} uses instead of a
     * misleadingly complete "Transitively affected (0)" (issue #135).
     */
    @Test
    void impactAnalysisOfAnUnknownIriReportsNotFoundInsteadOfZeroAffected() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot(), DisplayLocale.DEFAULT);
        String unknownIri = ID + "fr-999";

        String impact = renderer.impactAnalysis(PROJECT, graph, unknownIri);

        assertThat(impact).isEqualTo("Resource not found (no statements): " + unknownIri + "\n<" + unknownIri + ">");
        assertThat(impact).doesNotContain("Transitively affected");
    }

    @Test
    void roleUseCaseMatrixReportsBothDirectionsOfRoleInvolvement() {
        TraceabilityGraph graph = TraceabilityGraph.of(roleUseCaseFixtureSnapshot(), DisplayLocale.DEFAULT);

        String matrix = renderer.roleUseCaseMatrix(PROJECT, graph);

        assertThat(matrix).contains("# Role/use-case matrix -- project sample-project");
        assertThat(matrix).contains("## Roles (2)");
        assertThat(matrix).contains("## Use cases (2)");
        String rolesSection = matrix.substring(matrix.indexOf("## Roles"), matrix.indexOf("## Use cases"));
        assertThat(rolesSection).contains("ROLE-A").contains("use cases : UCA, UCB");
        assertThat(rolesSection).contains("ROLE-B").contains("use cases : UCA");
        String useCasesSection = matrix.substring(matrix.indexOf("## Use cases"));
        assertThat(useCasesSection).contains("UCA").contains("roles     : ROLE-A, ROLE-B");
        assertThat(useCasesSection).contains("UCB").contains("roles     : ROLE-A");
    }

    /**
     * ROLE_C carries no {@code primaryRole}/{@code supportingRole} edge from any use case -
     * the exact gap issue #147 reports: the tool's own description promises "for every role",
     * but the "Roles" section used to be built exclusively from
     * {@link TraceabilityGraph#rolesOf(String)}, so a role no use case references yet never
     * appeared and the section's count silently under-reported. It must now show up, with
     * "(none)" for its use cases - the strongest signal that a use case is missing or that this
     * role belongs to a different bounded context.
     */
    @Test
    void roleUseCaseMatrixIncludesARoleNoUseCaseReferencesYet() {
        TraceabilityGraph graph = TraceabilityGraph.of(roleWithoutUseCaseFixtureSnapshot(), DisplayLocale.DEFAULT);

        String matrix = renderer.roleUseCaseMatrix(PROJECT, graph);

        assertThat(matrix).contains("## Roles (2)");
        String rolesSection = matrix.substring(matrix.indexOf("## Roles"), matrix.indexOf("## Use cases"));
        assertThat(rolesSection).contains("ROLE-A").contains("use cases : UCA");
        assertThat(rolesSection).contains("ROLE-C").contains("use cases : (none)");
    }

    @Test
    void roleUseCaseMatrixReportsNoneForAProjectWithoutUseCases() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot(), DisplayLocale.DEFAULT);

        String matrix = renderer.roleUseCaseMatrix(PROJECT, graph);

        assertThat(matrix).contains("## Roles (0)").contains("## Use cases (1)");
    }

    @Test
    void termCooccurrenceFindsTermsNamedTogetherInRequirementAndUseCaseText() {
        TraceabilityGraph graph = TraceabilityGraph.of(termCooccurrenceFixtureSnapshot(), DisplayLocale.DEFAULT);

        String report = renderer.termCooccurrence(PROJECT, graph);

        assertThat(report).contains("# Term co-occurrence -- project sample-project");
        assertThat(report).contains("## Term pairs named together in the same text (1)");
        assertThat(report).contains("TERM-A").contains("TERM-B").contains("2 text(s)");
        assertThat(report).contains("FR-10").contains("UC10");
        assertThat(report).doesNotContain("TERM-C");
    }

    /**
     * Regression test for issue #298: two terms sharing the exact same {@code skos:prefLabel}
     * (a homonym) must break their matching tie the same way {@code orphan_check}'s {@link
     * TraceabilityGraph#unlinkedMentions()} does - by business code ({@code
     * dcterms:identifier}), not by term IRI. The fixture deliberately picks IRIs and codes whose
     * sort orders disagree (the IRI-smaller term carries the code-larger, IRI-larger term carries
     * the code-smaller): before the fix, {@code termCooccurrences} sorted by IRI and would have
     * matched the mention to {@code TERM-HOMONYM-Z}; after the fix it must match the same
     * code-smallest term ({@code TERM-HOMONYM-A}) {@code unlinkedMentions} would pick for the
     * identical ambiguous mention.
     */
    @Test
    void termCooccurrenceBreaksAHomonymTieByBusinessCodeNotByTermIri() {
        String homonymIriSmaller = ID + "aaa-homonym";
        String homonymIriLarger = ID + "zzz-homonym";
        TraceabilityGraph graph = TraceabilityGraph.of(StoreSnapshot.of(List.of(
                // IRI-smaller, but the code-*larger* of the two homonyms.
                iri(homonymIriSmaller, RDF_TYPE, SKOS + "Concept"),
                lit(homonymIriSmaller, PREF_LABEL, "Order"),
                lit(homonymIriSmaller, IDENTIFIER, "TERM-HOMONYM-Z"),

                // IRI-larger, but the code-*smaller* of the two homonyms - must win the tie.
                iri(homonymIriLarger, RDF_TYPE, SKOS + "Concept"),
                lit(homonymIriLarger, PREF_LABEL, "Order"),
                lit(homonymIriLarger, IDENTIFIER, "TERM-HOMONYM-A"),

                iri(TERM_C, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_C, PREF_LABEL, "Kunde"),
                lit(TERM_C, IDENTIFIER, "TERM-C"),

                iri(FR_10, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(FR_10, TITLE, "Bestellung"),
                lit(FR_10, IDENTIFIER, "FR-10"),
                lit(FR_10, DESCRIPTION, "Der Kunde platziert eine Order."))), DisplayLocale.DEFAULT);

        String report = renderer.termCooccurrence(PROJECT, graph);

        assertThat(report).contains("## Term pairs named together in the same text (1)");
        assertThat(report).contains("TERM-C").contains("TERM-HOMONYM-A");
        assertThat(report).doesNotContain("TERM-HOMONYM-Z");
    }

    @Test
    void termCooccurrenceReportsNoneWhenNoTwoTermsShareAText() {
        TraceabilityGraph graph = TraceabilityGraph.of(fixtureSnapshot(), DisplayLocale.DEFAULT);

        String report = renderer.termCooccurrence(PROJECT, graph);

        assertThat(report).contains("## Term pairs named together in the same text (0)");
        assertThat(report).contains("- none");
    }

    @Test
    void termCooccurrenceFindsTermsAcrossDifferentProseFieldsOfTheSameRequirement() {
        TraceabilityGraph graph = TraceabilityGraph.of(StoreSnapshot.of(List.of(
                iri(TERM_A, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_A, PREF_LABEL, "Kunde"),
                lit(TERM_A, IDENTIFIER, "TERM-A"),

                iri(TERM_B, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_B, PREF_LABEL, "Bestellung"),
                lit(TERM_B, IDENTIFIER, "TERM-B"),

                iri(FR_10, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(FR_10, TITLE, "Bestandsdaten"),
                lit(FR_10, IDENTIFIER, "FR-10"),
                lit(FR_10, DESCRIPTION, "Der Kunde meldet sich an."),
                iri(FR_10, ACCEPTANCE_CRITERION, CRITERION_10),
                iri(CRITERION_10, RDF_TYPE, ARKREQ + "AcceptanceCriterion"),
                lit(CRITERION_10, CRITERION_TEXT, "Die Bestellung wird angezeigt."))), DisplayLocale.DEFAULT);

        String report = renderer.termCooccurrence(PROJECT, graph);

        assertThat(report).contains("## Term pairs named together in the same text (1)");
        assertThat(report).contains("TERM-A").contains("TERM-B").contains("1 text(s)");
    }

    private static StoreSnapshot roleUseCaseFixtureSnapshot() {
        return StoreSnapshot.of(List.of(
                iri(ROLE_A, RDF_TYPE, ROLE_TYPE),
                lit(ROLE_A, PREF_LABEL, "Customer"),
                lit(ROLE_A, IDENTIFIER, "ROLE-A"),

                iri(ROLE_B, RDF_TYPE, ROLE_TYPE),
                lit(ROLE_B, PREF_LABEL, "Support agent"),
                lit(ROLE_B, IDENTIFIER, "ROLE-B"),

                iri(UC_A, RDF_TYPE, ARKREQ + "UseCase"),
                lit(UC_A, TITLE, "Place order"),
                lit(UC_A, IDENTIFIER, "UCA"),
                iri(UC_A, PRIMARY_ROLE, ROLE_A),
                iri(UC_A, SUPPORTING_ROLE, ROLE_B),

                iri(UC_B, RDF_TYPE, ARKREQ + "UseCase"),
                lit(UC_B, TITLE, "Cancel order"),
                lit(UC_B, IDENTIFIER, "UCB"),
                iri(UC_B, PRIMARY_ROLE, ROLE_A)));
    }

    private static StoreSnapshot roleWithoutUseCaseFixtureSnapshot() {
        return StoreSnapshot.of(List.of(
                iri(ROLE_A, RDF_TYPE, ROLE_TYPE),
                lit(ROLE_A, PREF_LABEL, "Customer"),
                lit(ROLE_A, IDENTIFIER, "ROLE-A"),

                // Never referenced by any use case's primaryRole/supportingRole edge.
                iri(ROLE_C, RDF_TYPE, ROLE_TYPE),
                lit(ROLE_C, PREF_LABEL, "Auditor"),
                lit(ROLE_C, IDENTIFIER, "ROLE-C"),

                iri(UC_A, RDF_TYPE, ARKREQ + "UseCase"),
                lit(UC_A, TITLE, "Place order"),
                lit(UC_A, IDENTIFIER, "UCA"),
                iri(UC_A, PRIMARY_ROLE, ROLE_A)));
    }

    private static StoreSnapshot termCooccurrenceFixtureSnapshot() {
        return StoreSnapshot.of(List.of(
                iri(TERM_A, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_A, PREF_LABEL, "Kunde"),
                lit(TERM_A, IDENTIFIER, "TERM-A"),

                iri(TERM_B, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_B, PREF_LABEL, "Bestellung"),
                lit(TERM_B, IDENTIFIER, "TERM-B"),

                iri(TERM_C, RDF_TYPE, SKOS + "Concept"),
                lit(TERM_C, PREF_LABEL, "Vertrag"),
                lit(TERM_C, IDENTIFIER, "TERM-C"),

                iri(FR_10, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(FR_10, TITLE, "Bestandsdaten"),
                lit(FR_10, IDENTIFIER, "FR-10"),
                lit(FR_10, DESCRIPTION, "Der Kunde sieht seine Bestellung ein."),

                iri(UC_10, RDF_TYPE, ARKREQ + "UseCase"),
                lit(UC_10, TITLE, "View order"),
                lit(UC_10, IDENTIFIER, "UC10"),
                lit(UC_10, USE_CASE_GOAL, "Kunde bestaetigt die Bestellung")));
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
