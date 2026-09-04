// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: read every recorded decision of a project and report what is mechanically decidable
 * about it - the consistency and quality check {@code adr_check} exposes (kogn-io/arknet#387).
 *
 * <p><strong>Reading only, never a gate.</strong> Nothing here writes, rejects or advances a status.
 * The SHACL write gate already checks a record's structure at write time; this checks whether the
 * corpus holds together afterwards, and a corpus-wide rule cannot be a write gate without refusing
 * a record for something a <em>neighbour</em> record does. A finding is a statement, not a
 * transition: what follows from it stays a decision of the user.</p>
 *
 * <p><strong>Two classes, never merged (issue #387).</strong> A {@link Severity#FACT} is decidable
 * without judgement - a date on a decision that has not been taken, a missing consequence, a code in
 * the prose the project does not hold. A {@link Severity#SUSPICION} is a pattern worth a look whose
 * verdict needs a reader - a tracker number may be the subject of the decision, an address literal
 * may be the thing decided about. Presenting the second as the first is what makes a check tool
 * distrusted after its first false positive, which is why {@link CheckReport} keeps them apart
 * rather than handing out one ranked list.</p>
 *
 * <p><strong>{@link #NOT_CHECKED} is part of the answer, not a footnote.</strong> The rules this
 * check cannot carry are exactly the ones that cost the most (bundling: a record carrying two
 * decisions cannot be half-superseded later), so an empty result read as "reviewed" would cover up
 * precisely the gap it does not close. {@link CheckReport#notChecked()} therefore travels with every
 * report, findings or not, and every presentation of one is expected to show it.</p>
 */
public interface CheckAdrs {

    /**
     * What this check deliberately leaves to a human reviewer - each entry a rule of the ADR skill's
     * review no amount of pattern matching decides.
     *
     * <p>Held here rather than in the presenting adapter so that a second presentation (a report, a
     * second transport) cannot quietly ship without it.</p>
     */
    List<String> NOT_CHECKED = List.of(
            "whether a record carries more than one decision - the independence test",
            "whether two records contradict one another",
            "whether a consequence is substantial or merely dutiful");

    /**
     * Checks every decision recorded in the given project and returns the findings, grouped by
     * severity but never filtered.
     *
     * <p>Sees exactly what {@code adr_list} sees: one read of the project's decisions in one
     * language variant per field. A record that read path skips (unresolvable store-first status or
     * {@code supersededBy} data) is invisible here as well - the caller is expected to pair this
     * with {@link CountSkippedAdrs} the way {@code adr_list} does, rather than have this port
     * re-read the whole decision graph to rediscover the same gap.</p>
     *
     * @param projectId     the project (architecture model) to check
     * @param displayLocale a BCP-47 language tag choosing which candidate of a multilingual field is
     *                      checked, or {@code null}/blank to use the project's own configured
     *                      display preference - a pattern present only in a variant this run did not
     *                      select is not seen, exactly as it would not be read
     * @return the report, never {@code null}; empty of findings for a corpus with nothing to say
     */
    CheckReport check(ProjectId projectId, String displayLocale);

    /** Whether a finding is decidable on its own or needs a reader to rule on it. */
    enum Severity {

        /** Decidable without judgement; true as stated. */
        FACT,

        /** A pattern worth looking at, whose verdict belongs to a reader. */
        SUSPICION
    }

    /**
     * The individual checks, each with the severity class it reports under and the sentence naming
     * it.
     *
     * <p><strong>The label lives on the rule, not in the adapter.</strong> Two of them
     * ({@link #NO_CONSEQUENCE}, {@link #NO_CONSIDERED_OPTION}) are the very sentences
     * {@code adr_add}/{@code adr_update} already append inline to a record they just wrote
     * (kogn-io/arknet#448). The inline hint and the corpus-wide finding are one statement about one
     * defect; sharing this constant is what keeps a caller from meeting it in two wordings and
     * taking them for two different remarks.</p>
     */
    enum Rule {

        /**
         * A day recorded on a decision that has expressly not been taken. Only
         * {@code adr_set_status} ever writes the field, so this is store-first residue or a
         * pre-#374 record (skill rule R8).
         */
        DECISION_DATE_WHILE_PROPOSED(Severity.FACT, "decisionDate set while the decision is PROPOSED"),

        /** Nothing recorded about what the decision costs or buys (skill rule R5). */
        NO_CONSEQUENCE(Severity.FACT, "no consequence recorded"),

        /** No option space recorded at all - "no alternative" is a claim that needs saying. */
        NO_CONSIDERED_OPTION(Severity.FACT, "no considered option recorded"),

        /**
         * Options are recorded but none is marked {@code CHOSEN}, on a decision that has been taken.
         * Reported from {@code ACCEPTED} on only: {@code PROPOSED} means precisely that nothing has
         * been chosen yet, and {@code Adr#accept} is where the invariant is enforced
         * (kogn-io/arknet#427).
         */
        NO_CHOSEN_OPTION(Severity.FACT, "an option was never chosen, though the decision was taken"),

        /**
         * The decision hangs in the model by nothing: no requirement it addresses, no bounded
         * context it affects. Nobody changing either will ever be pointed at it.
         */
        NO_OUTWARD_EDGE(Severity.FACT, "neither addressesRequirement nor affectsContext"),

        /**
         * The prose names an {@code ADR-n} the project does not hold - a reference to a deleted
         * record, to another numbering space (the zero-padded file records) or a typo (skill rule
         * R6).
         */
        UNRESOLVED_DECISION_REFERENCE(Severity.FACT, "names a decision this project does not hold"),

        /**
         * The prose names a peer decision the graph does not connect to. Prose and graph disagree,
         * and only the graph is followable (skill rule R7).
         */
        DECISION_REFERENCE_WITHOUT_EDGE(Severity.FACT,
                "names a decision it has no supersedes/supersededBy/relatedTo edge to"),

        /**
         * An issue, PR or commit number in the text. Sometimes the subject of the decision, usually
         * a reference that ages worse than the decision does (skill rule R3).
         */
        TRACKER_REFERENCE(Severity.SUSPICION, "tracker reference"),

        /**
         * A host, address or port literal. Sometimes the thing decided, usually an implementation
         * detail that will be wrong before the decision is (skill rule R2).
         */
        ADDRESS_LITERAL(Severity.SUSPICION, "address or port literal"),

        /**
         * "today", "currently", "not yet", "so far" and their german counterparts in {@code decision}
         * or a consequence: true the day it was written, quietly false later. Legitimate in
         * {@code context}, which describes the situation the decision was taken in, and therefore
         * not looked for there (skill rule R4).
         */
        STATUS_PROSE(Severity.SUSPICION, "status prose - true when written, silently false later"),

        /**
         * Two records whose titles name near enough the same subject. Whether they are one decision
         * recorded twice, two facets of one subject that merely fail to reference each other, or
         * genuinely separate is a reading, never a match count.
         */
        POSSIBLE_DUPLICATE(Severity.SUSPICION, "near-identical title to another decision");

        private final Severity severity;
        private final String label;

        Rule(Severity severity, String label) {
            this.severity = severity;
            this.label = label;
        }

        /** @return the class this rule reports under */
        public Severity severity() {
            return severity;
        }

        /** @return the sentence naming this rule, as a presentation shows it */
        public String label() {
            return label;
        }
    }

    /**
     * One thing found on one decision.
     *
     * @param code     the decision the finding is about
     * @param rule     which check produced it
     * @param field    where in the record it sits ({@code "context"}, {@code "decision"},
     *                 {@code "consequence 2"}, {@code "considered option 1"}), or {@code null} for a
     *                 finding about the record as a whole
     * @param evidence the literal that triggered it (the matched text, or a peer decision's code),
     *                 or {@code null} where the rule's own label already says everything - the raw
     *                 match rather than a rewritten one, so a reader can search for it
     */
    record Finding(AdrCode code, Rule rule, String field, String evidence) {

        public Finding {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(rule, "rule");
        }
    }

    /**
     * The outcome of one check run.
     *
     * @param checkedCount how many decisions were actually read - the denominator every finding
     *                     count is to be read against, and the number a caller hands to
     *                     {@link CountSkippedAdrs#skippedCount} to learn whether the corpus was even
     *                     fully visible
     * @param findings     every finding of both severities, ordered by decision and rule; empty when
     *                     there is nothing to report
     */
    record CheckReport(int checkedCount, List<Finding> findings) {

        public CheckReport {
            if (checkedCount < 0) {
                throw new IllegalArgumentException("checkedCount must not be negative: " + checkedCount);
            }
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        /** @return the findings decidable without judgement, in report order */
        public List<Finding> facts() {
            return bySeverity(Severity.FACT);
        }

        /** @return the findings a reader has to rule on, in report order */
        public List<Finding> suspicions() {
            return bySeverity(Severity.SUSPICION);
        }

        /**
         * What this run did not look at - {@link CheckAdrs#NOT_CHECKED}, carried on the report so
         * that a presentation showing findings has the boundary of the check in the same hand.
         *
         * @return the rules left to a human reviewer, never empty
         */
        public List<String> notChecked() {
            return NOT_CHECKED;
        }

        private List<Finding> bySeverity(Severity severity) {
            List<Finding> selected = new ArrayList<>();
            for (Finding finding : findings) {
                if (finding.rule().severity() == severity) {
                    selected.add(finding);
                }
            }
            return List.copyOf(selected);
        }
    }
}
