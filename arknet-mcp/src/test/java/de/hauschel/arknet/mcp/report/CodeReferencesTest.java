// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** A code written into prose reaches the card it names - and only when that card exists. */
class CodeReferencesTest {

    private static final String ID = "https://w3id.org/arknet/id/";

    @Test
    void linksACodeThatNamesACardInAnotherSection() {
        final List<ModelSection> marked = CodeReferences.markUp(List.of(
                section("Architecture decisions", card("ADR-1", "Scope frame", "the actor identity (see ADR-3)")),
                section("More decisions", card("ADR-3", "Actor identity", "unrelated text"))));

        assertThat(spansOf(marked.get(0))).containsExactly(
                new Span.Plain("the actor identity (see "),
                new Span.CodeRef("ADR-3", ID + "adr-3", "ADR-3", "Actor identity"),
                new Span.Plain(")"));
    }

    @Test
    void leavesACodeThatNamesNothingInThisProjectAsPlainText() {
        final List<ModelSection> marked = CodeReferences.markUp(List.of(
                section("Architecture decisions", card("ADR-1", "Scope frame", "superseded by ADR-99"))));

        assertThat(spansOf(marked.get(0))).containsExactly(new Span.Plain("superseded by ADR-99"));
    }

    /** A card linking to itself would send the reader to the card they are already reading. */
    @Test
    void doesNotLinkACardToItself() {
        final List<ModelSection> marked = CodeReferences.markUp(List.of(
                section("Architecture decisions", card("ADR-1", "Scope frame", "unlike ADR-1, this one ..."))));

        assertThat(spansOf(marked.get(0))).containsExactly(new Span.Plain("unlike ADR-1, this one ..."));
    }

    /** A glossary mention the owning builder already recognised survives this second pass. */
    @Test
    void leavesSpansACardBuilderAlreadyMarkedUpUntouched() {
        final RichText mixed = new RichText(List.of(
                new Span.TermLink("Actor", ID + "term-1", "TERM-1"),
                new Span.Plain(" is defined in ADR-3")));
        final ModelCard card = new ModelCard("ADR-1", "Scope frame", ID + "adr-1", List.of(),
                List.of(Block.Prose.paragraph("Decision", mixed)));

        final List<ModelSection> marked = CodeReferences.markUp(List.of(
                section("Architecture decisions", card),
                section("More decisions", card("ADR-3", "Actor identity", "unrelated text"))));

        assertThat(spansOf(marked.get(0))).containsExactly(
                new Span.TermLink("Actor", ID + "term-1", "TERM-1"),
                new Span.Plain(" is defined in "),
                new Span.CodeRef("ADR-3", ID + "adr-3", "ADR-3", "Actor identity"));
    }

    /** Bullet items and flow steps are prose too - a code in an acceptance criterion links. */
    @Test
    void marksUpBulletItemsAsWellAsProse() {
        final ModelCard requirement = new ModelCard("FR-1", "Report", ID + "fr-1", List.of(),
                List.of(new Block.Bullets("Acceptance criteria",
                        List.of(new BulletItem(1, RichText.plain("as decided in ADR-3"))))));

        final List<ModelSection> marked = CodeReferences.markUp(List.of(
                section("Requirements", requirement),
                section("Architecture decisions", card("ADR-3", "Actor identity", "unrelated text"))));

        final Block.Bullets bullets = (Block.Bullets) marked.get(0).cards().get(0).blocks().get(0);
        assertThat(bullets.items().get(0).text().spans()).containsExactly(
                new Span.Plain("as decided in "),
                new Span.CodeRef("ADR-3", ID + "adr-3", "ADR-3", "Actor identity"));
    }

    private static List<Span> spansOf(final ModelSection section) {
        return ProseParts.soleParagraph(section.cards().get(0).blocks().get(0)).spans();
    }

    private static ModelSection section(final String title, final ModelCard card) {
        return new ModelSection(title, title.toLowerCase().replace(' ', '-'), "", List.of(card));
    }

    private static ModelCard card(final String code, final String title, final String decision) {
        return new ModelCard(code, title, ID + code.toLowerCase(), List.of(),
                List.of(Block.Prose.plain("Decision", decision)));
    }
}
