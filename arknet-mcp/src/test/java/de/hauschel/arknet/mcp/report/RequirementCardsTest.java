// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * A requirement's relationship to the ubiquitous language lives in two places at once: in its
 * prose, where a human writes it, and in its {@code arkreq:usesTerm} edges, where the model
 * records it. Nothing keeps the two in step - the edge only ever appears through an explicit
 * {@code req_link_term} - so the card has to show both, and show where they disagree.
 */
class RequirementCardsTest {

    private static final ProjectId PROJECT = new ProjectId("req-cards-test");
    private static final String ID = "https://w3id.org/arknet/id/";
    private static final ResourceId KUNDE = ResourceId.of(ID + "term-1");
    private static final ResourceId BESTELLUNG = ResourceId.of(ID + "term-2");
    private static final ResourceId LIEFERADRESSE = ResourceId.of(ID + "term-3");

    private static final Glossary GLOSSARY = Glossary.of(List.of(
            term(KUNDE, "TERM-1", "Kunde"),
            term(BESTELLUNG, "TERM-2", "Bestellung"),
            term(LIEFERADRESSE, "TERM-3", "Lieferadresse")));

    /** A linked term named in the text reads as a link right where it is used. */
    @Test
    void marksALinkedTermInsideTheDescription() {
        final RequirementCards cards = cardsFor(requirement(
                "Der Kunde legt eine Bestellung an.", List.of(KUNDE), List.of()));

        assertThat(description(cards).spans()).contains(
                new Span.TermLink("Kunde", KUNDE.value(), "TERM-1"));
    }

    /**
     * The drift this whole feature exists for: the text names a glossary term, the model holds
     * no edge to it. Rendering it as a working link would claim a relationship the store does
     * not have, so it is marked as a gap instead.
     */
    @Test
    void marksAGlossaryWordWithNoEdgeAsAGap() {
        final RequirementCards cards = cardsFor(requirement(
                "Der Kunde legt eine Bestellung an.", List.of(KUNDE), List.of()));

        assertThat(description(cards).spans()).contains(
                new Span.TermGap("Bestellung", BESTELLUNG.value(), "TERM-2"));
    }

    /** Acceptance criteria are prose too - and the part a reviewer reads most closely. */
    @Test
    void marksTermsInAcceptanceCriteriaAsWell() {
        final RequirementCards cards = cardsFor(requirement(
                "Etwas ganz anderes.", List.of(KUNDE), List.of("Der Kunde sieht eine Bestaetigung.")));

        final Block.Bullets criteria = (Block.Bullets) block(cards, "Acceptance criteria");

        assertThat(criteria.items().getFirst().text().spans()).contains(
                new Span.TermLink("Kunde", KUNDE.value(), "TERM-1"));
    }

    /**
     * A term the prose already shows as a link needs no chip repeating it; only an edge the
     * text never names has to be listed, because nothing else in the card would reveal it.
     */
    @Test
    void listsOnlyLinkedTermsTheTextDoesNotName() {
        final RequirementCards cards = cardsFor(requirement(
                "Der Kunde bestellt.", List.of(KUNDE, LIEFERADRESSE), List.of()));

        final Block.Refs refs = (Block.Refs) block(cards, "Uses terms (not named in the text)");

        assertThat(refs.refs()).containsExactly(
                new Ref("Lieferadresse", "TERM-3", LIEFERADRESSE.value()));
    }

    /** With nothing found in the text the block keeps its plain old heading. */
    @Test
    void keepsThePlainHeadingWhenTheTextNamesNoTermAtAll() {
        final RequirementCards cards = cardsFor(requirement(
                "Etwas ganz anderes.", List.of(LIEFERADRESSE), List.of()));

        assertThat(block(cards, "Uses terms")).isNotNull();
    }

    /**
     * The recorded reason gets its own block, right after the statement it explains - and it is
     * prose like any other, so the glossary marks it up too (issue #321).
     */
    @Test
    void rendersTheRationaleAsItsOwnGlossaryMarkedBlock() {
        final RequirementCards cards = cardsFor(requirement(
                "Etwas ganz anderes.", "Damit der Kunde nicht abspringt.", List.of(KUNDE), List.of()));

        final Block.Prose rationale = (Block.Prose) block(cards, "Rationale");

        assertThat(ProseParts.soleParagraph(rationale).spans()).contains(new Span.TermLink("Kunde", KUNDE.value(), "TERM-1"));
    }

    /**
     * The field is optional (issue #321), so a requirement whose reason nobody recorded gets no
     * block at all - an empty "Rationale" heading would read as a recorded blank.
     */
    @Test
    void omitsTheRationaleBlockEntirelyWhenNoneIsRecorded() {
        final RequirementCards cards = cardsFor(requirement("Etwas ganz anderes.", List.of(), List.of()));

        assertThat(labels(cards)).doesNotContain("Rationale");
    }

