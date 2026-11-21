// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.analysis.domain.LanguageGapCheck.Gap;
import de.hauschel.arknet.analysis.domain.OrphanCheck;
import de.hauschel.arknet.analysis.domain.OrphanCheck.MentionFinding;
import de.hauschel.arknet.analysis.domain.OrphanCheck.ResourceFinding;
import de.hauschel.arknet.analysis.domain.OrphanCheck.WeakMention;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph.WeakReason;
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
        assertThat(rendered).contains(StoreCheckRenderer.LANGUAGE_BLIND_SPOT);
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

    /** Every rule's output names what it checks and what it does not, from the one source in the enum. */
    @Test
    void everySectionNamesScopeAndBlindSpotOfItsRule() {
        for (final StoreCheckKind kind : StoreCheckKind.values()) {
            assertThat(kind.scope()).isNotBlank();
            assertThat(kind.notSeen()).isNotBlank();
        }
        assertThat(renderer.roleTermDuplicateSection(List.of()))
                .contains("Checked here: " + StoreCheckKind.ROLE_TERM_DUPLICATE.scope())
                .contains("Not seen here: " + StoreCheckKind.ROLE_TERM_DUPLICATE.notSeen());
        assertThat(renderer.roleTermDuplicateSection(List.of(new Finding("ROLE-1", "TERM-1", "Reviewer"))))
                .contains(StoreCheckRenderer.ROLE_TERM_BLIND_SPOT);
        assertThat(renderer.stepAcceptanceSection(List.of()))
                .contains("Checked here: " + StoreCheckKind.STEP_ACCEPTANCE.scope());
        assertThat(renderer.orphanSection(new OrphanCheck.Result(List.of(), List.of(), List.of(), List.of(), List.of())))
                .contains("Checked here: " + StoreCheckKind.ORPHAN.scope());
    }

    /** An empty section that does not say what it could not see reads as "reviewed". */
    @Test
    void alwaysNamesItsBlindSpotWhetherItFoundSomethingOrNot() {
        assertThat(renderer.languageSection(List.of("de", "en"), List.of()))
                .contains(StoreCheckRenderer.LANGUAGE_BLIND_SPOT);
        assertThat(renderer.languageSection(List.of("de", "en"),
                List.of(new Gap(ID + "t1", "TERM-1", "Concept", SKOS + "definition", List.of("en")))))
                .contains(StoreCheckRenderer.LANGUAGE_BLIND_SPOT);
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

    /** kogn-io/arknet#473: an empty ORPHAN result reports cleanly, with no table at all. */
    @Test
    void saysNoOrphansFoundWhenAllFourListsAreEmpty() {
        String rendered = renderer.orphanSection(
                new OrphanCheck.Result(List.of(), List.of(), List.of(), List.of(), List.of()));

        assertThat(rendered)
                .contains("ORPHAN")
                .contains("no orphaned requirements, unreferenced terms, unlinked mentions or unattached "
                        + "constraints found.")
                .doesNotContain("|");
    }

    /**
     * Each of the four lists gets its own table, shown only when non-empty - the same discipline
     * {@link #rendersTheTwoCasesInTwoTablesRatherThanOne} follows for STEP_ACCEPTANCE.
     */
    @Test
    void rendersOnlyTheNonEmptyListsAsTheirOwnTable() {
        String rendered = renderer.orphanSection(new OrphanCheck.Result(
                List.of(new ResourceFinding(ID + "fr-2", "FR-2", "FunctionalRequirement", "Logout")),
                List.of(),
                List.of(new MentionFinding(ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                        List.of("description", "stepText"))),
                List.of(), List.of()));

        assertThat(rendered)
                .contains("1 requirement without a satisfying or realising use case")
                .contains("0 terms never referenced")
                .contains("1 unlinked mention")
                .contains("0 constraints not attached")
                .contains("Requirements no use case satisfies or realises:")
                .contains("| Resource | Type | Label |")
                .contains("| FR-2 | FunctionalRequirement | Logout |")
                .contains("Mentioned in text but not linked:")
                .contains(StoreCheckRenderer.MISSING_EDGE_LEGEND)
                .contains("\n- TERM-9 Kunde (1): description+stepText: FR-3")
                .doesNotContain("| Source |")
                .doesNotContain("Terms never referenced:")
                .doesNotContain("Constraints not attached to any requirement or use case:");
    }

    /** A resource with no business code of its own is shown by its shortened IRI, not dropped. */
    @Test
    void fallsBackToTheShortenedIriForAnOrphanWithoutAHandle() {
        String rendered = renderer.orphanSection(new OrphanCheck.Result(
                List.of(), List.of(new ResourceFinding(ID + "term-9", null, "Concept", null)),
                List.of(), List.of(), List.of()));

        assertThat(rendered).contains("| " + ID + "term-9 | Concept | - |");
    }

    @Test
    void alwaysNamesTheOrphanChecksBlindSpotWhetherItFoundSomethingOrNot() {
        assertThat(renderer.orphanSection(new OrphanCheck.Result(List.of(), List.of(), List.of(), List.of(), List.of())))
                .contains(StoreCheckRenderer.ORPHAN_BLIND_SPOT);
        assertThat(renderer.orphanSection(new OrphanCheck.Result(
                List.of(new ResourceFinding(ID + "fr-2", "FR-2", "FunctionalRequirement", "Logout")),
                List.of(), List.of(), List.of(), List.of())))
                .contains(StoreCheckRenderer.ORPHAN_BLIND_SPOT);
    }

    /** kogn-io/arknet#697: hints get their own block and table, and the summary does not count them. */
    @Test
    void rendersWeakMentionsAsSeparateHintBlockAndKeepsThemOutOfTheCount() {
        String rendered = renderer.orphanSection(new OrphanCheck.Result(
                List.of(new ResourceFinding(ID + "fr-2", "FR-2", "FunctionalRequirement", "Logout")),
                List.of(), List.of(), List.of(),
                List.of(
                        new WeakMention(ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                                List.of("criterionText"), WeakReason.ONLY_IN_ACCEPTANCE_CRITERION, List.of()),
                        new WeakMention(ID + "fr-4", "FR-4", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                                List.of("description"), WeakReason.COVERED_BY_REFERENCE, List.of("FR-5")))));

        assertThat(rendered)
                .contains("0 unlinked mentions")
                .contains("2 weaker mention hints not counted")
                .contains("Mentioned only in acceptance criteria or next to a covering reference "
                        + "(hints, not counted):")
                .contains(StoreCheckRenderer.HINT_LEGEND)
                .contains("\n- TERM-9 Kunde (2): criterionText: FR-3; description (covered by FR-5): FR-4")
                .doesNotContain("| Source |")
                .doesNotContain("Mentioned in text but not linked:");
    }

    /** Hints alone never turn an otherwise clean section into a finding; they are still stated. */
    @Test
    void staysAllClearButStatesTheHintsWhenOnlyWeakMentionsExist() {
        String rendered = renderer.orphanSection(new OrphanCheck.Result(
                List.of(), List.of(), List.of(), List.of(),
                List.of(new WeakMention(ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                        List.of("criterionText"), WeakReason.ONLY_IN_ACCEPTANCE_CRITERION, List.of()))));

        assertThat(rendered)
                .contains("no orphaned requirements, unreferenced terms, unlinked mentions or unattached "
                        + "constraints found")
                .contains("1 weaker mention hint not counted")
                .contains("\n- TERM-9 Kunde (1): criterionText: FR-3");
    }

    /**
     * kogn-io/arknet#689: one line per term, not per source and term - the sources grouped by the
     * field set they name the term in, the busiest term first and ties by term code.
     */
    @Test
    void groupsMentionsByTermWithTheBusiestTermFirstAndTiesByCode() {
        String rendered = renderer.orphanSection(new OrphanCheck.Result(
                List.of(), List.of(),
                List.of(
                        mention("FR-1", "TERM-7", "Mitglied", List.of("description")),
                        mention("FR-1", "TERM-2", "Kasse", List.of("description", "criterionText")),
                        mention("FR-2", "TERM-7", "Mitglied", List.of("title")),
                        mention("UC3", "TERM-7", "Mitglied", List.of("stepText")),
                        mention("FR-4", "TERM-1", "Beitrag", List.of("rationale")),
                        mention("FR-5", "TERM-7", "Mitglied", List.of("description"))),
                List.of(), List.of()));

        assertThat(rendered)
                .contains("6 unlinked mentions")
                .contains("\n- TERM-7 Mitglied (4): description: FR-1, FR-5; title: FR-2; stepText: UC3"
                        + "\n- TERM-1 Beitrag (1): rationale: FR-4"
                        + "\n- TERM-2 Kasse (1): description+criterionText: FR-1");
    }

    /** The legend cannot read a type off a shortened IRI, so such a source keeps its missing edge. */
    @Test
    void keepsTheMissingEdgeForASourceWithoutABusinessCode() {
        String rendered = renderer.orphanSection(new OrphanCheck.Result(
                List.of(), List.of(),
                List.of(new MentionFinding(ID + "bc-x", null, ID + "term-9", "TERM-9", "Kunde",
                        "ubiquitousLanguageTerm", List.of("domainVision"))),
                List.of(), List.of()));

        assertThat(rendered).contains("- TERM-9 Kunde (1): domainVision: " + ID + "bc-x (missing ubiquitousLanguageTerm)");
    }

    /**
     * Hints group by term exactly like the counted mentions; sources covered by the same references
     * share one group, sources covered by different ones do not.
     */
    @Test
    void groupsHintsByTermAndKeepsTheCoveringReferencesPerSource() {
        String rendered = renderer.orphanSection(new OrphanCheck.Result(
                List.of(), List.of(), List.of(), List.of(),
                List.of(
                        hint("UC2", "TERM-9", "Kontoauszug", WeakReason.COVERED_BY_REFERENCE, List.of("UC9")),
                        hint("FR-8", "TERM-3", "Buchung", WeakReason.ONLY_IN_ACCEPTANCE_CRITERION, List.of()),
                        hint("UC3", "TERM-9", "Kontoauszug", WeakReason.COVERED_BY_REFERENCE,
                                List.of("UC9", "FR-1")),
                        hint("UC4", "TERM-9", "Kontoauszug", WeakReason.COVERED_BY_REFERENCE, List.of("UC9")))));

        assertThat(rendered)
                .contains("4 weaker mention hints not counted")
                .contains("\n- TERM-9 Kontoauszug (3): stepText (covered by UC9): UC2, UC4; "
                        + "stepText (covered by UC9, FR-1): UC3"
                        + "\n- TERM-3 Buchung (1): criterionText: FR-8");
    }

    /**
     * kogn-io/arknet#689: switched off, both lists give way to one sentence, and every count in the
     * summary - findings and hints alike - stays exactly what it is with the lists shown.
     */
    @Test
    void omitsBothMentionListsButKeepsEveryCountWhenSwitchedOff() {
        OrphanCheck.Result result = new OrphanCheck.Result(
                List.of(new ResourceFinding(ID + "fr-2", "FR-2", "FunctionalRequirement", "Logout")),
                List.of(),
                List.of(mention("FR-1", "TERM-7", "Mitglied", List.of("description")),
                        mention("FR-3", "TERM-7", "Mitglied", List.of("description"))),
                List.of(),
                List.of(hint("FR-8", "TERM-3", "Buchung", WeakReason.ONLY_IN_ACCEPTANCE_CRITERION, List.of())));

        String shown = renderer.orphanSection(result, true);
        String omitted = renderer.orphanSection(result, false);

        assertThat(result.total()).isEqualTo(3);
        assertThat(omitted.lines().findFirst()).isEqualTo(shown.lines().findFirst());
        assertThat(omitted)
                .contains("2 unlinked mentions")
                .contains("1 weaker mention hint not counted")
                .contains("| FR-2 | FunctionalRequirement | Logout |")
                .contains(StoreCheckRenderer.MENTIONS_OMITTED)
                .doesNotContain("Mentioned in text but not linked:")
                .doesNotContain("Mentioned only in acceptance criteria")
                .doesNotContain("TERM-7");
        assertThat(shown)
                .contains("- TERM-7 Mitglied (2)")
                .contains("- TERM-3 Buchung (1)")
                .doesNotContain(StoreCheckRenderer.MENTIONS_OMITTED);
        assertThat(renderer.orphanSection(result)).isEqualTo(shown);
    }

    /** An all-clear section with hints only still says the hints were left out, not that none exist. */
    @Test
    void namesTheOmittedHintsInAnOtherwiseCleanSection() {
        String rendered = renderer.orphanSection(new OrphanCheck.Result(
                List.of(), List.of(), List.of(), List.of(),
                List.of(hint("FR-8", "TERM-3", "Buchung", WeakReason.ONLY_IN_ACCEPTANCE_CRITERION, List.of()))),
                false);

        assertThat(rendered)
                .contains("no orphaned requirements")
                .contains("1 weaker mention hint not counted")
                .contains(StoreCheckRenderer.MENTIONS_OMITTED)
                .doesNotContain("TERM-3");
    }

    /** Nothing to omit, nothing to announce: a section without mentions reads the same either way. */
    @Test
    void readsTheSameEitherWayWhenThereAreNoMentions() {
        OrphanCheck.Result result = new OrphanCheck.Result(
                List.of(new ResourceFinding(ID + "fr-2", "FR-2", "FunctionalRequirement", "Logout")),
                List.of(), List.of(), List.of(), List.of());

        assertThat(renderer.orphanSection(result, false)).isEqualTo(renderer.orphanSection(result, true));
    }

    private static MentionFinding mention(final String source, final String term, final String label,
            final List<String> fields) {
        return new MentionFinding(ID + source.toLowerCase(), source, ID + term.toLowerCase(), term, label,
                "usesTerm", fields);
    }

    private static WeakMention hint(final String source, final String term, final String label,
            final WeakReason reason, final List<String> coveredBy) {
        return new WeakMention(ID + source.toLowerCase(), source, ID + term.toLowerCase(), term, label,
                "usesTerm", List.of(reason == WeakReason.ONLY_IN_ACCEPTANCE_CRITERION ? "criterionText" : "stepText"),
                reason, coveredBy);
    }
}
