// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.mcp.check.StepAcceptanceCheck.Finding;
import de.hauschel.arknet.mcp.check.StepAcceptanceCheck.Kind;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * Unit tests for the rule behind {@code store_check STEP_ACCEPTANCE} (kogn-io/arknet#622): which
 * main-flow use-case step no acceptance criterion stands behind, along the two-hop backwards path
 * {@code Step -> stepRealises -> Requirement -> acceptanceCriterion}.
 *
 * <p>Every fixture here is a bare triple list, never a live store - same discipline as
 * {@link RoleTermDuplicateCheckTest}: the rule has nothing to do with RDF4J or any bounded
 * context.</p>
 */
class StepAcceptanceCheckTest {

    private static final String ID = "https://w3id.org/arknet/id/";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    /** The chain the whole check exists for: complete, so nothing is reported. */
    @Test
    void reportsNothingWhenTheStepReachesACriterionThroughItsRequirement() {
        StoreSnapshot snapshot = StoreSnapshot.of(triples(
                useCase("uc1", "UC-1", List.of("step1")),
                step("step1", 1, List.of("fr1")),
                requirement("fr1", "FR-1", List.of("ac1")),
                List.of(literal(ID + "ac1", ARKREQ + "criterionText", "Done when ...", "en"))));

        assertThat(StepAcceptanceCheck.run(snapshot)).isEmpty();
    }

    /** First case: a missing edge - the step hangs off no requirement at all. */
    @Test
    void reportsAStepWithoutARealisedRequirementAsAMissingEdge() {
        StoreSnapshot snapshot = StoreSnapshot.of(triples(
                useCase("uc1", "UC-1", List.of("step1")),
                step("step1", 2, List.of())));

        assertThat(StepAcceptanceCheck.run(snapshot))
                .extracting(Finding::kind, Finding::useCaseCode, Finding::position, Finding::requirementCodes)
                .containsExactly(tuple(Kind.NO_REQUIREMENT, "UC-1", 2, List.of()));
    }

    /** Second case: the chain exists but ends before the proof - an incomplete requirement. */
    @Test
    void reportsAStepWhoseRequirementCarriesNoCriterionAsAnIncompleteRequirement() {
        StoreSnapshot snapshot = StoreSnapshot.of(triples(
                useCase("uc1", "UC-1", List.of("step1")),
                step("step1", 1, List.of("fr1")),
                requirement("fr1", "FR-1", List.of())));

        assertThat(StepAcceptanceCheck.run(snapshot))
                .extracting(Finding::kind, Finding::useCaseCode, Finding::position, Finding::requirementCodes)
                .containsExactly(tuple(Kind.REQUIREMENT_WITHOUT_CRITERION, "UC-1", 1, List.of("FR-1")));
    }

    /**
     * The two cases are not the same defect and must stay distinguishable - the finding kind is
     * what the renderer splits its two tables on.
     */
    @Test
    void keepsTheTwoCasesApart() {
        StoreSnapshot snapshot = StoreSnapshot.of(triples(
                useCase("uc1", "UC-1", List.of("step1", "step2")),
                step("step1", 1, List.of()),
                step("step2", 2, List.of("fr1")),
                requirement("fr1", "FR-1", List.of())));

        assertThat(StepAcceptanceCheck.run(snapshot))
                .extracting(Finding::kind, Finding::position)
                .containsExactly(tuple(Kind.NO_REQUIREMENT, 1), tuple(Kind.REQUIREMENT_WITHOUT_CRITERION, 2));
    }

    /**
     * The question is whether the step has a proof behind it. One criterion on one of the realised
     * requirements answers it - a step already covered is not a gap, however many further
     * requirements it also realises.
     */
    @Test
    void treatsAStepAsCoveredWhenAnyOneRealisedRequirementCarriesACriterion() {
        StoreSnapshot snapshot = StoreSnapshot.of(triples(
                useCase("uc1", "UC-1", List.of("step1")),
                step("step1", 1, List.of("fr1", "fr2")),
                requirement("fr1", "FR-1", List.of()),
                requirement("fr2", "FR-2", List.of("ac1"))));

        assertThat(StepAcceptanceCheck.run(snapshot)).isEmpty();
    }

    @Test
    void namesEveryUncoveredRequirementOfAStepRealisingSeveral() {
        StoreSnapshot snapshot = StoreSnapshot.of(triples(
                useCase("uc1", "UC-1", List.of("step1")),
                step("step1", 1, List.of("fr1", "fr2")),
                requirement("fr1", "FR-1", List.of()),
                requirement("fr2", "FR-2", List.of())));

        assertThat(StepAcceptanceCheck.run(snapshot))
                .singleElement()
                .extracting(Finding::requirementCodes)
                .isEqualTo(List.of("FR-1", "FR-2"));
    }

    /**
     * The scope limit of kogn-io/arknet#317: an extension step carries no {@code realises} edge at
     * tool level although the ontology allows one, so reporting it would be noise about a known
     * model gap rather than a finding.
     */
    @Test
    void ignoresAnExtensionStepRatherThanReportingAKnownModelGapAsAFinding() {
        List<Triple> triples = new ArrayList<>(triples(
                useCase("uc1", "UC-1", List.of()),
                step("ext1", 1, List.of())));
        triples.add(iri(ID + "uc1", ARKREQ + "extensionStep", ID + "ext1"));

        assertThat(StepAcceptanceCheck.run(StoreSnapshot.of(triples))).isEmpty();
    }

    @Test
    void skipsAUseCaseWithoutABusinessCodeRatherThanGuessingOne() {
        List<Triple> triples = new ArrayList<>(List.of(
                iri(ID + "uc1", RDF_TYPE, ARKREQ + "UseCase"),
                iri(ID + "uc1", ARKREQ + "mainStep", ID + "step1")));
        triples.addAll(step("step1", 1, List.of()));

        assertThat(StepAcceptanceCheck.run(StoreSnapshot.of(triples))).isEmpty();
    }

    /** A step without a position is still reported - a missing number does not make it covered. */
    @Test
    void reportsAStepWithoutAPositionWithNoPositionRatherThanDroppingIt() {
        List<Triple> triples = new ArrayList<>(triples(useCase("uc1", "UC-1", List.of("step1"))));
        triples.add(iri(ID + "step1", RDF_TYPE, ARKREQ + "Step"));

        assertThat(StepAcceptanceCheck.run(StoreSnapshot.of(triples)))
                .extracting(Finding::kind, Finding::position)
                .containsExactly(tuple(Kind.NO_REQUIREMENT, null));
    }

    /**
     * A {@code mainStep} edge pointing at a subject with no statements of its own carries no
     * {@code stepRealises} either - the first case, not a silently skipped step.
     */
    @Test
    void reportsAStepThatCarriesNoStatementsOfItsOwn() {
        assertThat(StepAcceptanceCheck.run(StoreSnapshot.of(triples(useCase("uc1", "UC-1", List.of("gone"))))))
                .extracting(Finding::kind, Finding::useCaseCode)
                .containsExactly(tuple(Kind.NO_REQUIREMENT, "UC-1"));
    }

    @Test
    void ordersFindingsByUseCaseCodeThenStepPositionSoTwoRunsOverAnUnchangedStoreAgree() {
        StoreSnapshot snapshot = StoreSnapshot.of(triples(
                useCase("uc2", "UC-2", List.of("step3")),
                step("step3", 1, List.of()),
                useCase("uc1", "UC-1", List.of("step2", "step1")),
                step("step2", 2, List.of()),
                step("step1", 1, List.of())));

        assertThat(StepAcceptanceCheck.run(snapshot))
                .extracting(Finding::useCaseCode, Finding::position)
                .containsExactly(tuple("UC-1", 1), tuple("UC-1", 2), tuple("UC-2", 1));
    }

    @Test
    void reportsNothingForAStoreWithoutUseCases() {
        assertThat(StepAcceptanceCheck.run(StoreSnapshot.of(List.of(
                literal(ID + "fr1", DCTERMS + "identifier", "FR-1", null))))).isEmpty();
    }

    @SafeVarargs
    private static List<Triple> triples(final List<Triple>... parts) {
        List<Triple> all = new ArrayList<>();
        for (List<Triple> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private static List<Triple> useCase(final String name, final String code, final List<String> steps) {
        List<Triple> triples = new ArrayList<>(List.of(
                iri(ID + name, RDF_TYPE, ARKREQ + "UseCase"),
                literal(ID + name, DCTERMS + "identifier", code, null)));
        for (String step : steps) {
            triples.add(iri(ID + name, ARKREQ + "mainStep", ID + step));
        }
        return triples;
    }

    private static List<Triple> step(final String name, final int position, final List<String> requirements) {
        List<Triple> triples = new ArrayList<>(List.of(
                iri(ID + name, RDF_TYPE, ARKREQ + "Step"),
                literal(ID + name, ARKREQ + "position", String.valueOf(position), null)));
        for (String requirement : requirements) {
            triples.add(iri(ID + name, ARKREQ + "stepRealises", ID + requirement));
        }
        return triples;
    }

    private static List<Triple> requirement(final String name, final String code, final List<String> criteria) {
        List<Triple> triples = new ArrayList<>(List.of(
                iri(ID + name, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                literal(ID + name, DCTERMS + "identifier", code, null)));
        for (String criterion : criteria) {
            triples.add(iri(ID + name, ARKREQ + "acceptanceCriterion", ID + criterion));
        }
        return triples;
    }

    private static Triple iri(final String subject, final String predicate, final String object) {
        return new Triple(subject, predicate, new RdfNode.Resource(object));
    }

    private static Triple literal(final String subject, final String predicate, final String value,
            final String languageTag) {
        return new Triple(subject, predicate, new RdfNode.Literal(value, null, languageTag));
    }
}
