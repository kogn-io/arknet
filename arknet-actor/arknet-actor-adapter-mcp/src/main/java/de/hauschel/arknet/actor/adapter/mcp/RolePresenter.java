// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.mcp;

import java.util.List;
import java.util.stream.Collectors;

import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.application.port.in.RoleDetail.FilledByActor;
import de.hauschel.arknet.actor.domain.Role;

/**
 * Renders {@link RoleDetail}s into the plain-text strings {@link RoleMcpTools} returns from its
 * tool calls - split out for the same reason {@link ActorPresenter} is split from
 * {@link ActorMcpTools}.
 *
 * <p>Needs no driving port of its own: {@link RoleDetail} already carries its {@code filledBy}
 * occupants resolved to code and name (by {@code RoleService}), so there is nothing left for this
 * class to batch-resolve.</p>
 */
final class RolePresenter {

    /**
     * Renders a single role: code, name, the description when present, and the occupancy - either
     * every occupant's code and name, or {@code (unfilled)} when the role carries none.
     */
    String format(final RoleDetail detail) {
        final Role role = detail.role();
        final String description = role.description() == null ? "" : ": " + role.description();
        final String filledBy = " - " + filledByLine(detail.filledByActors());
        return "%s %s%s%s".formatted(role.code().value(), role.name(), description, filledBy);
    }

    private static String filledByLine(final List<FilledByActor> filledByActors) {
        if (filledByActors.isEmpty()) {
            return "unfilled";
        }
        return "filled by: " + filledByActors.stream()
                .map(actor -> "%s (%s)".formatted(actor.code().value(), actor.name()))
                .collect(Collectors.joining(", "));
    }
}
