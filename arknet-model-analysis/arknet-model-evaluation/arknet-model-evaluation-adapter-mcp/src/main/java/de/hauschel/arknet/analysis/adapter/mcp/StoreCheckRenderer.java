// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.adapter.mcp;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.analysis.domain.LanguageGapCheck.Gap;
import de.hauschel.arknet.analysis.domain.OrphanCheck;
import de.hauschel.arknet.analysis.domain.OrphanCheck.MentionFinding;
import de.hauschel.arknet.analysis.domain.OrphanCheck.ResourceFinding;
import de.hauschel.arknet.analysis.domain.OrphanCheck.WeakMention;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph;
import de.hauschel.arknet.analysis.domain.RoleTermDuplicateCheck.Finding;
import de.hauschel.arknet.analysis.domain.StepAcceptanceCheck.Kind;
import de.hauschel.arknet.analysis.domain.StepAcceptanceCheck;
import de.hauschel.arknet.analysis.domain.StoreCheckKind;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph;
import de.hauschel.arknet.persistence.Prefixes;

/**
 * Renders {@code store_check}'s findings as the compact text an agent reads.
 *
 * <p>Split from {@link LanguageGapCheck} for the same reason {@code TraceabilityRenderer} is split
 * from {@code TraceabilityGraph}: the rule is testable without a table, and the table is testable
 * without a store.</p>
 */
public final class StoreCheckRenderer {

    /** Scope notes per rule, built from {@link StoreCheckKind} so output and tests share one source. */
    static final String LANGUAGE_BLIND_SPOT = scopeNote(StoreCheckKind.LANGUAGE);
    static final String ROLE_TERM_BLIND_SPOT = scopeNote(StoreCheckKind.ROLE_TERM_DUPLICATE);
    static final String STEP_BLIND_SPOT = scopeNote(StoreCheckKind.STEP_ACCEPTANCE);
    static final String ORPHAN_BLIND_SPOT = scopeNote(StoreCheckKind.ORPHAN);

    /**
     * What a check looks at and what it does not, appended to every section: an empty section that
     * does not say what it could not look at reads as "reviewed", which is the one misreading a
     * check must not invite - the same discipline {@code adr_check} follows with its own "not
     * checked here" list.
     */
    static String scopeNote(final StoreCheckKind kind) {
        return "Checked here: " + kind.scope() + "\nNot seen here: " + kind.notSeen();
    }

    private final Prefixes prefixes;

