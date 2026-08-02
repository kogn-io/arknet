// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts.ResolvedBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * An ADR card must carry the decision itself (context/decision) as plain prose, its optional
 * MADR fields only when actually recorded, and its three relations as {@link Ref}s a reader can
 * follow - the addressed requirement and affected context through the borrowed resolve ports
 * (ADR-008), {@code supersedes}/{@code supersededBy} back to the ADR's own card in this report.
 */
class AdrCardsTest {

    private static final ProjectId PROJECT = new ProjectId("adr-cards-test");
    private static final String ID = "https://w3id.org/arknet/id/";
    private static final ResourceId ADR_1_ID = ResourceId.of(ID + "adr-1");
    private static final ResourceId ADR_2_ID = ResourceId.of(ID + "adr-2");
    private static final ResourceId ADR_10_ID = ResourceId.of(ID + "adr-10");
    private static final ResourceId FR_1 = ResourceId.of(ID + "fr-1");
    private static final ResourceId BC_1 = ResourceId.of(ID + "bc-1");

    @Test
    void rendersContextAndDecisionAsPlainProse() {
        final AdrCards cards = cardsFor(minimalAdr());

        final ModelSection section = cards.section(PROJECT, Glossary.empty());

        assertThat(section.cards()).singleElement().satisfies(card -> {
            assertThat(card.blocks()).contains(
                    Block.Prose.plain("Context", "Forces and constraints."),
                    Block.Prose.plain("Decision", "What was decided."));
        });
    }

    /** Optional MADR fields absent on the ADR must not render as empty blocks. */
    @Test
    void omitsAbsentOptionalFields() {
        final AdrCards cards = cardsFor(minimalAdr());

        final List<String> labels = cards.section(PROJECT, Glossary.empty()).cards().getFirst().blocks().stream()
                .map(Block::label).toList();

        assertThat(labels).containsExactly("Context", "Decision");
    }

    /** Optional MADR fields present on the ADR must render, each under its own label. */
    @Test
    void rendersOptionalFieldsWhenPresent() {
        final Adr adr = new Adr(
                new AdrId(ADR_1_ID), new AdrCode("ADR-1"), "Use kognio-rdf", AdrStatus.ACCEPTED,
                "Forces and constraints.", "What was decided.", "Positive and negative consequences.",
                "Options that were considered.", LocalDate.of(2026, 7, 1), List.of(), List.of(), List.of());
        final AdrCards cards = cardsFor(adr);

        final List<String> labels = cards.section(PROJECT, Glossary.empty()).cards().getFirst().blocks().stream()
                .map(Block::label).toList();

        assertThat(labels).containsExactly("Context", "Decision", "Consequences", "Alternatives", "Decision date");
        assertThat(blockOf(cards, "Consequences")).isEqualTo(
                Block.Prose.plain("Consequences", "Positive and negative consequences."));
        assertThat(blockOf(cards, "Alternatives")).isEqualTo(
                Block.Prose.plain("Alternatives", "Options that were considered."));
        assertThat(blockOf(cards, "Decision date")).isEqualTo(Block.Prose.plain("Decision date", "2026-07-01"));
    }

    @Test
    void resolvesAddressedRequirementsToTheirBusinessCode() {
        final Adr adr = new Adr(
                new AdrId(ADR_1_ID), new AdrCode("ADR-1"), "Use kognio-rdf", AdrStatus.PROPOSED,
                "Forces and constraints.", "What was decided.", null, null, null,
                List.of(new RequirementRef(FR_1)), List.of(), List.of());
        final AdrCards cards = new AdrCards(
                projectId -> List.of(new AdrDetail(adr, List.of(), List.of())),
                (projectId, ids) -> List.of(new ResolvedRequirement(FR_1, new RequirementCode("FR-1"))),
                (projectId, ids) -> List.of());

        assertThat(cards.section(PROJECT, Glossary.empty()).cards().getFirst().blocks()).contains(
                new Block.Refs("Addresses requirements", List.of(Ref.of("FR-1", FR_1.value()))));
    }

