// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Issue #390: {@code constraint_add}/{@code constraint_update} have promised the Markdown subset
 * (issue #388) since it was hung on every writing tool, but the report itself never rendered a
 * constraint - it fell into the generic raw-triple view like any resource no bounded context
 * claims. These tests pin the resulting card: type as a badge, statement as structured prose,
 * title as the card headline - the same shape every other section already gets.
 */
class ConstraintCardsTest {

    private static final ProjectId PROJECT = new ProjectId("constraint-cards-test");
    private static final String ID = "https://w3id.org/arknet/id/";

    @Test
    void showsTheTypeAsABadge() {
        final ConstraintCards cards = cardsFor(constraint(
                "TCON-1", ID + "tcon-1", ConstraintType.TECHNICAL, "JVM only", "Must run on the JVM."));

        assertThat(cards.section(PROJECT, null).cards().getFirst().badges())
                .containsExactly(new Badge(Badge.Kind.Known.TYPE, "TCON"));
    }

    @Test
    void showsTheTitleAsTheCardHeadline() {
        final ConstraintCards cards = cardsFor(constraint(
                "BCON-1", ID + "bcon-1", ConstraintType.BUSINESS, "Budget cap", "Total cost must stay under 50k."));

        final ModelCard card = cards.section(PROJECT, null).cards().getFirst();

        assertThat(card.code()).isEqualTo("BCON-1");
        assertThat(card.title()).isEqualTo("Budget cap");
    }

    /** The Markdown subset (issue #388) applies to the statement exactly like any other prose field. */
    @Test
    void structuresTheStatementByTheMarkdownSubset() {
        final ConstraintCards cards = cardsFor(constraint(
                "RCON-1", ID + "rcon-1", ConstraintType.REGULATORY, "GDPR",
                "Applies:\n\n- personal data\n- health data"));

        final Block.Prose statement = (Block.Prose) block(cards, "Statement");

        assertThat(ProseParts.partsOf(statement)).containsExactly(
                new ProsePart.Paragraph(RichText.plain("Applies:")),
                new ProsePart.Bullets(List.of(RichText.plain("personal data"), RichText.plain("health data"))));
    }

    /** No {@code arkreq:usesTerm} edge of its own (mirrors {@link ActorCards}), so no glossary markup. */
    @Test
    void doesNotMarkUpGlossaryTermsInTheStatement() {
        final ConstraintCards cards = cardsFor(constraint(
                "TCON-1", ID + "tcon-1", ConstraintType.TECHNICAL, "JVM only", "Der Kunde ist betroffen."));

        assertThat(ProseParts.soleParagraph(block(cards, "Statement")).spans())
                .doesNotHaveAnyElementsOfTypes(Span.TermLink.class, Span.TermGap.class);
    }

    /**
     * Regression guard mirroring {@code RequirementCardsTest#ordersCardsByBusinessCodeNumericallyNotLexicographically}:
     * sorting {@code String} codes naturally puts {@code TCON-10} before {@code TCON-2}.
     */
    @Test
    void ordersCardsByBusinessCodeNumericallyNotLexicographically() {
        final ConstraintCards cards = new ConstraintCards((projectId, displayLocale) -> List.of(
                constraint("TCON-2", ID + "tcon-2", ConstraintType.TECHNICAL, "Zweite", "Statement."),
                constraint("TCON-10", ID + "tcon-10", ConstraintType.TECHNICAL, "Zehnte", "Statement."),
                constraint("TCON-1", ID + "tcon-1", ConstraintType.TECHNICAL, "Erste", "Statement.")));

        assertThat(cards.section(PROJECT, null).cards())
                .extracting(ModelCard::code).containsExactly("TCON-1", "TCON-2", "TCON-10");
    }

    private static Block block(final ConstraintCards cards, final String label) {
        return cards.section(PROJECT, null).cards().getFirst().blocks().stream()
                .filter(b -> b.label().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("no block " + label));
    }

    private static ConstraintCards cardsFor(final Constraint constraint) {
        return new ConstraintCards((projectId, displayLocale) -> List.of(constraint));
    }

    private static Constraint constraint(
            final String code, final String iri, final ConstraintType type,
            final String title, final String statement) {
        return new Constraint(new ConstraintId(ResourceId.of(iri)), new ConstraintCode(code), title, statement, type);
    }
}
