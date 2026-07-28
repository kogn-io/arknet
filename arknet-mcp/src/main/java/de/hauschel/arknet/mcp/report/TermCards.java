// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.Term;

/**
 * Builds the report's glossary cards from the {@link Glossary} the report read once.
 *
 * <p>A term is a SKOS concept plus an optional actor facet. In the generic view that facet was
 * a second {@code rdf:type} triple pointing at {@code arkproc:HumanActor} - here it is a badge
 * saying "Human actor", which is what the reader is looking for when they follow a use case's
 * primary actor into the glossary.</p>
 *
 * <p>Unlike the other card builders this one holds no in-port: every term it renders is already
 * in the glossary the report needs anyway for labelling chips and finding mentions in prose.
 * Reading the list a second time would only risk showing a different glossary than the one the
 * rest of the report was built against.</p>
 */
public final class TermCards {

    private TermCards() {
    }

    /**
     * @param glossary the workspace's glossary
     * @return the glossary section, ordered by preferred label
     */
    public static ModelSection section(final Glossary glossary) {
        Objects.requireNonNull(glossary, "glossary");
        final List<ModelCard> cards = glossary.terms().stream()
                .sorted(Comparator.comparing(Term::prefLabel, String.CASE_INSENSITIVE_ORDER))
                .map(TermCards::card)
                .toList();
        return new ModelSection("Glossary", "glossary",
                "the ubiquitous language - one agreed meaning per term", cards);
    }

    private static ModelCard card(final Term term) {
        final List<Badge> badges = new ArrayList<>();
        final List<Block> blocks = new ArrayList<>();
        blocks.add(Block.Prose.plain("Definition", term.definition()));
        final ActorFacet facet = term.actorFacet();
        if (facet != null) {
            badges.add(new Badge("actor", Labels.humanise(facet.kind().name()) + " actor"));
            if (facet.role() != null) {
                blocks.add(Block.Prose.plain("Role", facet.role()));
            }
        }
        return new ModelCard(term.code().value(), term.prefLabel(), term.id().value().value(), badges, blocks);
    }
}
