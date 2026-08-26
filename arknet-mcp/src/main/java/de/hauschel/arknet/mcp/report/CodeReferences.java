// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.hauschel.arknet.mcp.mention.LabelMentions;

/**
 * Turns a business code written into someone's prose ("see ADR-3", "supersedes FR-12") into a
 * link to that resource's own card.
 *
 * <p><strong>Why this is a separate pass over finished sections.</strong> A glossary mention is
 * marked up by the card builder that owns the text, because only that builder knows which term
 * edges the element carries ({@link Glossary#markUp}). A code reference is the opposite case:
 * the code names a resource of a <em>different</em> bounded context, and no builder can see
 * beyond its own. Running once over the assembled sections is what lets an ADR's prose reach a
 * requirement's card without the ADR hexagon and the requirements hexagon learning about each
 * other - the same reason {@link ModelViews} assembles rather than the builders calling one
 * another.</p>
 *
 * <p><strong>Only codes that actually have a card are linked.</strong> The matcher is built from
 * the cards in hand, so a text naming {@code ADR-99} in a project whose decisions stop at 20
 * stays plain text rather than becoming a link into nothing. The report never claims a resource
 * exists because a sentence mentioned it - the same discipline {@link Span.TermGap} keeps for
 * the glossary, only the honest answer here is "this is prose", not "this is a gap": a code in a
 * sentence is not an edge the model was supposed to carry.</p>
 *
 * <p><strong>A card never links to itself.</strong> ADR-7's own text saying "unlike ADR-7" would
 * render as a link back to the card the reader is already on; that is noise, not navigation.</p>
 */
final class CodeReferences {

    private CodeReferences() {
    }

    /**
     * @param sections the assembled sections, in reading order
     * @return the same sections with every prose run's code mentions marked up; input untouched
     */
    static List<ModelSection> markUp(final List<ModelSection> sections) {
        Objects.requireNonNull(sections, "sections");
        final Map<String, Target> targets = targets(sections);
        if (targets.isEmpty()) {
            return sections;
        }
        final LabelMentions<Target> mentions = LabelMentions.of(targets.values(), Target::code);
        return sections.stream().map(section -> new ModelSection(section.title(), section.id(), section.subtitle(),
                section.cards().stream().map(card -> markUp(card, mentions)).toList())).toList();
    }

    /** Every carded resource by its business code; a code appearing twice keeps its first card. */
    private static Map<String, Target> targets(final List<ModelSection> sections) {
        final Map<String, Target> byCode = new LinkedHashMap<>();
        for (final ModelSection section : sections) {
            for (final ModelCard card : section.cards()) {
                if (!card.code().isBlank()) {
                    byCode.putIfAbsent(card.code(), new Target(card.code(), card.iri(), card.title()));
                }
            }
        }
        return byCode;
    }

    private static ModelCard markUp(final ModelCard card, final LabelMentions<Target> mentions) {
        return new ModelCard(card.code(), card.title(), card.iri(), card.badges(),
                card.blocks().stream().map(block -> markUp(block, mentions, card.code())).toList());
    }

    private static Block markUp(final Block block, final LabelMentions<Target> mentions, final String self) {
        return switch (block) {
            case Block.Prose prose -> new Block.Prose(prose.label(), markUp(prose.text(), mentions, self));
            case Block.Bullets bullets -> new Block.Bullets(bullets.label(), bullets.items().stream()
                    .map(item -> new BulletItem(item.position(), markUp(item.text(), mentions, self),
                            item.badge(), item.caption()))
                    .toList());
            case Block.Flow flow -> new Block.Flow(flow.label(), flow.steps().stream()
                    .map(step -> new FlowStep(step.position(), markUp(step.text(), mentions, self), step.realises()))
                    .toList());
            case Block.Refs refs -> refs;
        };
    }

    /**
     * Marks up the plain runs of one text, leaving every span a card builder already recognised
     * as it is - a glossary mention stays a glossary mention even if its label happens to read
     * like a code.
     */
    private static RichText markUp(final RichText text, final LabelMentions<Target> mentions, final String self) {
        final List<Span> spans = new ArrayList<>();
        boolean changed = false;
        for (final Span span : text.spans()) {
            if (!(span instanceof Span.Plain plain)) {
                spans.add(span);
                continue;
            }
            final List<Span> split = split(plain.text(), mentions, self);
            changed |= split.size() > 1;
            spans.addAll(split);
        }
        return changed ? new RichText(spans) : text;
    }

    private static List<Span> split(final String text, final LabelMentions<Target> mentions, final String self) {
        final List<LabelMentions.Mention<Target>> found = mentions.in(text).stream()
                .filter(mention -> !mention.item().code().equals(self))
                .toList();
        if (found.isEmpty()) {
            return List.of(new Span.Plain(text));
        }
        final List<Span> spans = new ArrayList<>();
        int cursor = 0;
        for (final LabelMentions.Mention<Target> mention : found) {
            if (mention.start() > cursor) {
                spans.add(new Span.Plain(text.substring(cursor, mention.start())));
            }
            final Target target = mention.item();
            spans.add(new Span.CodeRef(text.substring(mention.start(), mention.end()),
                    target.iri(), target.code(), target.title()));
            cursor = mention.end();
        }
        if (cursor < text.length()) {
            spans.add(new Span.Plain(text.substring(cursor)));
        }
        return spans;
    }

    /**
     * @param code  the business code as its card shows it (e.g. {@code ADR-3})
     * @param iri   the card's subject IRI, so the renderer can anchor the link
     * @param title the card's title, shown as the link's tooltip - the point of the exercise: a
     *              reader learns what ADR-3 <em>is</em> without leaving the sentence
     */
    private record Target(String code, String iri, String title) {
    }
}