    @Test
    void resolvesAffectedContextsToTheirBusinessCode() {
        final Adr adr = new Adr(
                new AdrId(ADR_1_ID), new AdrCode("ADR-1"), "Use kognio-rdf", AdrStatus.PROPOSED,
                "Forces and constraints.", "What was decided.", null, null, null,
                List.of(), List.of(new BoundedContextRef(BC_1)), List.of());
        final AdrCards cards = new AdrCards(
                projectId -> List.of(new AdrDetail(adr, List.of(), List.of())),
                (projectId, ids) -> List.of(),
                (projectId, ids) -> List.of(new ResolvedBoundedContext(BC_1, new BoundedContextCode("BC-1"))));

        assertThat(cards.section(PROJECT, Glossary.empty()).cards().getFirst().blocks()).contains(
                new Block.Refs("Affects contexts", List.of(Ref.of("BC-1", BC_1.value()))));
    }

    /**
     * A reference the borrowed port cannot resolve must still be shown - falling back to its bare
     * id keeps a broken link visible instead of quietly dropping the edge.
     */
    @Test
    void fallsBackToTheBareIdWhenAReferenceCannotBeResolved() {
        final Adr adr = new Adr(
                new AdrId(ADR_1_ID), new AdrCode("ADR-1"), "Use kognio-rdf", AdrStatus.PROPOSED,
                "Forces and constraints.", "What was decided.", null, null, null,
                List.of(new RequirementRef(FR_1)), List.of(new BoundedContextRef(BC_1)), List.of());
        final AdrCards cards = new AdrCards(
                projectId -> List.of(new AdrDetail(adr, List.of(), List.of())),
                (projectId, ids) -> List.of(),
                (projectId, ids) -> List.of());

        final List<Block> blocks = cards.section(PROJECT, Glossary.empty()).cards().getFirst().blocks();

        assertThat(blocks).contains(
                new Block.Refs("Addresses requirements", List.of(Ref.of(FR_1.value(), FR_1.value()))),
                new Block.Refs("Affects contexts", List.of(Ref.of(BC_1.value(), BC_1.value()))));
    }

    /**
     * {@code supersedes}/{@code supersededBy} arrive as codes only; the card must still link to
     * the other ADR's own subject id, resolved from the same list of decisions in this report.
     */
    @Test
    void rendersSupersedesAndSupersededByAsRefsToTheOtherAdrsOwnId() {
        final Adr older = new Adr(
                new AdrId(ADR_1_ID), new AdrCode("ADR-1"), "Store data in files", AdrStatus.DEPRECATED,
                "Forces and constraints.", "What was decided.", null, null, null, List.of(), List.of(), List.of());
        final Adr newer = new Adr(
                new AdrId(ADR_2_ID), new AdrCode("ADR-2"), "Use kognio-rdf", AdrStatus.ACCEPTED,
                "Forces and constraints.", "What was decided.", null, null, null,
                List.of(), List.of(), List.of()).supersede(new AdrId(ADR_1_ID));
        final AdrCards cards = new AdrCards(
                projectId -> List.of(
                        new AdrDetail(older, List.of(), List.of(new AdrCode("ADR-2"))),
                        new AdrDetail(newer, List.of(new AdrCode("ADR-1")), List.of())),
                (projectId, ids) -> List.of(), (projectId, ids) -> List.of());

        final ModelSection section = cards.section(PROJECT, Glossary.empty());

        assertThat(cardOf(section, "ADR-1").blocks()).contains(
                new Block.Refs("Superseded by", List.of(Ref.of("ADR-2", ADR_2_ID.value()))));
        assertThat(cardOf(section, "ADR-2").blocks()).contains(
                new Block.Refs("Supersedes", List.of(Ref.of("ADR-1", ADR_1_ID.value()))));
    }

