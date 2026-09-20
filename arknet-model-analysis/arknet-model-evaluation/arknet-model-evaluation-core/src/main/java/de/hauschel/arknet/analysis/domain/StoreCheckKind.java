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
    LANGUAGE,

    /**
     * Which role ({@code arkproc:Role}) and glossary term ({@code skos:Concept}) carry the same
     * name (kogn-io/arknet#512) - reported, never rejected: {@code role_add}/{@code term_add}
     * stay independent of each other.
     */
    ROLE_TERM_DUPLICATE,

    /**
     * Which main-flow use-case step no acceptance criterion stands behind (kogn-io/arknet#622) -
     * either because the step realises no requirement at all, or because no requirement it
     * realises carries an {@code arkreq:acceptanceCriterion}.
     */
    STEP_ACCEPTANCE,

    /**
     * Every orphaned artifact of the project (kogn-io/arknet#473, folding the former {@code
     * orphan_check} tool in here): a requirement no use case realises, a glossary term never
     * referenced, a requirement's/use case's/bounded context's/architecture decision's prose
     * naming a term without the matching edge, and a constraint no requirement or use case is
     * bound by. See {@link OrphanCheck}.
     */
    ORPHAN;

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
