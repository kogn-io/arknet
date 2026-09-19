// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.analysis.domain.LanguageGapCheck.Gap;
import de.hauschel.arknet.analysis.domain.RoleTermDuplicateCheck.Finding;
import de.hauschel.arknet.analysis.domain.StepAcceptanceCheck.Kind;
import de.hauschel.arknet.analysis.domain.StepAcceptanceCheck;
import de.hauschel.arknet.analysis.domain.StoreCheckKind;
import de.hauschel.arknet.persistence.Prefixes;

/** Unit tests for {@code store_check}'s text output. */
class StoreCheckRendererTest {

    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String ID = "https://w3id.org/arknet/id/";

    private final StoreCheckRenderer renderer = new StoreCheckRenderer(Prefixes.defaults());

    /**
     * The distinction the whole issue turns on: with no declared set there is no target state, so
     * "no gaps" would answer a question nobody asked. The section has to say it did not check, and
     * name the way to make it checkable.
     */
    @Test
    void saysItDidNotCheckRatherThanReportingACleanResultWhenNoLanguagesAreMaintained() {
        String rendered = renderer.languageSection(List.of(), List.of());

        assertThat(rendered).contains("not checked").contains("project_update");
        assertThat(rendered).doesNotContain("No field is missing");
    }

    @Test
    void reportsACleanResultOnlyWhenThereIsSomethingToBeCleanAgainst() {
        String rendered = renderer.languageSection(List.of("de", "en"), List.of());

        assertThat(rendered).contains("maintained languages de, en").contains("No field is missing");
    }

    @Test
    void rendersOneRowPerFieldWithTheShortenedPredicateAndASummaryLine() {
        String rendered = renderer.languageSection(List.of("de", "en"), List.of(
                new Gap(ID + "t1", "TERM-1", "Concept", SKOS + "definition", List.of("en")),
                new Gap(ID + "t2", "TERM-2", "Concept", SKOS + "definition", List.of("en"))));

        assertThat(rendered)
                .contains("| Resource | Type | Field | Missing |")
                .contains("| TERM-1 | Concept | skos:definition | en |")
                .contains("2 fields on 2 resources missing a maintained language.");
    }

    /** A resource with no code of its own must still be addressable, so the IRI is shortened, not dropped. */
    @Test
    void fallsBackToTheShortenedIriForAResourceWithoutAHandle() {
        String rendered = renderer.languageSection(List.of("de", "en"),
                List.of(new Gap(ID + "abc", null, null, SKOS + "definition", List.of("en"))));

        assertThat(rendered).contains("| " + ID + "abc | - | skos:definition | en |");
    }

    /** An empty section that does not say what it could not see reads as "reviewed". */
    @Test
    void alwaysNamesItsBlindSpotWhetherItFoundSomethingOrNot() {
        assertThat(renderer.languageSection(List.of("de", "en"), List.of()))
                .contains(StoreCheckRenderer.BLIND_SPOT);
        assertThat(renderer.languageSection(List.of("de", "en"),
                List.of(new Gap(ID + "t1", "TERM-1", "Concept", SKOS + "definition", List.of("en")))))
                .contains(StoreCheckRenderer.BLIND_SPOT);
    }

    @Test
    void headsTheReportWithTheChecksThatActuallyRan() {
        assertThat(renderer.report(List.of(StoreCheckKind.LANGUAGE), List.of("body")))
                .startsWith("store_check: LANGUAGE")
                .endsWith("body");
    }

    @Test
    void saysNoRoleAndTermShareANameWhenTheCheckFoundNothing() {
        assertThat(renderer.roleTermDuplicateSection(List.of()))
                .contains("ROLE_TERM_DUPLICATE")
                .contains("no role and glossary term share a name");
    }

    @Test
    void rendersOneRowPerFindingWithTheRoleTheTermAndTheSharedName() {
        String rendered = renderer.roleTermDuplicateSection(
                List.of(new Finding("ROLE-1", "TERM-6", "Requirements Engineer")));

        assertThat(rendered)
                .contains("ROLE_TERM_DUPLICATE")
                .contains("| Role | Term | Name |")
                .contains("| ROLE-1 | TERM-6 | Requirements Engineer |");
    }

    @Test
    void saysEveryStepReachesACriterionWhenTheStepCheckFoundNothing() {
        assertThat(renderer.stepAcceptanceSection(List.of()))
                .contains("STEP_ACCEPTANCE")
                .contains("every main-flow step reaches an acceptance criterion");
    }

    /**
     * The distinction issue #622 turns on: a missing edge and an incomplete requirement are not
     * the same defect, so they must not be read off one list.
     */
    @Test
    void rendersTheTwoCasesInTwoTablesRatherThanOne() {
        String rendered = renderer.stepAcceptanceSection(List.of(
                new StepAcceptanceCheck.Finding(Kind.NO_REQUIREMENT, "UC-1", 1, List.of()),
                new StepAcceptanceCheck.Finding(Kind.REQUIREMENT_WITHOUT_CRITERION, "UC-1", 2,
                        List.of("FR-3", "FR-4"))));

        assertThat(rendered)
                .contains("2 main-flow steps have no acceptance criterion behind them.")
                .contains("No requirement (arkreq:stepRealises missing):")
                .contains("| Use case | Step |")
                .contains("| UC-1 | 1 |")
                .contains("Requirement without an acceptance criterion:")
                .contains("| Use case | Step | Requirement |")
                .contains("| UC-1 | 2 | FR-3, FR-4 |");
    }

    /** A step with no position of its own is shown as having none, never under an invented number. */
    @Test
    void showsAStepWithoutAPositionAsHavingNone() {
        assertThat(renderer.stepAcceptanceSection(
                List.of(new StepAcceptanceCheck.Finding(Kind.NO_REQUIREMENT, "UC-1", null, List.of()))))
                .contains("| UC-1 | - |");
    }

    @Test
    void alwaysNamesTheStepChecksBlindSpotWhetherItFoundSomethingOrNot() {
        assertThat(renderer.stepAcceptanceSection(List.of()))
                .contains(StoreCheckRenderer.STEP_BLIND_SPOT);
        assertThat(renderer.stepAcceptanceSection(
                List.of(new StepAcceptanceCheck.Finding(Kind.NO_REQUIREMENT, "UC-1", 1, List.of()))))
                .contains(StoreCheckRenderer.STEP_BLIND_SPOT);
    }
}
