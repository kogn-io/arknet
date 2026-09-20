// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.adapter.mcp;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.analysis.domain.LanguageGapCheck.Gap;
import de.hauschel.arknet.analysis.domain.OrphanCheck;
import de.hauschel.arknet.analysis.domain.OrphanCheck.MentionFinding;
import de.hauschel.arknet.analysis.domain.OrphanCheck.ResourceFinding;
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

    /**
     * Named in the output, not only in the tool description: an empty section that does not say
     * what it could not look at reads as "reviewed", which is the one misreading a check must not
     * invite - the same discipline {@code adr_check} follows with its own "not checked here" list.
     */
    static final String BLIND_SPOT =
            "Not seen here: a field that carries no language-tagged literal at all (a single "
                    + "untagged value written before a project had a default language, or a field never "
                    + "written) is indistinguishable from a field that is simply not multilingual, and "
                    + "is not reported. resource_get shows a resource's raw literals.";

    /**
     * The same discipline for {@code STEP_ACCEPTANCE}: what the check deliberately does not look
     * at travels in its output, not only in a {@code CLAUDE.md}.
     */
    static final String STEP_BLIND_SPOT =
            "Not seen here: extension steps - they carry no arkreq:stepRealises edge at tool level "
                    + "(kogn-io/arknet#317), so they are out of scope rather than reported as a missing "
                    + "edge; whether a criterion actually covers the step it is reached from, which is a "
                    + "reading and not a check; and a use case without a business code of its own, which "
                    + "is skipped rather than named by a guessed handle.";

    /**
     * The same discipline for {@code ORPHAN}: the mention match is literal and whole-word, not
     * stem-based, so "Mentioned in text but not linked" also flags everyday words used in their
     * ordinary sense - a hit there is a reading hint for a human, not a finding that demands an edge.
     */
    static final String ORPHAN_BLIND_SPOT =
            "Not seen here: the text-mention match (\"Mentioned in text but not linked\") is literal "
                    + "and whole-word, not stem-based, so it also flags everyday words used in their "
                    + "ordinary sense (e.g. \"Rolle\", \"Begriff\", \"Projekt\") - a hit there is a "
                    + "reading hint for a human, not a finding that demands an edge.";

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
                    + "project_update(languages=[\"de\",\"en\"]), then run this check again.";
        }
        final StringBuilder rendered = new StringBuilder("LANGUAGE: maintained languages ")
                .append(String.join(", ", maintainedLanguages)).append(".");
        if (gaps.isEmpty()) {
            return rendered.append(" No field is missing one of them.\n\n").append(BLIND_SPOT).toString();
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
        return rendered.append("\n\n").append(BLIND_SPOT).toString();
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
            return "ROLE_TERM_DUPLICATE: no role and glossary term share a name.";
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
        return rendered.toString();
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

    /**
     * Renders the orphan section (kogn-io/arknet#473, folding the former {@code orphan_check} tool
     * in here): four sub-tables, one per finding list, each shown only when it is non-empty - the
     * same discipline {@link #stepAcceptanceSection} follows for its two cases.
     *
     * @param result the four findings lists, already computed by {@link OrphanCheck#run}
     * @return the section text
     */
    public String orphanSection(final OrphanCheck.Result result) {
        Objects.requireNonNull(result, "result");
        if (result.total() == 0) {
            return "ORPHAN: no orphaned requirements, unreferenced terms, unlinked mentions or unattached "
                    + "constraints found.\n\n" + ORPHAN_BLIND_SPOT;
        }
        final StringBuilder rendered = new StringBuilder("ORPHAN: ")
                .append(result.orphanRequirements().size())
                .append(result.orphanRequirements().size() == 1 ? " requirement without" : " requirements without")
                .append(" a realising use case, ")
                .append(result.orphanTerms().size())
                .append(result.orphanTerms().size() == 1 ? " term" : " terms").append(" never referenced, ")
                .append(result.unlinkedMentions().size())
                .append(result.unlinkedMentions().size() == 1 ? " unlinked mention" : " unlinked mentions")
                .append(", ").append(result.orphanConstraints().size())
                .append(result.orphanConstraints().size() == 1 ? " constraint" : " constraints")
                .append(" not attached to any requirement or use case.");
        if (!result.orphanRequirements().isEmpty()) {
            rendered.append("\n\nRequirements without a realising use case:\n\n");
            appendResourceTable(rendered, result.orphanRequirements());
        }
        if (!result.orphanTerms().isEmpty()) {
            rendered.append("\n\nTerms never referenced:\n\n");
            appendResourceTable(rendered, result.orphanTerms());
        }
        if (!result.unlinkedMentions().isEmpty()) {
            rendered.append("\n\nMentioned in text but not linked:\n\n");
            appendMentionTable(rendered, result.unlinkedMentions());
        }
        if (!result.orphanConstraints().isEmpty()) {
            rendered.append("\n\nConstraints not attached to any requirement or use case:\n\n");
            appendResourceTable(rendered, result.orphanConstraints());
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

    private void appendMentionTable(final StringBuilder out, final List<MentionFinding> mentions) {
        out.append("| Source | Term | Label | Missing edge |\n| --- | --- | --- | --- |");
        for (final MentionFinding mention : mentions) {
            out.append("\n| ").append(handleOf(mention.sourceIri(), mention.sourceHandle()))
                    .append(" | ").append(handleOf(mention.termIri(), mention.termHandle()))
                    .append(" | ").append(mention.termLabel())
                    .append(" | ").append(mention.edgeLocalName())
                    .append(" |");
        }
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
