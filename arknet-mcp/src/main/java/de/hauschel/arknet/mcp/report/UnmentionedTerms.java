// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.List;
import java.util.Set;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * The "linked terms the prose never names" block both {@link RequirementCards} and
 * {@link BoundedContextCards} append to their cards (issue #103): a term the text does name is
 * already visible as a link inside the sentence, so repeating it as a chip would only pad the
 * card; a term that appears nowhere in the text is the opposite - invisible unless listed here,
 * and worth a second look, since the resource is linked to language it does not use.
 */
final class UnmentionedTerms {

    private UnmentionedTerms() {
    }

    /**
     * @param blocks            the card's blocks, appended to if any unmentioned term remains
     * @param linked            the resource's linked term identities
     * @param glossary          the project's glossary
     * @param texts             the prose already scanned for mentions (e.g. description and
     *                          acceptance criteria, or a domain vision)
     * @param sectionTitle      the block title when no linked term is named in {@code texts} at
     *                          all (e.g. {@code "Uses terms"})
     * @param unmentionedSuffix appended in parentheses when some, but not all, linked terms are
     *                          named (e.g. {@code "not named in the text"})
     */
    static void addTo(
            final List<Block> blocks,
            final Set<ResourceId> linked,
            final Glossary glossary,
            final List<String> texts,
            final String sectionTitle,
            final String unmentionedSuffix) {
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
                mentioned.isEmpty() ? sectionTitle : sectionTitle + " (" + unmentionedSuffix + ")", rest));
    }
}
