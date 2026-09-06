// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Builds the report's requirement cards from the requirements context's read in-port.
 *
 * <p>Type, status and MoSCoW priority become badges rather than rows of vocabulary IRIs; the
 * acceptance criteria - the part a reviewer actually checks against - become a list instead of
 * {@code n} repeated {@code arkreq:acceptanceCriterion} literals.</p>
 *
 * <p><strong>The glossary in the sentence, not beside it.</strong> A requirement talks about
 * the ubiquitous language in prose while the model records it as {@code arkreq:usesTerm}
 * edges. Rendering the edges as a chip list under the text left the reader to match the two up
 * by eye - and hid the more interesting fact, that a text can name a term nothing links to.
 * The description and acceptance criteria are therefore marked up through {@link Glossary}: a
 * mention backed by an edge is a link, a mention without one is shown as a gap. The chip list
 * survives only for edges whose term the text never names, which is the one thing marking up
 * cannot show.</p>
 */
public final class RequirementCards {

    /** The section title, shared with {@link ModelViews}' failure message for this section. */
    public static final String SECTION_TITLE = "Requirements";

    /**
     * The acceptance-criteria block's label, shared with {@link HtmlReportRenderer#langSources}
     * so it can pair this specific {@link Block.Bullets} list with its own sub-resources rather
     * than another {@link Block.Bullets} list the same card might carry (issue #358).
     */
    public static final String ACCEPTANCE_CRITERIA_LABEL = "Acceptance criteria";

    private final ListRequirements requirements;

    /**
     * @param requirements the requirements context's list in-port
     */
    public RequirementCards(final ListRequirements requirements) {
        this.requirements = Objects.requireNonNull(requirements, "requirements");
    }

    /**
     * @param projectId     the project to read
     * @param displayLocale the resolved project's own configured default display language
     *                      (BCP-47 tag), or {@code null} if it has none - passed straight through
     *                      to {@code req_list}'s own port so the report honours the same project
     *                      default {@code req_list}/{@code term_list} already do (issue #281)
     * @param glossary    the project's glossary, for labelling and marking up references
     * @return the requirements section, ordered by business code
     */
    public ModelSection section(final ProjectId projectId, final String displayLocale, final Glossary glossary) {
        Objects.requireNonNull(glossary, "glossary");
        final List<ModelCard> cards = requirements.list(projectId, displayLocale).stream()
                .sorted(Comparator.comparing(requirement -> requirement.code().value(), BusinessCodes.ORDER))
                .map(requirement -> card(requirement, glossary))
                .toList();
        return new ModelSection(SECTION_TITLE, "requirements",
                "what the system shall do, and what counts as done", cards);
    }

    private static ModelCard card(final Requirement requirement, final Glossary glossary) {
        final List<Badge> badges = new ArrayList<>();
        badges.add(new Badge(Badge.Kind.Known.TYPE, requirement.type().idPrefix()));
        badges.add(new Badge(Badge.Kind.Known.STATUS, Labels.humanise(requirement.status().name())));
        if (requirement.priority() != null) {
            badges.add(new Badge(Badge.Kind.Known.PRIORITY, Labels.humanise(requirement.priority().name())));
        }

        final Set<ResourceId> linked = requirement.usesTerms().stream()
                .map(TermRef::value)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final List<String> texts = new ArrayList<>();
        texts.add(requirement.description());
        if (requirement.rationale() != null) {
            texts.add(requirement.rationale());
        }
        requirement.acceptanceCriteria().stream().map(AcceptanceCriterion::text).forEach(texts::add);

        final List<Block> blocks = new ArrayList<>();
        blocks.add(ProseMarkdown.prose("Description", requirement.description(),
                text -> glossary.markUp(text, linked)));
        if (requirement.rationale() != null) {
            // Right after the statement it explains, and glossary-marked like every other prose
            // block - a reason names the same domain terms the requirement itself does (issue
            // #321). Absent for a requirement whose reason nobody recorded: the field is
            // optional, so no block beats an empty one.
            blocks.add(ProseMarkdown.prose("Rationale", requirement.rationale(),
                    text -> glossary.markUp(text, linked)));
        }
        blocks.add(new Block.Bullets(ACCEPTANCE_CRITERIA_LABEL, requirement.acceptanceCriteria().stream()
                .map(criterion -> new BulletItem(criterion.position(),
                        ProseMarkdown.inline(criterion.text(), text -> glossary.markUp(text, linked))))
                .toList()));
        if (requirement.qualityCategory() != null) {
            blocks.add(Block.Prose.plain("Quality category", requirement.qualityCategory()));
        }
        UnmentionedTerms.addTo(blocks, linked, glossary, texts, "Uses terms", "not named in the text");
        return new ModelCard(requirement.code().value(), requirement.title(),
                requirement.id().value().value(), badges, blocks);
    }
}
