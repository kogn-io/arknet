// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.List;
import java.util.Objects;

/**
 * Thrown when a write would leave a project whose {@link Project#defaultLanguage()} is not one of
 * the languages it maintains ({@link Project#maintainedLanguages()}).
 *
 * <p>The two fields say different things - the default language is the <em>fallback</em> a call
 * that names no language lands in, the maintained set is the project's <em>commitment</em> about
 * which languages every multilingual field should carry - but they cannot contradict each other:
 * a fallback pointing outside the maintained set would silently write every unqualified call into
 * a language the project never undertook to keep, which is exactly the untracked incompleteness
 * the maintained set exists to make visible.</p>
 *
 * <p>Raised on both writes that can break it - setting the set and changing the default language -
 * because either half moving is enough to break the pair. An empty maintained set never violates
 * it (no commitment, nothing to contradict), and neither does a project with no default language
 * configured at all: there is then no fallback that could point anywhere.</p>
 */
public class DefaultLanguageNotMaintainedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String defaultLanguage;
    private final transient List<String> maintainedLanguages;

    /**
     * Creates the exception, naming both ways out - widen the set, or move the default into it -
     * because which of the two the caller meant is not decidable from the write alone.
     *
     * @param defaultLanguage     the default language that points outside the maintained set
     * @param maintainedLanguages the maintained set, never empty when this is thrown
     */
    public DefaultLanguageNotMaintainedException(String defaultLanguage, List<String> maintainedLanguages) {
        super("default language '" + Objects.requireNonNull(defaultLanguage, "defaultLanguage")
                + "' is not one of the languages this project maintains ("
                + String.join(", ", Objects.requireNonNull(maintainedLanguages, "maintainedLanguages"))
                + "). The default language is the fallback a call that names no language is written "
                + "and read under, so it has to point into the maintained set. Either add '"
                + defaultLanguage + "' to the maintained languages, or set the default language to "
                + "one of: " + String.join(", ", maintainedLanguages) + ".");
        this.defaultLanguage = defaultLanguage;
        this.maintainedLanguages = List.copyOf(maintainedLanguages);
    }

    /** @return the default language that points outside the maintained set */
    public String defaultLanguage() {
        return defaultLanguage;
    }

    /** @return the languages the project maintains */
    public List<String> maintainedLanguages() {
        return maintainedLanguages;
    }
}