    /**
     * A term named only in the rationale counts as named: the unlinked-mention sweep scans it
     * alongside the description and the criteria, so it must not also appear as a chip.
     */
    @Test
    void countsTermsNamedOnlyInTheRationaleAsNamedInTheText() {
        final RequirementCards cards = cardsFor(requirement(
                "Etwas ganz anderes.", "Damit die Lieferadresse stimmt.", List.of(LIEFERADRESSE), List.of()));

        assertThat(labels(cards)).doesNotContain("Uses terms (not named in the text)");
    }

    /**
     * Regression test for issue #143: sorting {@code String} codes naturally puts {@code FR-10}
     * before {@code FR-2} once a project passes ten requirements.
     */
    @Test
    void ordersCardsByBusinessCodeNumericallyNotLexicographically() {
        final RequirementCards cards = new RequirementCards((projectId, displayLocale) -> List.of(
                requirement("FR-2", ID + "fr-2", "Zweite"),
                requirement("FR-10", ID + "fr-10", "Zehnte"),
                requirement("FR-1", ID + "fr-1", "Erste")));

        assertThat(cards.section(PROJECT, null, GLOSSARY).cards())
                .extracting(ModelCard::code).containsExactly("FR-1", "FR-2", "FR-10");
    }

    /** Every linked term appears in the prose, so the chip list would be pure repetition. */
    @Test
    void dropsTheChipListEntirelyWhenTheTextNamesEveryLinkedTerm() {
        final RequirementCards cards = cardsFor(requirement(
                "Der Kunde bestellt.", List.of(KUNDE), List.of()));

        assertThat(labels(cards)).doesNotContain("Uses terms", "Uses terms (not named in the text)");
    }

    /**
     * The two markup passes compose: the author's emphasis is structure, the glossary link inside
     * it is what the model knows - and neither swallows the other (issue #388).
     */
    @Test
    void keepsAGlossaryLinkInsideAnEmphasisedRun() {
        final RequirementCards cards = cardsFor(requirement(
                "Der **Kunde** legt an.", List.of(KUNDE), List.of()));

        assertThat(description(cards).spans()).contains(new Span.Emphasis(Span.Style.STRONG,
                new RichText("Kunde", List.of(new Span.TermLink("Kunde", KUNDE.value(), "TERM-1")))));
    }

    /**
     * A description that enumerates gets a real list, instead of the numbering-in-brackets
     * workaround structure used to fall back on (issue #388).
     */
    @Test
    void structuresABulletListInsideADescription() {
        final RequirementCards cards = cardsFor(requirement(
                "Es gilt:\n\n- erstens\n- zweitens", List.of(), List.of()));

        assertThat(ProseParts.partsOf(block(cards, "Description"))).containsExactly(
                new ProsePart.Paragraph(RichText.plain("Es gilt:")),
                new ProsePart.Bullets(List.of(RichText.plain("erstens"), RichText.plain("zweitens"))));
    }

    private static RichText description(final RequirementCards cards) {
        return ProseParts.soleParagraph(block(cards, "Description"));
    }

    private static Block block(final RequirementCards cards, final String label) {
        return cards.section(PROJECT, null, GLOSSARY).cards().getFirst().blocks().stream()
                .filter(b -> b.label().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("no block " + label + " in " + labels(cards)));
    }

    private static List<String> labels(final RequirementCards cards) {
        return cards.section(PROJECT, null, GLOSSARY).cards().getFirst().blocks().stream()
                .map(Block::label).toList();
    }

    private static RequirementCards cardsFor(final Requirement requirement) {
        return new RequirementCards((projectId, displayLocale) -> List.of(requirement));
    }

    private static Requirement requirement(
            final String description, final List<ResourceId> linked, final List<String> criteria) {
        return requirement(description, null, linked, criteria);
    }

    /** {@link #requirement(String, List, List)} carrying a rationale (issue #321). */
    private static Requirement requirement(final String description, final String rationale,
            final List<ResourceId> linked, final List<String> criteria) {
        return new Requirement(
                new RequirementId(ResourceId.of(ID + "fr-1")),
                new RequirementCode("FR-1"), "Bestellen", description, rationale,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                linked.stream().map(TermRef::new).toList(),
                toCriteria(criteria.isEmpty() ? List.of("Es funktioniert.") : criteria), List.of());
    }

    private static Requirement requirement(final String code, final String iri, final String title) {
        return new Requirement(
                new RequirementId(ResourceId.of(iri)), new RequirementCode(code), title,
                "Beschreibung.", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE,
                null, null, List.of(), toCriteria(List.of("Es funktioniert.")), List.of());
    }

    private static List<AcceptanceCriterion> toCriteria(final List<String> texts) {
        final List<AcceptanceCriterion> criteria = new ArrayList<>();
        int position = 1;
        for (final String text : texts) {
            criteria.add(new AcceptanceCriterion(position++, text));
        }
        return criteria;
    }

    private static Term term(final ResourceId id, final String code, final String label) {
        return new Term(new TermId(id), new TermCode(code), label, "Definition von " + label + ".", null);
    }
}
