// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
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

    private final ListRequirements requirements;

    /**
     * @param requirements the requirements context's list in-port
     */
    public RequirementCards(final ListRequirements requirements) {
        this.requirements = Objects.requireNonNull(requirements, "requirements");
    }

    /**
     * @param projectId the project to read
     * @param glossary    the project's glossary, for labelling and marking up references
     * @return the requirements section, ordered by business code
     */
    public ModelSection section(final ProjectId projectId, final Glossary glossary) {
        Objects.requireNonNull(glossary, "glossary");
        final List<ModelCard> cards = requirements.list(projectId).stream()
                .sorted(Comparator.comparing(requirement -> requirement.code().value()))
                .map(requirement -> card(requirement, glossary))
                .toList();
        return new ModelSection("Requirements", "requirements",
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
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        final List<String> texts = new ArrayList<>();
        texts.add(requirement.description());
        texts.addAll(requirement.acceptanceCriteria());

        final List<Block> blocks = new ArrayList<>();
        blocks.add(new Block.Prose("Description", glossary.markUp(requirement.description(), linked)));
        blocks.add(new Block.Bullets("Acceptance criteria", requirement.acceptanceCriteria().stream()
                .map(criterion -> glossary.markUp(criterion, linked))
                .toList()));
        if (requirement.qualityCategory() != null) {
            blocks.add(Block.Prose.plain("Quality category", requirement.qualityCategory()));
        }
        if (requirement.motivatedBy() != null) {
            blocks.add(new Block.Refs("Motivated by",
                    List.of(Ref.of(localName(requirement.motivatedBy()), requirement.motivatedBy()))));
        }
        addUnmentionedTerms(blocks, linked, glossary, texts);
        return new ModelCard(requirement.code().value(), requirement.title(),
                requirement.id().value().value(), badges, blocks);
    }

    /**
     * The linked terms the prose never names. A term the text does name is already visible as a
     * link inside the sentence, so repeating it as a chip would only pad the card; a term that
     * appears nowhere in the text is the opposite - invisible unless listed here, and worth a
     * second look, since a requirement is linked to language it does not use.
     */
    private static void addUnmentionedTerms(
            final List<Block> blocks,
            final Set<ResourceId> linked,
            final Glossary glossary,
            final List<String> texts) {
        if (linked.isEmpty()) {
            return;
        }
        final Set<ResourceId> mentioned = glossary.mentionedIn(texts);
        final List<Ref> rest = linked.stream()
                .filter(id -> !mentioned.contains(id))
                .map(glossary::ref)
                .toList();
        if (rest.isEmpty()) {
            return;
        }
        blocks.add(new Block.Refs(
                mentioned.isEmpty() ? "Uses terms" : "Uses terms (not named in the text)", rest));
    }

    /**
     * {@code arkreq:motivatedBy} points at an {@code arkreq:Goal} for which no aggregate exists
     * yet, so the requirement carries it as a plain IRI. Its local name is the best label
     * available; the IRI still links the reference to a card if the goal happens to be in the
     * store.
     */
    private static String localName(final String iri) {
        final int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut + 1 < iri.length() ? iri.substring(cut + 1) : iri;
    }
}
