// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

/**
 * Kind of a {@link Constraint} (ISO 29148): a non-negotiable, externally-imposed boundary on the
 * solution space, distinguished by where it comes from.
 *
 * <ul>
 *   <li>{@link #TECHNICAL} - technology, platform or infrastructure choices.</li>
 *   <li>{@link #BUSINESS} - business, budget or organizational decisions.</li>
 *   <li>{@link #REGULATORY} - law, standard or contract.</li>
 * </ul>
 *
 * <p>The identity prefixes ({@code TCON}/{@code BCON}/{@code RCON}) are deliberately not
 * {@code TC}/{@code BC}/{@code RC}: {@code BC} is already this codebase's heavily-used
 * abbreviation for Bounded Context ({@code bc_add}, {@code arkddd:BoundedContext}, the module
 * {@code arknet-bounded-context}), and reusing it for a business constraint's code would be
 * confusing at the exact point (a human typing a code into a tool call) where clarity matters
 * most.</p>
 */
public enum ConstraintType {

    /** A constraint arising from technology, platform, or infrastructure choices; prefixed {@code TCON-}. */
    TECHNICAL("TCON"),

    /** A constraint arising from business, budget, or organizational decisions; prefixed {@code BCON-}. */
    BUSINESS("BCON"),

    /** A constraint arising from law, standard or contract; prefixed {@code RCON-}. */
    REGULATORY("RCON");

    private final String idPrefix;

    ConstraintType(String idPrefix) {
        this.idPrefix = idPrefix;
    }

    /**
     * The identity prefix used for this type, e.g. {@code TCON} for a technical constraint.
     * Combined with a running number ({@code TCON-1}, {@code BCON-7}) by the application layer -
     * each subtype numbered independently, exactly like {@link RequirementType#idPrefix()}.
     *
     * @return the non-blank identity prefix
     */
    public String idPrefix() {
        return idPrefix;
    }
}
