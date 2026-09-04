// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.CheckAdrs.CheckReport;
import de.hauschel.arknet.adr.application.port.in.CheckAdrs.Rule;
import de.hauschel.arknet.adr.application.port.in.CheckAdrs.Severity;
import de.hauschel.arknet.adr.application.port.in.ListAdrs;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.adr.domain.TermRef;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Rule-by-rule tests for {@link AdrCheckService}: one per check {@code adr_check} carries, one per
 * pattern it must deliberately stay silent about, plus the ordering and the two-class split the
 * report promises (kogn-io/arknet#387). Driven through a fake {@link ListAdrs} handing over
 * hand-built decisions - the read path this service borrows instead of a repository of its own,
 * which is what lets a test say "this corpus" in three lines.
 */
class AdrCheckServiceTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final RequirementRef FR_1 =
            new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
    private static final BoundedContextRef BC_1 =
            new BoundedContextRef(ResourceId.of("https://w3id.org/arknet/id/bc-1"));
    private static final TermRef TERM_1 = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));

    private final List<AdrDetail> corpus = new ArrayList<>();
    private final AdrCheckService service = new AdrCheckService((projectId, displayLocale) -> corpus);

    @Test
    void reportsADecisionDateOnAProposedDecisionAsAFact() {
        // given a PROPOSED record that nevertheless carries a decision date
        record(draft(1).addressing(FR_1).decidedOn(LocalDate.of(2026, 8, 24)));

        // when
        CheckReport report = service.check(PROJECT, null);

        // then
        assertTrue(has(report, "ADR-1", Rule.DECISION_DATE_WHILE_PROPOSED), report.findings().toString());
        assertEquals(Severity.FACT, Rule.DECISION_DATE_WHILE_PROPOSED.severity());
    }

    @Test
    void staysSilentAboutADecisionDateOnAnAcceptedDecision() {
        // given an ACCEPTED record - that transition is exactly where the date is written
        record(draft(1).accepted().addressing(FR_1).decidedOn(LocalDate.of(2026, 8, 24)));

        assertFalse(has(service.check(PROJECT, null), "ADR-1", Rule.DECISION_DATE_WHILE_PROPOSED));
    }

    @Test
    void reportsMissingConsequencesAndOptionsInTheSameWordsTheWritingToolsUse() {
        // given a record with neither list populated
        record(draft(1).addressing(FR_1));

        CheckReport report = service.check(PROJECT, null);

        assertTrue(has(report, "ADR-1", Rule.NO_CONSEQUENCE), report.findings().toString());
        assertTrue(has(report, "ADR-1", Rule.NO_CONSIDERED_OPTION), report.findings().toString());
        // the very wording adr_add/adr_update append inline (kogn-io/arknet#448)
        assertEquals("no consequence recorded", Rule.NO_CONSEQUENCE.label());
        assertEquals("no considered option recorded", Rule.NO_CONSIDERED_OPTION.label());
    }

    @Test
    void reportsOptionsWithoutAChosenOneOnlyOnceTheDecisionWasTaken() {
        // given the same option space on a PROPOSED and on an ACCEPTED record
        record(draft(1).addressing(FR_1).options(rejectedOption()));
        record(draft(2).accepted().addressing(FR_1).options(rejectedOption()));

        CheckReport report = service.check(PROJECT, null);

        // then only the taken decision is missing a choice - PROPOSED means nothing is chosen yet
        assertFalse(has(report, "ADR-1", Rule.NO_CHOSEN_OPTION), report.findings().toString());
        assertTrue(has(report, "ADR-2", Rule.NO_CHOSEN_OPTION), report.findings().toString());
    }

    @Test
    void staysSilentAboutATakenDecisionWithNoOptionsAtAll() {
        // given an accepted record whose option space is empty - legitimate, and already reported
        // once as NO_CONSIDERED_OPTION; a second finding would count the same gap twice
        record(draft(1).accepted().addressing(FR_1));

        assertFalse(has(service.check(PROJECT, null), "ADR-1", Rule.NO_CHOSEN_OPTION));
    }

    @Test
    void reportsADecisionThatHangsInTheModelByNothing() {
        // given a record with no addressesRequirement and no affectsContext, only a used term
        record(draft(1).using(TERM_1));

        assertTrue(has(service.check(PROJECT, null), "ADR-1", Rule.NO_OUTWARD_EDGE));
    }

    @Test
    void staysSilentWhenEitherOutwardEdgeIsPresent() {
        record(draft(1).addressing(FR_1));
        record(draft(2).affecting(BC_1));

        CheckReport report = service.check(PROJECT, null);

        assertFalse(has(report, "ADR-1", Rule.NO_OUTWARD_EDGE), report.findings().toString());
        assertFalse(has(report, "ADR-2", Rule.NO_OUTWARD_EDGE), report.findings().toString());
    }

    @Test
    void reportsAProseReferenceToADecisionTheProjectDoesNotHold() {
        // given prose naming a store code that does not exist and a file-record number that never will
        record(draft(1).addressing(FR_1).decision("Supersedes ADR-99 and refines ADR-016."));

        CheckReport report = service.check(PROJECT, null);

        assertTrue(hasEvidence(report, Rule.UNRESOLVED_DECISION_REFERENCE, "ADR-99"), report.findings().toString());
        assertTrue(hasEvidence(report, Rule.UNRESOLVED_DECISION_REFERENCE, "ADR-016"), report.findings().toString());
    }

    @Test
    void reportsProseAndGraphRunningApartButNotAnEdgeThatCarriesTheReference() {
        // given two records naming ADR-1 in their prose, only one of them linked to it
        record(draft(1).addressing(FR_1));
        record(draft(2).addressing(FR_1).context("Continues where ADR-1 left off."));
        record(draft(3).addressing(FR_1).context("Continues where ADR-1 left off.").relatedTo("ADR-1"));

        CheckReport report = service.check(PROJECT, null);

        assertTrue(has(report, "ADR-2", Rule.DECISION_REFERENCE_WITHOUT_EDGE), report.findings().toString());
        assertFalse(has(report, "ADR-3", Rule.DECISION_REFERENCE_WITHOUT_EDGE), report.findings().toString());
    }

    @Test
    void staysSilentAboutARecordNamingItsOwnCode() {
        record(draft(1).addressing(FR_1).decision("ADR-1 replaces the hand-written note."));

        CheckReport report = service.check(PROJECT, null);

        assertFalse(has(report, "ADR-1", Rule.DECISION_REFERENCE_WITHOUT_EDGE), report.findings().toString());
        assertFalse(has(report, "ADR-1", Rule.UNRESOLVED_DECISION_REFERENCE), report.findings().toString());
    }

    @Test
    void suspectsATrackerReferenceWhereverItStands() {
        record(draft(1).addressing(FR_1).context("Raised in #123.").decision("Closes kogn-io/arknet#456."));

        CheckReport report = service.check(PROJECT, null);

        assertTrue(hasEvidence(report, Rule.TRACKER_REFERENCE, "#123"), report.findings().toString());
        assertTrue(hasEvidence(report, Rule.TRACKER_REFERENCE, "#456"), report.findings().toString());
        assertEquals(Severity.SUSPICION, Rule.TRACKER_REFERENCE.severity());
    }

    @Test
    void suspectsAddressAndPortLiterals() {
        record(draft(1).addressing(FR_1)
                .context("Bound to 127.0.0.1 only.")
                .decision("Serve on localhost:47331, never on port 8080."));

        CheckReport report = service.check(PROJECT, null);

        assertTrue(hasEvidence(report, Rule.ADDRESS_LITERAL, "127.0.0.1"), report.findings().toString());
        assertTrue(hasEvidence(report, Rule.ADDRESS_LITERAL, "localhost:47331"), report.findings().toString());
        assertTrue(hasEvidence(report, Rule.ADDRESS_LITERAL, "port 8080"), report.findings().toString());
    }

    @Test
    void suspectsStatusProseInTheDecisionAndInAConsequenceButNotInTheContext() {
        // given "currently"/"so far" in all three places - legitimate in the context, which
        // describes the situation the decision was taken in (skill rule R4)
        record(draft(1).addressing(FR_1)
                .context("The store currently holds every decision.")
                .decision("The export is currently regenerated by hand.")
                .consequences(consequence("No importer exists so far.")));

        CheckReport report = service.check(PROJECT, null);

        assertTrue(hasField(report, Rule.STATUS_PROSE, "decision"), report.findings().toString());
        assertTrue(hasField(report, Rule.STATUS_PROSE, "consequence 1"), report.findings().toString());
        assertFalse(hasField(report, Rule.STATUS_PROSE, "context"), report.findings().toString());
    }

    @Test
    void suspectsGermanStatusProseAsWell() {
        record(draft(1).addressing(FR_1).decision("Der Export wird derzeit von Hand erzeugt."));

        assertTrue(hasEvidenceIgnoringCase(service.check(PROJECT, null), Rule.STATUS_PROSE, "derzeit"));
    }

    @Test
    void suspectsTwoRecordsWithNearIdenticalTitlesOfBeingOneDecision() {
        record(draft(1).addressing(FR_1).name("Store the ADR export under docs"));
        record(draft(2).addressing(FR_1).name("Store the ADR export under docs/"));
        record(draft(3).addressing(FR_1).name("Use an embedded triple store"));

        CheckReport report = service.check(PROJECT, null);

        assertTrue(hasEvidence(report, Rule.POSSIBLE_DUPLICATE, "ADR-1"), report.findings().toString());
        // reported once, on the later record - not a second time in the other direction
        assertEquals(1, countOf(report, Rule.POSSIBLE_DUPLICATE), report.findings().toString());
        assertFalse(has(report, "ADR-3", Rule.POSSIBLE_DUPLICATE), report.findings().toString());
    }

    @Test
    void separatesFactsFromSuspicionsAndOrdersFindingsByRunningNumber() {
        record(draft(10).addressing(FR_1).decision("Raised in #7."));
        record(draft(2).addressing(FR_1));

        CheckReport report = service.check(PROJECT, null);

        assertEquals(2, report.checkedCount());
        // ADR-10 after ADR-2, not before it as string order would have it
        assertEquals(List.of("ADR-2", "ADR-2", "ADR-10", "ADR-10", "ADR-10"),
                report.findings().stream().map(finding -> finding.code().value()).toList());
        assertTrue(report.facts().stream().allMatch(finding -> finding.rule().severity() == Severity.FACT));
        assertEquals(1, report.suspicions().size(), report.suspicions().toString());
    }

    @Test
    void alwaysCarriesWhatItDidNotCheck() {
        // given a corpus with nothing to report - the case an empty result would be read as
        // "reviewed" if the boundary of the check did not travel with it
        record(draft(1).accepted().addressing(FR_1)
                .consequences(consequence("Reading a decision no longer needs the repository"))
                .options(new ConsideredOption(1, "Keep the files", "Two truths", OptionOutcome.REJECTED),
                        new ConsideredOption(2, "Move to the store", "One truth", OptionOutcome.CHOSEN))
                .decidedOn(LocalDate.of(2026, 8, 24)));

        CheckReport report = service.check(PROJECT, null);

        assertEquals(List.of(), report.findings());
        assertFalse(report.notChecked().isEmpty());
        assertTrue(report.notChecked().stream().anyMatch(entry -> entry.contains("independence test")),
                report.notChecked().toString());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> service.check(null, null));
        assertThrows(NullPointerException.class, () -> new AdrCheckService(null));
    }

    // --- fixtures ----------------------------------------------------------------

    private void record(Draft draft) {
        corpus.add(draft.build());
    }

    private static Draft draft(int number) {
        return new Draft(number);
    }

    private static Consequence consequence(String statement) {
        return new Consequence(1, statement, ConsequenceType.NEGATIVE);
    }

    private static ConsideredOption rejectedOption() {
        return new ConsideredOption(1, "Keep the files", "Two truths", OptionOutcome.REJECTED);
    }

    private static boolean has(CheckReport report, String code, Rule rule) {
        return report.findings().stream()
                .anyMatch(finding -> finding.code().value().equals(code) && finding.rule() == rule);
    }

    private static boolean hasEvidence(CheckReport report, Rule rule, String evidence) {
        return report.findings().stream()
                .anyMatch(finding -> finding.rule() == rule && evidence.equals(finding.evidence()));
    }

    private static boolean hasEvidenceIgnoringCase(CheckReport report, Rule rule, String evidence) {
        return report.findings().stream().anyMatch(finding -> finding.rule() == rule
                && finding.evidence() != null && finding.evidence().equalsIgnoreCase(evidence));
    }

    private static boolean hasField(CheckReport report, Rule rule, String field) {
        return report.findings().stream()
                .anyMatch(finding -> finding.rule() == rule && field.equals(finding.field()));
    }

    private static long countOf(CheckReport report, Rule rule) {
        return report.findings().stream().filter(finding -> finding.rule() == rule).count();
    }

    /**
     * Builds one {@link AdrDetail} for the corpus under test. A mutable builder rather than a
     * fourteen-argument constructor call per case: every test here varies one or two fields and
     * would otherwise drown the rule it is about in defaults.
     */
    private static final class Draft {

        private final int number;
        private String name;
        private AdrStatus status = AdrStatus.PROPOSED;
        private String context;
        private String decision;
        private List<Consequence> consequences = List.of();
        private List<ConsideredOption> consideredOptions = List.of();
        private LocalDate decisionDate;
        private List<RequirementRef> requirements = List.of();
        private List<BoundedContextRef> contexts = List.of();
        private List<TermRef> terms = List.of();
        private List<AdrCode> relatedTo = List.of();

        private Draft(int number) {
            this.number = number;
            this.name = "Decision " + number;
            this.context = "Why decision " + number + " was needed";
            this.decision = "What decision " + number + " settles";
        }

        private Draft accepted() {
            status = AdrStatus.ACCEPTED;
            return this;
        }

        private Draft name(String value) {
            name = value;
            return this;
        }

        private Draft context(String value) {
            context = value;
            return this;
        }

        private Draft decision(String value) {
            decision = value;
            return this;
        }

        private Draft consequences(Consequence... values) {
            consequences = List.of(values);
            return this;
        }

        private Draft options(ConsideredOption... values) {
            consideredOptions = List.of(values);
            return this;
        }

        private Draft decidedOn(LocalDate value) {
            decisionDate = value;
            return this;
        }

        private Draft addressing(RequirementRef... values) {
            requirements = List.of(values);
            return this;
        }

        private Draft affecting(BoundedContextRef... values) {
            contexts = List.of(values);
            return this;
        }

        private Draft using(TermRef... values) {
            terms = List.of(values);
            return this;
        }

        private Draft relatedTo(String... codes) {
            relatedTo = List.of(codes).stream().map(AdrCode::new).toList();
            return this;
        }

        private AdrDetail build() {
            Adr adr = new Adr(new AdrId(ResourceId.of("https://w3id.org/arknet/id/adr-" + number)),
                    new AdrCode("ADR-" + number), name, status, context, decision, consequences,
                    consideredOptions, decisionDate, requirements, contexts, terms, null, List.of());
            return new AdrDetail(adr, List.of(), List.of(), relatedTo);
        }
    }
}
