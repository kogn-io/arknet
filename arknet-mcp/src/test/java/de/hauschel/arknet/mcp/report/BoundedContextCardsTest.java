// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.bc.application.port.in.BoundedContextDetail;
import de.hauschel.arknet.bc.application.port.in.RelatedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.ContextRelationshipId;
import de.hauschel.arknet.bc.domain.RelationshipType;
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

    private static final ProjectId PROJECT = new ProjectId("bc-cards-test");
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

        assertThat(cards.section(PROJECT, null, GLOSSARY).cards().getFirst().blocks())
                .extracting(Block::label)
                .doesNotContain("Ubiquitous language", "Ubiquitous language (not named in the vision)");
    }

    /**
     * Regression test for issue #143: sorting {@code String} codes naturally puts {@code BC-10}
     * before {@code BC-2} once a project passes ten bounded contexts.
     */
    @Test
    void ordersCardsByBusinessCodeNumericallyNotLexicographically() {
        final BoundedContextCards cards = new BoundedContextCards((projectId, displayLocale) -> List.of(
                detail(context("BC-2", ID + "bc-2")), detail(context("BC-10", ID + "bc-10")),
                detail(context("BC-1", ID + "bc-1"))));

        assertThat(cards.section(PROJECT, null, GLOSSARY).cards())
                .extracting(ModelCard::code).containsExactly("BC-1", "BC-2", "BC-10");
    }

    /** A context map chip names direction, peer code and relationship type in one label. */
    @Test
    void rendersAContextMapChipForEachDirection() {
        final BoundedContextCards cards = new BoundedContextCards((projectId, displayLocale) -> List.of(detail(
                context("Nimmt jede Bestellung auf.", List.of()),
                List.of(
                        new RelatedContext(
                                new ContextRelationshipId(ResourceId.of(ID + "relationship-1")),
                                RelatedContext.Direction.UPSTREAM_OF,
                                new BoundedContextId(ResourceId.of(ID + "bc-2")),
                                new BoundedContextCode("BC-2"), RelationshipType.PUBLISHED_LANGUAGE),
                        new RelatedContext(
                                new ContextRelationshipId(ResourceId.of(ID + "relationship-2")),
                                RelatedContext.Direction.DOWNSTREAM_OF,
                                new BoundedContextId(ResourceId.of(ID + "bc-3")),
                                new BoundedContextCode("BC-3"), RelationshipType.CONFORMIST)))));

        final Block.Refs refs = (Block.Refs) block(cards, BoundedContextCards.CONTEXT_MAP_LABEL);

        assertThat(refs.refs()).containsExactly(
                Ref.of("upstream of BC-2 (Published language)", ID + "bc-2"),
                Ref.of("downstream of BC-3 (Conformist)", ID + "bc-3"));
    }

    /** No relationships, no "Context map" block - a context with no context-map edges yet. */
    @Test
    void dropsTheContextMapBlockWhenTheContextHasNoRelationships() {
        final BoundedContextCards cards = cardsFor(context("Nimmt jede Bestellung auf.", List.of()));

        assertThat(cards.section(PROJECT, null, GLOSSARY).cards().getFirst().blocks())
                .extracting(Block::label).doesNotContain(BoundedContextCards.CONTEXT_MAP_LABEL);
    }

    private static RichText vision(final BoundedContextCards cards) {
        return ProseParts.soleParagraph(block(cards, "Domain vision"));
    }

    private static Block block(final BoundedContextCards cards, final String label) {
        return cards.section(PROJECT, null, GLOSSARY).cards().getFirst().blocks().stream()
                .filter(b -> b.label().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("no block " + label));
    }

    private static BoundedContextCards cardsFor(final BoundedContext context) {
        return new BoundedContextCards((projectId, displayLocale) -> List.of(detail(context)));
    }

    private static BoundedContextDetail detail(final BoundedContext context) {
        return new BoundedContextDetail(context, List.of());
    }

    private static BoundedContextDetail detail(final BoundedContext context, final List<RelatedContext> relationships) {
        return new BoundedContextDetail(context, relationships);
    }

    private static BoundedContext context(final String vision, final List<ResourceId> linked) {
        return new BoundedContext(
                new BoundedContextId(ResourceId.of(ID + "bc-1")),
                new BoundedContextCode("BC-1"), "Ordering", vision,
                Subdomain.CORE_DOMAIN, null,
                linked.stream().map(TermRef::new).toList());
    }

    private static BoundedContext context(final String code, final String iri) {
        return new BoundedContext(
                new BoundedContextId(ResourceId.of(iri)), new BoundedContextCode(code), "Name",
                "Vision.", Subdomain.CORE_DOMAIN, null, List.of());
    }

    private static Term term(final ResourceId id, final String code, final String label) {
        return new Term(new TermId(id), new TermCode(code), label, "Definition von " + label + ".", null);
    }
}
