// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import java.util.List;
import java.util.Locale;

/**
 * The checks {@code store_check} can run. One tool with a selector rather than one tool per check
 * (kogn-io/arknet#412), for the same reason {@code store_overview}/{@code resource_get} are two
 * generic tools rather than one per bounded context: a check is a way of reading the
 * store, not a bounded context of its own, and a tool per check would grow the tool surface every
 * agent pays for on every call by one entry per rule.
 *
 * <p>{@link #LANGUAGE}, {@link #ROLE_TERM_DUPLICATE}, {@link #STEP_ACCEPTANCE} and {@link #ORPHAN}
 * exist today; whatever check-shaped tool follows folds in here too, rather than arriving as a new
 * tool name.</p>
 */
public enum StoreCheckKind {

    /**
     * Which fields do not carry every language the project undertakes to maintain
     * ({@code arkprj:maintainedLanguage}, kogn-io/arknet#412).
     */
    LANGUAGE("per field, whether every language the project maintains carries a language-tagged value.",
            "a field that carries no language-tagged literal at all (a single untagged value written "
                    + "before a project had a default language, or a field never written) is "
                    + "indistinguishable from a field that is simply not multilingual, and is not reported; "
                    + "whether one language's text is a current translation of another's. resource_get "
                    + "shows a resource's raw literals."),

    /**
     * Which role ({@code arkproc:Role}) and glossary term ({@code skos:Concept}) carry the same
     * name (kogn-io/arknet#512) - reported, never rejected: {@code role_add}/{@code term_add}
     * stay independent of each other.
     */
    ROLE_TERM_DUPLICATE("whether a role name and a glossary term's prefLabel are the same string, compared "
            + "case-insensitively and trimmed.",
            "synonyms and near-matches; two roles or two terms sharing a name; a role or term without a "
                    + "business code, which is skipped rather than named by a guessed handle."),

    /**
     * Which main-flow use-case step no acceptance criterion stands behind (kogn-io/arknet#622) -
     * either because the step realises no requirement at all, or because no requirement it
     * realises carries an {@code arkreq:acceptanceCriterion}.
     */
    STEP_ACCEPTANCE("per main-flow step, whether the path step -> arkreq:stepRealises -> requirement -> "
            + "arkreq:acceptanceCriterion exists.",
            "extension steps - they carry no arkreq:stepRealises edge at tool level "
                    + "(kogn-io/arknet#317), so they are out of scope rather than reported as a missing "
                    + "edge; whether a criterion actually covers the step it is reached from, which is a "
                    + "reading and not a check; and a use case without a business code of its own, which "
                    + "is skipped rather than named by a guessed handle."),

    /**
     * Every orphaned artifact of the project (kogn-io/arknet#473, folding the former {@code
     * orphan_check} tool in here): a requirement no use case satisfies or realises, a glossary term never
     * referenced, a requirement's/use case's/bounded context's/architecture decision's prose
     * naming a term without the matching edge, and a constraint no requirement or use case is
     * bound by. See {@link OrphanCheck}.
     */
    ORPHAN("the presence of edges: requirements no use case satisfies (oslc_rm:satisfies) or realises (arkreq:stepRealises), terms never referenced, text naming a "
            + "term without the matching edge, constraints bound by nothing.",
            "the text-mention match (\"Mentioned in text but not linked\") is literal and whole-word, "
                    + "not stem-based, so it also flags everyday words used in their ordinary sense (e.g. "
                    + "\"Rolle\", \"Begriff\", \"Projekt\") - a hit there is a reading hint for a human, "
                    + "not a finding that demands an edge; whether an existing edge is the right one.");

    private final String scope;
    private final String notSeen;

    StoreCheckKind(final String scope, final String notSeen) {
        this.scope = scope;
        this.notSeen = notSeen;
    }

    /** @return the one sentence naming what this check looks at, shown in its output. */
    public String scope() {
        return scope;
    }

    /**
     * @return what this check deliberately does not look at, shown in its output so an empty or
     *         short result is not read as "reviewed"
     */
    public String notSeen() {
        return notSeen;
    }

    /**
     * Parses one caller-supplied selector, case-insensitively.
     *
     * @param value the raw tool argument
     * @return the matching check
     * @throws IllegalArgumentException naming every allowed value - an agent that guessed wrong
     *                                  must be told what it may pass, not merely that it failed
     */
    public static StoreCheckKind parse(final String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown check '" + value + "': expected one of "
                    + String.join(", ", names()) + ".", e);
        }
    }

    /** @return every check name, for an error message or a tool description. */
    public static List<String> names() {
        return List.of(values()).stream().map(Enum::name).toList();
    }
}
