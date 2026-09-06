// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a language-scoped {@code term_update} supplies a preferred label that differs from
 * the label the term already carries (kogn-io/arknet#502, FR-10): a glossary term is the same word
 * under every language tag - only its definition is translated - so a label written under one
 * explicit language must be that word, not a translation of it.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP
 * tools - surface the message, which names the label the term already carries and the two ways
 * forward (translate the definition alone, or rename the term under every language at once by
 * supplying the new label without a language). Only {@code term_update} can trigger this - a
 * brand-new term minted by {@code term_add} has no existing label to differ from.</p>
 */
public class TermLabelMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient TermCode code;
    private final transient String rejectedLabel;
    private final transient String language;
    private final transient List<String> existingLabels;

    /**
     * Creates the exception.
     *
     * @param projectId      the project the term lives in
     * @param code           the term the caller tried to correct
     * @param rejectedLabel  the label the caller supplied
     * @param language       the BCP-47 tag the caller supplied it under
     * @param existingLabels the distinct label(s) the term already carries - one, unless the store
     *                       holds a term written before the rule was enforced
     */
    public TermLabelMismatchException(ProjectId projectId, TermCode code, String rejectedLabel, String language,
            List<String> existingLabels) {
        super(message(projectId, code, rejectedLabel, language, existingLabels));
        this.projectId = projectId;
        this.code = code;
        this.rejectedLabel = rejectedLabel;
        this.language = language;
        this.existingLabels = List.copyOf(existingLabels);
    }

    private static String message(ProjectId projectId, TermCode code, String rejectedLabel, String language,
            List<String> existingLabels) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(rejectedLabel, "rejectedLabel");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(existingLabels, "existingLabels");
        String existing = existingLabels.stream()
                .map(label -> "\"" + label + "\"")
                .reduce((a, b) -> a + " / " + b)
                .orElse("(none)");
        return "term " + code.value() + " in project " + projectId.value() + " already carries the label "
                + existing + " - a glossary term is the same word under every language, only its definition is "
                + "translated, so \"" + rejectedLabel + "\" under @" + language + " is not accepted. To translate, "
                + "supply the definition alone (omit label). To rename the term under every language at once, "
                + "supply the new label without a language.";
    }

    /** @return the project the term lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the term the caller tried to correct */
    public TermCode code() {
        return code;
    }

    /** @return the label the caller supplied */
    public String rejectedLabel() {
        return rejectedLabel;
    }

    /** @return the BCP-47 tag the caller supplied the label under */
    public String language() {
        return language;
    }

    /** @return the distinct label(s) the term already carries */
    public List<String> existingLabels() {
        return existingLabels;
    }
}