    /**
     * @param prefixes the CURIE resolver, shared with the store read path so a predicate is
     *                 shortened the same way here as in {@code store_overview}
     */
    public StoreCheckRenderer(final Prefixes prefixes) {
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    /**
     * Renders the language section.
     *
     * @param maintainedLanguages what the project undertakes to maintain; empty means the check did
     *                            not run
     * @param gaps                the findings, already ordered
     * @return the section text
     */
    public String languageSection(final List<String> maintainedLanguages, final List<Gap> gaps) {
        Objects.requireNonNull(maintainedLanguages, "maintainedLanguages");
        Objects.requireNonNull(gaps, "gaps");
        if (maintainedLanguages.isEmpty()) {
            // Deliberately not "no gaps found": with no declared set there is no target state, so a
            // field carrying one language is not incomplete against anything. Reporting a clean
            // result here would answer a question that was never asked (kogn-io/arknet#412).
            return "LANGUAGE: not checked - this project declares no maintained language set, so there "
                    + "is no target state to compare its fields against. Declare one with "
                    + "project_update(languages=[\"de\",\"en\"]), then run this check again.\n\n" + LANGUAGE_BLIND_SPOT;
        }
        final StringBuilder rendered = new StringBuilder("LANGUAGE: maintained languages ")
                .append(String.join(", ", maintainedLanguages)).append(".");
        if (gaps.isEmpty()) {
            return rendered.append(" No field is missing one of them.\n\n").append(LANGUAGE_BLIND_SPOT).toString();
        }
        rendered.append("\n\n| Resource | Type | Field | Missing |\n| --- | --- | --- | --- |");
        for (final Gap gap : gaps) {
            rendered.append("\n| ").append(handleOf(gap))
                    .append(" | ").append(gap.typeLocalName() == null ? "-" : gap.typeLocalName())
                    .append(" | ").append(prefixes.toCurie(gap.predicateIri()))
                    .append(" | ").append(String.join(", ", gap.missingLanguages()))
                    .append(" |");
        }
        final long resources = gaps.stream().map(Gap::subjectIri).distinct().count();
        rendered.append("\n\n").append(gaps.size()).append(gaps.size() == 1 ? " field on " : " fields on ")
                .append(resources).append(resources == 1 ? " resource" : " resources")
                .append(" missing a maintained language.");
        return rendered.append("\n\n").append(LANGUAGE_BLIND_SPOT).toString();
    }

    /**
     * Renders the role/term-duplicate section (kogn-io/arknet#512).
     *
     * @param findings every role and term found to share a name, already ordered
     * @return the section text
     */
    public String roleTermDuplicateSection(final List<Finding> findings) {
        Objects.requireNonNull(findings, "findings");
        if (findings.isEmpty()) {
            return "ROLE_TERM_DUPLICATE: no role and glossary term share a name.\n\n" + ROLE_TERM_BLIND_SPOT;
        }
        final StringBuilder rendered = new StringBuilder("ROLE_TERM_DUPLICATE: ")
                .append(findings.size()).append(findings.size() == 1 ? " pair" : " pairs")
                .append(" of a role and a glossary term sharing a name.")
                .append("\n\n| Role | Term | Name |\n| --- | --- | --- |");
        for (final Finding finding : findings) {
            rendered.append("\n| ").append(finding.roleCode())
                    .append(" | ").append(finding.termCode())
                    .append(" | ").append(finding.name())
                    .append(" |");
        }
        return rendered.append("\n\n").append(ROLE_TERM_BLIND_SPOT).toString();
    }

    /**
     * Renders the step/acceptance-criterion section (kogn-io/arknet#622).
     *
     * <p>Two tables, not one: a step hanging off no requirement is a missing edge, a step whose
     * requirement carries no criterion is an incomplete requirement, and reading them in one list
     * would hide which of the two a row is.</p>
     *
     * @param findings every main-flow step no acceptance criterion stands behind, already ordered
     * @return the section text
     */
    public String stepAcceptanceSection(final List<StepAcceptanceCheck.Finding> findings) {
        Objects.requireNonNull(findings, "findings");
        if (findings.isEmpty()) {
            return "STEP_ACCEPTANCE: every main-flow step reaches an acceptance criterion through the "
                    + "requirement it realises.\n\n" + STEP_BLIND_SPOT;
        }
        final List<StepAcceptanceCheck.Finding> unrealised = findings.stream()
                .filter(finding -> finding.kind() == Kind.NO_REQUIREMENT).toList();
        final List<StepAcceptanceCheck.Finding> uncovered = findings.stream()
                .filter(finding -> finding.kind() == Kind.REQUIREMENT_WITHOUT_CRITERION).toList();
        final StringBuilder rendered = new StringBuilder("STEP_ACCEPTANCE: ")
                .append(findings.size())
                .append(findings.size() == 1 ? " main-flow step has" : " main-flow steps have")
                .append(" no acceptance criterion behind them.");
        if (!unrealised.isEmpty()) {
            rendered.append("\n\nNo requirement (arkreq:stepRealises missing):")
                    .append("\n\n| Use case | Step |\n| --- | --- |");
            for (final StepAcceptanceCheck.Finding finding : unrealised) {
                rendered.append("\n| ").append(finding.useCaseCode())
                        .append(" | ").append(positionOf(finding)).append(" |");
            }
        }
        if (!uncovered.isEmpty()) {
            rendered.append("\n\nRequirement without an acceptance criterion:")
                    .append("\n\n| Use case | Step | Requirement |\n| --- | --- | --- |");
            for (final StepAcceptanceCheck.Finding finding : uncovered) {
                rendered.append("\n| ").append(finding.useCaseCode())
                        .append(" | ").append(positionOf(finding))
                        .append(" | ").append(finding.requirementCodes().isEmpty()
                                ? "-" : String.join(", ", finding.requirementCodes()))
                        .append(" |");
            }
        }
        return rendered.append("\n\n").append(STEP_BLIND_SPOT).toString();
    }

    /** A step's 1-based position, or {@code -} when it carries none - never an invented number. */
    private static String positionOf(final StepAcceptanceCheck.Finding finding) {
        return finding.position() == null ? "-" : String.valueOf(finding.position());
    }

    /** How a line of either mention list reads (kogn-io/arknet#689). */
    static final String LINE_LAYOUT = "One line per term: code, label, (number of sources), then the sources "
            + "grouped by the field(s) the term was found in (field+field: sources; next group).";

    /**
     * Legend under the mention list: the missing edge follows from the source's type, so it is said
     * once here instead of on every row (kogn-io/arknet#689). Kept in step with the edge names
     * {@code TraceabilityGraph#classifiedMentions} assigns per source type.
     */
    static final String MISSING_EDGE_LEGEND = "Missing edge by source type: requirement, use case or decision "
            + "-> usesTerm; bounded context -> ubiquitousLanguageTerm; glossary term -> broader/narrower/related.";

    /** Legend under the hint list: the reason is implied unless a covering reference is named. */
    static final String HINT_LEGEND = "A group marked \"covered by\" names the term next to a reference "
            + "that already carries the edge; every other group names it only in an acceptance criterion.";

    /** What stands in for both mention lists when the caller switched them off (kogn-io/arknet#689). */
    static final String MENTIONS_OMITTED = "Mention lists omitted (withMentions=false); the counts above "
            + "still include them. Call again without withMentions=false to see them.";

    /**
     * Renders the orphan section with both mention lists shown - the answer {@code orphan_check}
     * and a {@code store_check} call without {@code withMentions} give.
     *
     * @param result the findings lists and hints, already computed by {@link OrphanCheck#run}
     * @return the section text
     * @see #orphanSection(OrphanCheck.Result, boolean)
     */
    public String orphanSection(final OrphanCheck.Result result) {
        return orphanSection(result, true);
    }

    /**
     * Renders the orphan section (kogn-io/arknet#473, folding the former {@code orphan_check} tool
     * in here): one block per finding list, each shown only when it is non-empty - the same
     * discipline {@link #stepAcceptanceSection} follows for its two cases.
     *
     * <p>Mentions downgraded to hints (kogn-io/arknet#697) follow in a separate block and a
     * clause of the summary; they never count as findings, so an otherwise clean result stays
     * clean and merely states them. Both mention lists are grouped by term, one line per term with
     * its mention count and every source with the field(s) the term was found in
     * (kogn-io/arknet#689) - a list per source and term repeated the term's label and the missing
     * edge on every row, which made it the bulk of the whole report.</p>
     *
     * @param result       the findings lists and hints, already computed by {@link OrphanCheck#run}
     * @param withMentions {@code false} replaces both mention lists by one sentence; the summary and
     *                     its counts stay as they are
     * @return the section text
     */
    public String orphanSection(final OrphanCheck.Result result, final boolean withMentions) {
        Objects.requireNonNull(result, "result");
        final int hints = result.weakMentions().size();
        if (result.total() == 0) {
            final StringBuilder clear = new StringBuilder("ORPHAN: no orphaned requirements, unreferenced terms, "
                    + "unlinked mentions or unattached constraints found.");
            if (hints > 0) {
                clear.append(' ').append(hintCount(hints)).append('.');
                if (withMentions) {
                    appendWeakBlock(clear, result.weakMentions());
                } else {
                    clear.append("\n\n").append(MENTIONS_OMITTED);
                }
            }
            return clear.append("\n\n").append(ORPHAN_BLIND_SPOT).toString();
        }
        final StringBuilder rendered = new StringBuilder("ORPHAN: ")
                .append(result.orphanRequirements().size())
                .append(result.orphanRequirements().size() == 1 ? " requirement without" : " requirements without")
                .append(" a satisfying or realising use case, ")
                .append(result.orphanTerms().size())
                .append(result.orphanTerms().size() == 1 ? " term" : " terms").append(" never referenced, ")
                .append(result.unlinkedMentions().size())
                .append(result.unlinkedMentions().size() == 1 ? " unlinked mention" : " unlinked mentions")
                .append(", ").append(result.orphanConstraints().size())
                .append(result.orphanConstraints().size() == 1 ? " constraint" : " constraints")
                .append(" not attached to any requirement or use case.");
        if (hints > 0) {
            rendered.append(' ').append(hintCount(hints)).append('.');
        }
        if (!result.orphanRequirements().isEmpty()) {
            rendered.append("\n\nRequirements no use case satisfies or realises:\n\n");
            appendResourceTable(rendered, result.orphanRequirements());
        }
        if (!result.orphanTerms().isEmpty()) {
            rendered.append("\n\nTerms never referenced:\n\n");
            appendResourceTable(rendered, result.orphanTerms());
        }
        if (withMentions && !result.unlinkedMentions().isEmpty()) {
            appendMentionBlock(rendered, result.unlinkedMentions());
        }
        if (!result.orphanConstraints().isEmpty()) {
            rendered.append("\n\nConstraints not attached to any requirement or use case:\n\n");
            appendResourceTable(rendered, result.orphanConstraints());
        }
        if (withMentions && hints > 0) {
            appendWeakBlock(rendered, result.weakMentions());
        }
        if (!withMentions && (hints > 0 || !result.unlinkedMentions().isEmpty())) {
            rendered.append("\n\n").append(MENTIONS_OMITTED);
        }
        return rendered.append("\n\n").append(ORPHAN_BLIND_SPOT).toString();
    }

    private void appendResourceTable(final StringBuilder out, final List<ResourceFinding> findings) {
        out.append("| Resource | Type | Label |\n| --- | --- | --- |");
        for (final ResourceFinding finding : findings) {
            out.append("\n| ").append(handleOf(finding.iri(), finding.handle()))
                    .append(" | ").append(finding.typeLocalName() == null ? "-" : finding.typeLocalName())
                    .append(" | ").append(finding.label() == null ? "-" : finding.label())
                    .append(" |");
        }
    }

    /**
     * The mention list, one line per term (kogn-io/arknet#689). A source without a business code
     * keeps its missing edge, because the legend reads the edge off a type its shortened IRI does
     * not show.
     */
    private void appendMentionBlock(final StringBuilder out, final List<MentionFinding> mentions) {
        out.append("\n\nMentioned in text but not linked:\n\n").append(LINE_LAYOUT).append(' ')
                .append(MISSING_EDGE_LEGEND).append('\n');
        appendTermLines(out, mentions.stream().map(mention -> new TermSource(
                handleOf(mention.termIri(), mention.termHandle()), mention.termLabel(), fieldsOf(mention.fields()),
                handleOf(mention.sourceIri(), mention.sourceHandle())
                        + (mention.sourceHandle() == null ? " (missing " + mention.edgeLocalName() + ")" : "")))
                .toList());
    }

    private static String hintCount(final int hints) {
        return hints + (hints == 1 ? " weaker mention hint" : " weaker mention hints") + " not counted";
    }

    /** The field set a source names the term in, as the key its sources are grouped under. */
    private static String fieldsOf(final List<String> fields) {
        return fields.isEmpty() ? "-" : String.join("+", fields);
    }

    /**
     * The hint block (kogn-io/arknet#697): mentions that are listed but neither counted nor
     * demanding an edge, grouped by term and field like the mention list (kogn-io/arknet#689); a
     * covered mention is grouped under its field set plus the references that cover it, so sources
     * covered by the same references share one group.
     */
    private void appendWeakBlock(final StringBuilder out, final List<WeakMention> weakMentions) {
        out.append("\n\nMentioned only in acceptance criteria or next to a covering reference "
                + "(hints, not counted):\n\n").append(LINE_LAYOUT).append(' ').append(HINT_LEGEND).append('\n');
        appendTermLines(out, weakMentions.stream().map(mention -> new TermSource(
                handleOf(mention.termIri(), mention.termHandle()), mention.termLabel(),
                fieldsOf(mention.fields()) + (mention.reason() == TraceabilityGraph.WeakReason.COVERED_BY_REFERENCE
                        ? " (covered by " + String.join(", ", mention.coveredBy()) + ")"
                        : ""),
                handleOf(mention.sourceIri(), mention.sourceHandle())))
                .toList());
    }

    /**
     * One rendered source of a mention: the term it names, the group it is listed under within that
     * term's line (its field set, for a hint plus its covering references), and itself.
     */
    private record TermSource(String termHandle, String termLabel, String group, String source) {
    }

    /**
     * Groups {@code sources} by term and appends one line per term - most sources first, then by
     * term code (numerically, so TERM-2 precedes TERM-10). Within a line the sources are grouped by
     * {@link TermSource#group()}, so a field name is written once per term rather than once per
     * source; groups and sources keep the order the check reported them in.
     */
    /** Orders codes by prefix, then by the running number as a number; falls back to text. */
    private static int compareCodes(final String left, final String right) {
        final int leftDash = left.lastIndexOf('-');
        final int rightDash = right.lastIndexOf('-');
        if (leftDash > 0 && rightDash > 0 && left.substring(0, leftDash).equals(right.substring(0, rightDash))
                && left.substring(leftDash + 1).matches("\\d{1,9}")
                && right.substring(rightDash + 1).matches("\\d{1,9}")) {
            return Integer.compare(Integer.parseInt(left.substring(leftDash + 1)),
                    Integer.parseInt(right.substring(rightDash + 1)));
        }
        return left.compareTo(right);
    }

    private static void appendTermLines(final StringBuilder out, final List<TermSource> sources) {
        final Map<String, List<TermSource>> byTerm = sources.stream()
                .collect(Collectors.groupingBy(TermSource::termHandle, LinkedHashMap::new, Collectors.toList()));
        byTerm.values().stream()
                .sorted(Comparator.comparingInt((List<TermSource> group) -> group.size()).reversed()
                        .thenComparing(group -> group.getFirst().termHandle(), StoreCheckRenderer::compareCodes))
                .forEach(group -> out.append("\n- ").append(group.getFirst().termHandle())
                        .append(' ').append(group.getFirst().termLabel())
                        .append(" (").append(group.size()).append("): ")
                        .append(group.stream()
                                .collect(Collectors.groupingBy(TermSource::group, LinkedHashMap::new,
                                        Collectors.mapping(TermSource::source, Collectors.joining(", "))))
                                .entrySet().stream()
                                .map(byFields -> byFields.getKey() + ": " + byFields.getValue())
                                .collect(Collectors.joining("; "))));
    }

    /**
     * Assembles the whole report from the sections that ran, so a caller selecting several checks
     * gets one document rather than a concatenation with no header.
     *
     * @param kinds    the checks that ran, in the order they ran
     * @param sections their rendered output, one per kind
     * @return the report
     */
    public String report(final List<StoreCheckKind> kinds, final List<String> sections) {
        Objects.requireNonNull(kinds, "kinds");
        Objects.requireNonNull(sections, "sections");
        return "store_check: " + kinds.stream().map(Enum::name).collect(Collectors.joining(", "))
                + "\n\n" + String.join("\n\n", sections);
    }

    /** A resource's business handle, or its shortened IRI when it has none - never an invented one. */
    private String handleOf(final Gap gap) {
        return gap.handle() != null ? gap.handle() : prefixes.toCurie(gap.subjectIri());
    }

    /** Same fallback as {@link #handleOf(Gap)}, for a handle already extracted from an {@link OrphanCheck} finding. */
    private String handleOf(final String iri, final String handle) {
        return handle != null ? handle : prefixes.toCurie(iri);
    }
}
