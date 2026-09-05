// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.mcp.check.LanguageGapCheck.Gap;
import de.hauschel.arknet.mcp.store.Prefixes;

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
}
