// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.mcp;

import de.hauschel.arknet.actor.domain.Actor;

/**
 * Renders {@link Actor}s into the plain-text strings {@link ActorMcpTools} returns from its tool
 * calls - split out for the same reason {@code ConstraintPresenter} is split from
 * {@code ConstraintMcpTools}: the two carry independent reasons to change.
 *
 * <p>Needs no driving port at all: an {@link Actor} carries no cross-resource reference to resolve
 * for display, so unlike {@code BoundedContextMcpTools#format} there is nothing to batch-resolve
 * and no fallback to a bare IRI to arrange.</p>
 */
final class ActorPresenter {

    /** Renders a single actor; the description is omitted entirely when the actor carries none. */
    String format(final Actor actor) {
        final String description = actor.description() == null ? "" : ": " + actor.description();
        return "%s [%s] %s%s".formatted(actor.code().value(), actor.type(), actor.name(), description);
    }
}
