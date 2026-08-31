// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ListConstraints;
import de.hauschel.arknet.req.domain.Constraint;

/**
 * Builds the report's constraint cards from the requirements context's {@link ListConstraints}
 * in-port.
 *
 * <p>Issue #390: {@code constraint_add}/{@code constraint_update} have carried the Markdown-subset
 * promise (issue #388) since it was hung on every writing tool, but until this class existed a
 * constraint fell into the generic raw-triple view like any resource no bounded context claims -
 * the one place a tool promised a data shape the report's only output channel did not honour.</p>
 *
 * <p><strong>No glossary mark-up, no borrowed port, no {@code constrainedBy} back-reference.</strong>
 * A constraint carries no {@code arkreq:usesTerm} edge of its own, mirroring {@link ActorCards}'
 * reasoning for the same absence. The owning side of {@code oslc_rm:constrainedBy} is the
 * requirement or use case that names this constraint, not the other way round - and that edge is
 * deliberately unrendered everywhere in this report already (see {@link UseCaseCards}), so this
 * class does not become the first place it appears.</p>
 */
public final class ConstraintCards {

    /** The section title, shared with {@link ModelViews}' failure message for this section. */
    public static final String SECTION_TITLE = "Constraints";

    private final ListConstraints constraints;

    /**
     * @param constraints the requirements context's constraint list in-port
     */
    public ConstraintCards(final ListConstraints constraints) {
        this.constraints = Objects.requireNonNull(constraints, "constraints");
    }

    /**
     * @param projectId     the project to read
     * @param displayLocale the resolved project's own configured default display language
     *                      (BCP-47 tag), or {@code null} if it has none - passed straight through
     *                      to {@code constraint_list}'s own port, mirroring {@link RequirementCards}
     * @return the constraint section, ordered by business code
     */
    public ModelSection section(final ProjectId projectId, final String displayLocale) {
        final List<ModelCard> cards = constraints.list(projectId, displayLocale).stream()
                .sorted(Comparator.comparing(constraint -> constraint.code().value(), BusinessCodes.ORDER))
                .map(ConstraintCards::card)
                .toList();
        return new ModelSection(SECTION_TITLE, "constraints",
                "the non-negotiable boundaries the solution space must stay inside", cards);
    }

    private static ModelCard card(final Constraint constraint) {
        final List<Badge> badges = List.of(new Badge(Badge.Kind.Known.TYPE, constraint.type().idPrefix()));
        final List<Block> blocks = List.of(
                ProseMarkdown.prose("Statement", constraint.statement(), RichText::plain));
        return new ModelCard(constraint.code().value(), constraint.title(),
                constraint.id().value().value(), badges, blocks);
    }
}
