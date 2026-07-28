// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * A bounded context's whole point is the language inside it, so its vision statement is where
 * that language should be visible - and where a term the context claims but never uses shows up
 * as exactly that.
 */
class BoundedContextCardsTest {

    private static final ProjectId WORKSPACE = new ProjectId("bc-cards-test");
    private static final String ID = "https://w3id.org/arknet/id/";
    private static final ResourceId BESTELLUNG = ResourceId.of(ID + "term-1");
    private static final ResourceId LIEFERADRESSE = ResourceId.of(ID + "term-2");

    private static final Glossary GLOSSARY = Glossary.of(List.of(
            term(BESTELLUNG, "TERM-1", "Bestellung"),
            term(LIEFERADRESSE, "TERM-2", "Lieferadresse")));

    @Test
    void marksALinkedTermInsideTheDomainVision() {
        final BoundedContextCards cards = cardsFor(context(
                "Nimmt jede Bestellung auf.", List.of(BESTELLUNG)));

        assertThat(vision(cards).spans()).contains(
                new Span.TermLink("Bestellung", BESTELLUNG.value(), "TERM-1"));
    }

    /** A glossary word the vision names without an edge behind it is a gap, not a link. */
    @Test
    void marksAnUnlinkedGlossaryWordAsAGap() {
        final BoundedContextCards cards = cardsFor(context(
                "Nimmt jede Bestellung auf.", List.of()));

        assertThat(vision(cards).spans()).contains(
                new Span.TermGap("Bestellung", BESTELLUNG.value(), "TERM-1"));
    }

    /** Only language the vision does not name survives as a chip; the rest is already in the sentence. */
    @Test
    void listsOnlyTermsTheVisionDoesNotName() {
        final BoundedContextCards cards = cardsFor(context(
                "Nimmt jede Bestellung auf.", List.of(BESTELLUNG, LIEFERADRESSE)));

        final Block.Refs refs = (Block.Refs) block(cards, "Ubiquitous language (not named in the vision)");

        assertThat(refs.refs()).containsExactly(
                new Ref("Lieferadresse", "TERM-2", LIEFERADRESSE.value()));
    }

    @Test
    void dropsTheChipListWhenTheVisionNamesEveryLinkedTerm() {
        final BoundedContextCards cards = cardsFor(context(
                "Nimmt jede Bestellung auf.", List.of(BESTELLUNG)));

        assertThat(cards.section(WORKSPACE, GLOSSARY).cards().getFirst().blocks())
                .extracting(Block::label)
                .doesNotContain("Ubiquitous language", "Ubiquitous language (not named in the vision)");
    }

    private static RichText vision(final BoundedContextCards cards) {
        return ((Block.Prose) block(cards, "Domain vision")).text();
    }

    private static Block block(final BoundedContextCards cards, final String label) {
        return cards.section(WORKSPACE, GLOSSARY).cards().getFirst().blocks().stream()
                .filter(b -> b.label().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("no block " + label));
    }

    private static BoundedContextCards cardsFor(final BoundedContext context) {
        return new BoundedContextCards(projectId -> List.of(context));
    }

    private static BoundedContext context(final String vision, final List<ResourceId> linked) {
        return new BoundedContext(
                new BoundedContextId(ResourceId.of(ID + "bc-1")),
                new BoundedContextCode("BC-1"), "Ordering", vision,
                Subdomain.CORE_DOMAIN, null,
                linked.stream().map(TermRef::new).toList());
    }

    private static Term term(final ResourceId id, final String code, final String label) {
        return new Term(new TermId(id), new TermCode(code), label, "Definition von " + label + ".", null);
    }
}
