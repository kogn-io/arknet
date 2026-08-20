// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.actor.application.port.in.ListActors;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Builds the report's actor cards from the actor context's {@link ListActors} in-port.
 *
 * <p>Since issue #336 an actor is its own resource type in {@code arknet-actor}'s register, no
 * longer a facet on a glossary term - {@link TermCards} therefore no longer carries an actor
 * badge, and this class is the actor's own card builder instead, mirroring
 * {@link BoundedContextCards}. The kind ({@code HUMAN}/{@code SYSTEM}/{@code LEGAL}/
 * {@code GROUP}) becomes a badge, reusing {@link Badge.Kind.Known#ACTOR} - the same pill style
 * the old term-facet badge rendered with - and the optional description becomes a prose
 * block.</p>
 *
 * <p><strong>No glossary mark-up, no borrowed port.</strong> An actor carries no reference to a
 * term, a requirement or a bounded context in this scope (see {@code ActorMcpTools}'s own
 * "No borrowed neighbour port" note) and its name/description are plain, untagged literals - so
 * unlike {@link BoundedContextCards} or {@link RequirementCards} there is no prose to mark up
 * against the {@link Glossary} and no neighbour hexagon's read in-port to borrow.</p>
 */
public final class ActorCards {

    /** The section title, shared with {@link ModelViews}' failure message for this section. */
    public static final String SECTION_TITLE = "Actors";

    private final ListActors actors;

    /**
     * @param actors the actor context's list in-port
     */
    public ActorCards(final ListActors actors) {
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    /**
     * @param projectId the project to read
     * @return the actor section, ordered by business code
     */
    public ModelSection section(final ProjectId projectId) {
        final List<ModelCard> cards = actors.list(projectId).stream()
                .sorted(Comparator.comparing(actor -> actor.code().value(), BusinessCodes.ORDER))
                .map(ActorCards::card)
                .toList();
        return new ModelSection(SECTION_TITLE, "actors",
                "who or what acts on the system, or holds an interest in it", cards);
    }

    private static ModelCard card(final Actor actor) {
        final List<Badge> badges = List.of(new Badge(Badge.Kind.Known.ACTOR, Labels.humanise(actor.type().name())));
        final List<Block> blocks = new ArrayList<>();
        if (actor.description() != null) {
            blocks.add(Block.Prose.plain("Description", actor.description()));
        }
        return new ModelCard(actor.code().value(), actor.name(), actor.id().value().value(), badges, blocks);
    }
}
