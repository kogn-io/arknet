// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import de.hauschel.arknet.req.domain.Constraint;

/**
 * Renders {@link Constraint}s into the plain-text strings {@link ConstraintMcpTools} returns from
 * its tool calls - split out for the same reason {@link RequirementPresenter} is split from
 * {@link RequirementMcpTools}: the two carry independent reasons to change.
 *
 * <p>Far simpler than {@link RequirementPresenter}: a {@link Constraint} carries no cross-resource
 * references of its own to resolve for display (unlike a {@link de.hauschel.arknet.req.domain.Requirement}'s
 * {@code usesTerms}/{@code constrainedBy}), so this class needs no driving port at all.</p>
 */
final class ConstraintPresenter {

    /** Renders a single constraint. */
    String format(final Constraint c) {
        return "%s [%s] %s: %s".formatted(c.code().value(), c.type(), c.title(), c.statement());
    }
}
