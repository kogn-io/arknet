// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.ul.domain.Term;

/**
 * Builds the report's glossary cards from the {@link Glossary} the report read once.
 *
 * <p>A term is a plain SKOS concept - since issue #336 it carries no actor facet any more; an
 * actor is its own resource type in {@code arknet-actor}'s register, rendered separately by
 * {@link ActorCards}.</p>
 *
 * <p>Unlike the other card builders this one holds no in-port: every term it renders is already
 * in the glossary the report needs anyway for labelling chips and finding mentions in prose.
 * Reading the list a second time would only risk showing a different glossary than the one the
 * rest of the report was built against.</p>
 */
public final class TermCards {

    /** The section title, shared with {@link ModelViews}' failure message for this section. */
    public static final String SECTION_TITLE = "Glossary";

    private TermCards() {
    }

    /**
     * @param glossary the project's glossary
     * @return the glossary section, ordered by preferred label
     */
    public static ModelSection section(final Glossary glossary) {
        Objects.requireNonNull(glossary, "glossary");
        final List<ModelCard> cards = glossary.terms().stream()
                .sorted(Comparator.comparing(Term::prefLabel, String.CASE_INSENSITIVE_ORDER))
                .map(TermCards::card)
                .toList();
        return new ModelSection(SECTION_TITLE, "glossary",
                "the ubiquitous language - one agreed meaning per term", cards);
    }

    private static ModelCard card(final Term term) {
        final List<Block> blocks = new ArrayList<>();
        blocks.add(Block.Prose.plain("Definition", term.definition()));
        return new ModelCard(term.code().value(), term.prefLabel(), term.id().value().value(), List.of(), blocks);
    }
}
