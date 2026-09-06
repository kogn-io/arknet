// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.actor.application.port.in.ListRoles;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.application.port.in.RoleDetail.FilledByActor;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Builds the report's role cards from the actor context's {@link ListRoles} in-port - the second
 * resource type of the actor hexagon (ADR-37/kogn-io/arknet#405), alongside {@link ActorCards}.
 *
 * <p><strong>No glossary mark-up, no borrowed port.</strong> A role carries no reference to a term,
 * a requirement or a bounded context in this scope, mirroring {@link ActorCards}' own reasoning -
 * {@code filledBy} is this hexagon's own edge and is already resolved to code and name by
 * {@code RoleService}, so there is nothing left to batch-resolve here either.</p>
 *
 * <p>Unlike {@link ActorCards}, {@code displayLocale} is a real parameter: a role's {@code name}/
 * {@code description} are language-tagged, mirroring {@link ConstraintCards}.</p>
 */
public final class RoleCards {

    /** The section title, shared with {@link ModelViews}' failure message for this section. */
    public static final String SECTION_TITLE = "Roles";

    private final ListRoles roles;

    /**
     * @param roles the actor context's role list in-port
     */
    public RoleCards(final ListRoles roles) {
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    /**
     * @param projectId     the project to read
     * @param displayLocale the resolved project's own configured default display language (BCP-47
     *                      tag), or {@code null} if it has none - passed straight through to
     *                      {@code role_list}'s own port, mirroring {@link ConstraintCards}
     * @return the role section, ordered by business code
     */
    public ModelSection section(final ProjectId projectId, final String displayLocale) {
        final List<ModelCard> cards = roles.list(projectId, displayLocale).stream()
                .sorted(Comparator.comparing(detail -> detail.role().code().value(), BusinessCodes.ORDER))
                .map(RoleCards::card)
                .toList();
        return new ModelSection(SECTION_TITLE, "roles",
                "the named functions someone or something may act or hold an interest in, independent "
                        + "of who currently fills them", cards);
    }

    private static ModelCard card(final RoleDetail detail) {
        final List<Block> blocks = new ArrayList<>();
        if (detail.role().description() != null) {
            blocks.add(ProseMarkdown.prose("Description", detail.role().description(), RichText::plain));
        }
        blocks.add(ProseMarkdown.prose("Filled by", filledByText(detail.filledByActors()), RichText::plain));
        return new ModelCard(detail.role().code().value(), detail.role().name(),
                detail.role().id().value().value(), List.of(), blocks);
    }

    private static String filledByText(final List<FilledByActor> filledByActors) {
        if (filledByActors.isEmpty()) {
            return "(unfilled)";
        }
        return filledByActors.stream()
                .map(actor -> "%s (%s)".formatted(actor.code().value(), actor.name()))
                .collect(Collectors.joining(", "));
    }
}
