// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * The use-case card is the report's answer to "a use case is unreadable as triples": it must
 * carry the flow in order and the references as something a human recognises.
 */
class UseCaseCardsTest {

    private static final ProjectId PROJECT = new ProjectId("cards-test");
    private static final ResourceId ACTOR = ResourceId.of("https://w3id.org/arknet/id/actor-1");
    private static final ResourceId FR_1 = ResourceId.of("https://w3id.org/arknet/id/fr-1");

    private static final Glossary GLOSSARY = Glossary.of(List.of(new Term(
            new TermId(ACTOR), new TermCode("TERM-1"), "Kunde", "Wer bestellt.", null)));

    @Test
    void buildsACockburnStyleCardWithTheFlowInOrder() {
        final UseCaseCards cards = new UseCaseCards(
                projectId -> List.of(useCase()),
                (projectId, ids) -> List.of(new ResolvedRequirement(FR_1, new RequirementCode("FR-1"))));

        final ModelSection section = cards.section(PROJECT, GLOSSARY);

        assertThat(section.title()).isEqualTo("Use Cases");
        assertThat(section.cards()).singleElement().satisfies(card -> {
            assertThat(card.code()).isEqualTo("UC1");
            assertThat(card.title()).isEqualTo("Bestellung aufgeben");
            assertThat(card.blocks()).element(0)
                    .isEqualTo(new Block.Prose("Goal", RichText.plain("Der Kunde bestellt Artikel.")));
            assertThat(card.blocks()).filteredOn(Block.Flow.class::isInstance)
                    .singleElement()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(Block.Flow.class))
                    .satisfies(flow -> assertThat(flow.steps())
                            .extracting(FlowStep::position, FlowStep::text)
                            .containsExactly(
                                    org.assertj.core.groups.Tuple.tuple(1, "Artikel in den Warenkorb legen"),
                                    org.assertj.core.groups.Tuple.tuple(2, "Bestellung bestaetigen")));
        });
    }

    /**
     * The chip carries the term itself; its running number stays available as the tooltip. An
     * actor identity is opaque, so {@code TERM-1} told the reader nothing about who acts here.
     */
    @Test
    void showsAnActorByItsLabelWithTheCodeAsTooltip() {
        final UseCaseCards cards = new UseCaseCards(
                projectId -> List.of(useCase()), (projectId, ids) -> List.of());

        assertThat(cards.section(PROJECT, GLOSSARY).cards().getFirst().blocks()).contains(
                new Block.Refs("Primary actor", List.of(new Ref("Kunde", "TERM-1", ACTOR.value()))));
    }

    /**
     * A use case has no {@code usesTerm} edge - only actor roles - so a glossary word in its goal
     * has no edge that could be pleaded missing. Marking it up would demand a link the model has
     * nowhere to put, which is why only requirement and bounded-context prose is analysed.
     */
    @Test
    void leavesGlossaryWordsInTheGoalUnmarked() {
        final UseCaseCards cards = new UseCaseCards(
                projectId -> List.of(useCase()), (projectId, ids) -> List.of());

        final Block.Prose goal = (Block.Prose) cards.section(PROJECT, GLOSSARY)
                .cards().getFirst().blocks().getFirst();

        assertThat(goal.text().spans()).containsExactly(new Span.Plain("Der Kunde bestellt Artikel."));
    }

    @Test
    void resolvesStepRequirementsToTheirBusinessCodes() {
        final UseCaseCards cards = new UseCaseCards(
                projectId -> List.of(useCase()),
                (projectId, ids) -> List.of(new ResolvedRequirement(FR_1, new RequirementCode("FR-1"))));

        final Block.Flow flow = flowOf(cards.section(PROJECT, GLOSSARY));

        assertThat(flow.steps().get(0).realises()).containsExactly(Ref.of("FR-1", FR_1.value()));
    }

    /**
     * A reference the owning context cannot resolve must still be shown - falling back to its
     * IRI keeps a broken link visible instead of quietly dropping the edge.
     */
    @Test
    void fallsBackToTheBareIriWhenAReferenceCannotBeResolved() {
        final UseCaseCards cards = new UseCaseCards(
                projectId -> List.of(useCase()), (projectId, ids) -> List.of());

        final ModelSection section = cards.section(PROJECT, Glossary.empty());

        assertThat(section.cards().getFirst().blocks()).contains(
                new Block.Refs("Primary actor", List.of(Ref.of(ACTOR.value(), ACTOR.value()))));
        assertThat(flowOf(section).steps().get(0).realises())
                .containsExactly(Ref.of(FR_1.value(), FR_1.value()));
    }

    /** Optional fields that are absent are left out rather than rendered as empty blocks. */
    @Test
    void omitsAbsentOptionalFields() {
        final UseCaseCards cards = new UseCaseCards(
                projectId -> List.of(useCase()), (projectId, ids) -> List.of());

        final List<String> labels = cards.section(PROJECT, GLOSSARY).cards().getFirst().blocks().stream()
                .map(Block::label).toList();

        assertThat(labels).containsExactly("Goal", "Trigger", "Primary actor", "Postcondition", "Main flow");
    }

    @Test
    void sectionIsEmptyWhenThereAreNoUseCases() {
        final UseCaseCards cards = new UseCaseCards(
                projectId -> List.of(), (projectId, ids) -> List.of());

        assertThat(cards.section(PROJECT, GLOSSARY).isEmpty()).isTrue();
    }

    private static Block.Flow flowOf(final ModelSection section) {
        return (Block.Flow) section.cards().getFirst().blocks().stream()
                .filter(Block.Flow.class::isInstance).findFirst().orElseThrow();
    }

    /** A use case with a trigger and a postcondition, but no scope, precondition or extensions. */
    private static UseCase useCase() {
        return new UseCase(
                new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/uc-1")),
                new UseCaseCode("UC1"),
                "Bestellung aufgeben",
                "Der Kunde bestellt Artikel.",
                null,
                "Kunde oeffnet den Warenkorb",
                new ActorRef(ACTOR),
                List.of(),
                null,
                "Die Bestellung ist erfasst.",
                List.of(
                        new Step(1, "Artikel in den Warenkorb legen", List.of(new RequirementRef(FR_1))),
                        new Step(2, "Bestellung bestaetigen", List.of())),
                List.of());
    }
}
