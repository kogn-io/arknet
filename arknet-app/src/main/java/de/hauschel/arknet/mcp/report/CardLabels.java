// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

/**
 * The labels of the blocks the card builders put on their {@link ModelCard}s, kept in one place
 * because a renderer that picks blocks by purpose (e.g. {@link SpecificationRenderer}) matches
 * on them. Builder and renderer share the constant, so renaming a label cannot silently drop a
 * block from a renderer that selects by it.
 */
final class CardLabels {

    /** Free-text description of an actor, role or requirement. */
    static final String DESCRIPTION = "Description";
    /** A requirement's reason. */
    static final String RATIONALE = "Rationale";
    /** A requirement's quality category. */
    static final String QUALITY_CATEGORY = "Quality category";
    /** A constraint's statement. */
    static final String STATEMENT = "Statement";
    /** A glossary term's definition. */
    static final String DEFINITION = "Definition";
    /** The actors filling a role; a role nobody fills carries {@link #UNFILLED}. */
    static final String FILLED_BY = "Filled by";
    /** The text of {@link #FILLED_BY} for a role nobody fills. */
    static final String UNFILLED = "(unfilled)";
    /** A use case's goal. */
    static final String GOAL = "Goal";
    /** A use case's scope. */
    static final String SCOPE = "Scope";
    /** A use case's trigger. */
    static final String TRIGGER = "Trigger";
    /** A use case's primary role. */
    static final String PRIMARY_ROLE = "Primary role";
    /** A use case's supporting roles. */
    static final String SUPPORTING_ROLES = "Supporting roles";
    /** A use case's precondition. */
    static final String PRECONDITION = "Precondition";
    /** A use case's postcondition. */
    static final String POSTCONDITION = "Postcondition";
    /** A use case's ordered main flow. */
    static final String MAIN_FLOW = "Main flow";

    private CardLabels() {
    }
}