    /**
     * A code named by {@code supersedes}/{@code supersededBy} that no longer resolves against
     * this report's own list of decisions falls back to itself rather than being dropped.
     */
    @Test
    void fallsBackToTheCodeWhenTheSupersededAdrIsNoLongerInTheList() {
        final Adr adr = new Adr(
                new AdrId(ADR_2_ID), new AdrCode("ADR-2"), "Use kognio-rdf", AdrStatus.ACCEPTED,
                "Forces and constraints.", "What was decided.", null, null, null, List.of(), List.of(), List.of());
        final AdrCards cards = new AdrCards(
                projectId -> List.of(new AdrDetail(adr, List.of(new AdrCode("ADR-1")), List.of())),
                (projectId, ids) -> List.of(), (projectId, ids) -> List.of());

        assertThat(cards.section(PROJECT, Glossary.empty()).cards().getFirst().blocks()).contains(
                new Block.Refs("Supersedes", List.of(Ref.of("ADR-1", "ADR-1"))));
    }

    @Test
    void ordersCardsByBusinessCode() {
        final AdrCards cards = new AdrCards(
                projectId -> List.of(
                        new AdrDetail(adr(ADR_2_ID, "ADR-2", AdrStatus.PROPOSED), List.of(), List.of()),
                        new AdrDetail(adr(ADR_1_ID, "ADR-1", AdrStatus.PROPOSED), List.of(), List.of())),
                (projectId, ids) -> List.of(), (projectId, ids) -> List.of());

        assertThat(cards.section(PROJECT, Glossary.empty()).cards())
                .extracting(ModelCard::code).containsExactly("ADR-1", "ADR-2");
    }

    /**
     * Regression test for issue #143: {@code Comparator.comparing(... code().value())} sorted
     * {@code String}s naturally, so {@code ADR-10} landed before {@code ADR-2} once a project
     * passed ten decisions.
     */
    @Test
    void ordersCardsByBusinessCodeNumericallyNotLexicographically() {
        final AdrCards cards = new AdrCards(
                projectId -> List.of(
                        new AdrDetail(adr(ADR_2_ID, "ADR-2", AdrStatus.PROPOSED), List.of(), List.of()),
                        new AdrDetail(adr(ADR_10_ID, "ADR-10", AdrStatus.PROPOSED), List.of(), List.of()),
                        new AdrDetail(adr(ADR_1_ID, "ADR-1", AdrStatus.PROPOSED), List.of(), List.of())),
                (projectId, ids) -> List.of(), (projectId, ids) -> List.of());

        assertThat(cards.section(PROJECT, Glossary.empty()).cards())
                .extracting(ModelCard::code).containsExactly("ADR-1", "ADR-2", "ADR-10");
    }

    @Test
    void showsTheStatusAsABadge() {
        final AdrCards cards = cardsFor(adr(ADR_1_ID, "ADR-1", AdrStatus.REJECTED));

        assertThat(cards.section(PROJECT, Glossary.empty()).cards().getFirst().badges()).containsExactly(
                new Badge(Badge.Kind.Known.STATUS, "Rejected"));
    }

    private static Adr minimalAdr() {
        return adr(ADR_1_ID, "ADR-1", AdrStatus.PROPOSED);
    }

    private static Adr adr(final ResourceId id, final String code, final AdrStatus status) {
        return new Adr(
                new AdrId(id), new AdrCode(code), "Use kognio-rdf", status,
                "Forces and constraints.", "What was decided.", null, null, null, List.of(), List.of(), List.of());
    }

    private static AdrCards cardsFor(final Adr adr) {
        return new AdrCards(
                projectId -> List.of(new AdrDetail(adr, List.of(), List.of())),
                (projectId, ids) -> List.of(), (projectId, ids) -> List.of());
    }

    private static Block blockOf(final AdrCards cards, final String label) {
        return cards.section(PROJECT, Glossary.empty()).cards().getFirst().blocks().stream()
                .filter(b -> b.label().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("no block " + label));
    }

    private static ModelCard cardOf(final ModelSection section, final String code) {
        return section.cards().stream()
                .filter(card -> card.code().equals(code))
                .findFirst().orElseThrow(() -> new AssertionError("no card " + code));
    }
}
