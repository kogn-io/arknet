// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.Term;

/**
 * Builds the report's glossary cards from the ubiquitous-language context's read in-port.
 *
 * <p>A term is a SKOS concept plus an optional actor facet. In the generic view that facet was
 * a second {@code rdf:type} triple pointing at {@code arkproc:HumanActor} - here it is a badge
 * saying "Human actor", which is what the reader is looking for when they follow a use case's
 * primary actor into the glossary.</p>
 */
public final class TermCards {

    private final ListTerms terms;

    /**
     * @param terms the ubiquitous-language context's list in-port
     */
    public TermCards(final ListTerms terms) {
        this.terms = Objects.requireNonNull(terms, "terms");
    }

    /**
     * @param workspaceId the workspace to read
     * @return the glossary section, ordered by preferred label
     */
    public ModelSection section(final WorkspaceId workspaceId) {
        final List<ModelCard> cards = terms.list(workspaceId).stream()
                .sorted(Comparator.comparing(Term::prefLabel, String.CASE_INSENSITIVE_ORDER))
                .map(TermCards::card)
                .toList();
        return new ModelSection("Glossary", "glossary",
                "the ubiquitous language - one agreed meaning per term", cards);
    }

    private static ModelCard card(final Term term) {
        final List<Badge> badges = new ArrayList<>();
        final List<Block> blocks = new ArrayList<>();
        blocks.add(new Block.Prose("Definition", term.definition()));
        final ActorFacet facet = term.actorFacet();
        if (facet != null) {
            badges.add(new Badge("actor", Labels.humanise(facet.kind().name()) + " actor"));
            if (facet.role() != null) {
                blocks.add(new Block.Prose("Role", facet.role()));
            }
        }
        return new ModelCard(term.code().value(), term.prefLabel(), term.id().value().value(), badges, blocks);
    }
}
