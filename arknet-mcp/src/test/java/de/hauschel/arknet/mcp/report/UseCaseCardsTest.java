// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.actor.application.port.in.ResolveRoles;
import de.hauschel.arknet.actor.application.port.in.ResolveRoles.ResolvedRole;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.RoleRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.TermRef;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * The use-case card is the report's answer to "a use case is unreadable as triples": it must
 * carry the flow in order and the references as something a human recognises.
 *
 * <p>Since issue #333 a use case's relationship to the ubiquitous language lives in the same two
 * places a requirement's does (see {@link RequirementCardsTest}): in its prose, and in its
 * {@code arkreq:usesTerm}/{@code primaryRole}/{@code supportingRole} edges. The markup tests
 * here mirror {@code RequirementCardsTest}'s, with one addition a requirement does not have: a
 * use case's own primary/supporting role counts as linked even without a {@code usesTerm} edge,
 * since that relationship is already recorded under a different predicate.</p>
 *
 * <p><strong>Role display resolution (ADR-37/kogn-io/arknet#405 Part C).</strong> {@code
 * ROLE}/{@code LIEFERANT} here also happen to be registered in {@link #GLOSSARY} under the same
 * identity, purely so the mention-markup tests below can exercise the generic "an id in the
 * {@code linked} set counts as a link" mechanism without needing a second matching engine - this
 * is a test fixture convenience, not a claim that a role and a glossary term share an identity
 * space in a real project (since issue #336 they never do). The role <em>chip</em> itself
 * (business code and display name) is resolved independently, through {@link #RESOLVE_ROLES}, a
 * fake {@link ResolveRoles}.</p>
 */
class UseCaseCardsTest {

    private static final ProjectId PROJECT = new ProjectId("cards-test");
    private static final String ID = "https://w3id.org/arknet/id/";
    private static final ResourceId ROLE = ResourceId.of(ID + "role-1");
    private static final ResourceId LIEFERANT = ResourceId.of(ID + "role-2");
    private static final ResourceId FR_1 = ResourceId.of(ID + "fr-1");
    private static final ResourceId WARENKORB = ResourceId.of(ID + "term-warenkorb");
    private static final ResourceId LIEFERADRESSE = ResourceId.of(ID + "term-lieferadresse");

    private static final Glossary GLOSSARY = Glossary.of(List.of(
            term(ROLE, "TERM-1", "Kunde"),
            term(WARENKORB, "TERM-2", "Warenkorb"),
            term(LIEFERADRESSE, "TERM-3", "Lieferadresse"),
            term(LIEFERANT, "TERM-4", "Lieferant")));

    /** Resolves {@link #ROLE}/{@link #LIEFERANT} to the display name/code the chip tests expect. */
    private static final ResolveRoles RESOLVE_ROLES = (projectId, displayLocale, ids) -> List.of(
            new ResolvedRole(ROLE, new RoleCode("ROLE-1"), "Kunde"),
            new ResolvedRole(LIEFERANT, new RoleCode("ROLE-2"), "Lieferant"));

    @Test
    void sectionTitleIsUseCases() {
        final ModelSection section = cardsFor(useCase("Ziel.", List.of())).section(PROJECT, null, GLOSSARY);

        assertThat(section.title()).isEqualTo("Use Cases");
    }

    @Test
    void cardCodeIsTheUseCasesBusinessCode() {
        final ModelSection section = cardsFor(useCase("Ziel.", List.of())).section(PROJECT, null, GLOSSARY);

        assertThat(section.cards()).singleElement()
                .satisfies(card -> assertThat(card.code()).isEqualTo("UC1"));
    }

    @Test
    void cardTitleIsTheUseCasesTitle() {
        final ModelSection section = cardsFor(useCase("Ziel.", List.of())).section(PROJECT, null, GLOSSARY);

        assertThat(section.cards()).singleElement()
                .satisfies(card -> assertThat(card.title()).isEqualTo("Bestellung aufgeben"));
    }

    /**
     * A use case's own primary role is not just referenced via {@code arkreq:primaryRole} - it
     * is also the subject of its goal, and that mention counts as linked even though no {@code
     * usesTerm} edge backs it (issue #333).
     */
    @Test
    void marksTheUseCasesOwnPrimaryRoleMentionInTheGoalAsALink() {
        final UseCaseCards cards = cardsFor(useCase("Der Kunde bestellt Artikel.", List.of()));

        assertThat(goal(cards).spans()).contains(new Span.TermLink("Kunde", ROLE.value(), "TERM-1"));
    }

    /**
     * The drift this whole feature exists for: the goal names a glossary term, the model holds
     * no edge to it (issue #333, mirrors {@code RequirementCardsTest}).
     */
    @Test
    void marksAGlossaryWordWithNoEdgeAsAGapInTheGoal() {
        final UseCaseCards cards = cardsFor(useCase("Der Kunde legt Artikel in den Warenkorb.", List.of()));

        assertThat(goal(cards).spans()).contains(new Span.TermGap("Warenkorb", WARENKORB.value(), "TERM-2"));
    }

    @Test
    void marksALinkedUsesTermInTheGoal() {
        final UseCaseCards cards = cardsFor(
                useCase("Der Kunde legt Artikel in den Warenkorb.", List.of(WARENKORB)));

        assertThat(goal(cards).spans()).contains(new Span.TermLink("Warenkorb", WARENKORB.value(), "TERM-2"));
    }

    @Test
    void marksATermInTheScope() {
        final UseCaseCards cards = cardsFor(useCase(
                "Ziel.", "Der Kunde verwaltet seinen Warenkorb.", null, ROLE, List.of(), null, null,
                List.of(new Step(1, "Ein Schritt", List.of())), List.of(), List.of(WARENKORB)));

        assertThat(ProseParts.soleParagraph(block(cards, "Scope")).spans())
                .contains(new Span.TermLink("Warenkorb", WARENKORB.value(), "TERM-2"));
    }

    @Test
    void marksATermInTheTrigger() {
        final UseCaseCards cards = cardsFor(useCase(
                "Ziel.", null, "Der Kunde oeffnet den Warenkorb.", ROLE, List.of(), null, null,
                List.of(new Step(1, "Ein Schritt", List.of())), List.of(), List.of(WARENKORB)));

        assertThat(ProseParts.soleParagraph(block(cards, "Trigger")).spans())
                .contains(new Span.TermLink("Warenkorb", WARENKORB.value(), "TERM-2"));
    }

    @Test
    void marksATermInThePrecondition() {
        final UseCaseCards cards = cardsFor(useCase(
                "Ziel.", null, null, ROLE, List.of(), "Der Warenkorb des Kunden ist leer.", null,
                List.of(new Step(1, "Ein Schritt", List.of())), List.of(), List.of(WARENKORB)));

        assertThat(ProseParts.soleParagraph(block(cards, "Precondition")).spans())
                .contains(new Span.TermLink("Warenkorb", WARENKORB.value(), "TERM-2"));
    }

    @Test
    void marksATermInThePostcondition() {
        final UseCaseCards cards = cardsFor(useCase(
                "Ziel.", null, null, ROLE, List.of(), null, "Der Warenkorb des Kunden ist leer.",
                List.of(new Step(1, "Ein Schritt", List.of())), List.of(), List.of(WARENKORB)));

        assertThat(ProseParts.soleParagraph(block(cards, "Postcondition")).spans())
                .contains(new Span.TermLink("Warenkorb", WARENKORB.value(), "TERM-2"));
    }

    @Test
    void marksATermInAMainFlowStep() {
        final UseCaseCards cards = cardsFor(useCase(
                "Ziel.", null, null, ROLE, List.of(), null, null,
                List.of(new Step(1, "Der Kunde legt Artikel in den Warenkorb.", List.of())), List.of(),
                List.of(WARENKORB)));

        final Block.Flow flow = flowOf(cards.section(PROJECT, null, GLOSSARY));

        assertThat(flow.steps().getFirst().text().spans()).contains(
                new Span.TermLink("Kunde", ROLE.value(), "TERM-1"),
                new Span.TermLink("Warenkorb", WARENKORB.value(), "TERM-2"));
    }

    @Test
    void marksATermInAnExtension() {
        final UseCaseCards cards = cardsFor(useCase(
                "Ziel.", null, null, ROLE, List.of(), null, null,
                List.of(new Step(1, "Ein Schritt", List.of())), List.of("Der Kunde bricht ab."), List.of()));

        final Block.Bullets extensions = (Block.Bullets) block(cards, "Extensions");

        assertThat(extensions.items().getFirst().text().spans())
                .contains(new Span.TermLink("Kunde", ROLE.value(), "TERM-1"));
    }

    /** A supporting role's mention is linked exactly like the primary role's (issue #333). */
    @Test
    void marksASupportingRoleMentionAsALink() {
        final UseCaseCards cards = cardsFor(useCase(
                "Der Lieferant liefert die Bestellung aus.", null, null, ROLE, List.of(LIEFERANT), null, null,
                List.of(new Step(1, "Ein Schritt", List.of())), List.of(), List.of()));

        assertThat(goal(cards).spans()).contains(new Span.TermLink("Lieferant", LIEFERANT.value(), "TERM-4"));
    }

    /**
     * The chip carries the role's resolved display name; its running number stays available as
     * the tooltip. A role identity is opaque, so {@code ROLE-1} told the reader nothing about who
     * fulfils it (ADR-37/kogn-io/arknet#405 Part C - resolved through {@link ResolveRoles}, not
     * the glossary a role no longer belongs to).
     */
    @Test
    void showsARoleByItsResolvedNameWithTheCodeAsTooltip() {
        final UseCaseCards cards = cardsFor(useCase("Ziel.", List.of()));

        assertThat(cards.section(PROJECT, null, GLOSSARY).cards().getFirst().blocks()).contains(
                new Block.Refs("Primary role", List.of(new Ref("Kunde", "ROLE-1", ROLE.value()))));
    }

    /**
     * A term the prose already shows as a link needs no chip repeating it; only an edge no field's
     * text names has to be listed (mirrors {@code RequirementCardsTest}).
     */
    @Test
    void listsOnlyLinkedTermsTheTextDoesNotName() {
        final UseCaseCards cards = cardsFor(
                useCase("Der Kunde legt Artikel in den Warenkorb.", List.of(WARENKORB, LIEFERADRESSE)));

        final Block.Refs refs = (Block.Refs) block(cards, "Uses terms (not named in the text)");

        assertThat(refs.refs()).containsExactly(new Ref("Lieferadresse", "TERM-3", LIEFERADRESSE.value()));
    }

    /** With nothing found in the text the block keeps its plain old heading. */
    @Test
    void keepsThePlainHeadingWhenTheTextNamesNoTermAtAll() {
        final UseCaseCards cards = cardsFor(useCase("Ganz andere Worte.", List.of(LIEFERADRESSE)));

        assertThat(block(cards, "Uses terms")).isNotNull();
    }

    /** Every linked term appears in the goal, so the chip list would be pure repetition. */
    @Test
    void dropsTheChipListEntirelyWhenTheTextNamesEveryLinkedTerm() {
        final UseCaseCards cards = cardsFor(
                useCase("Der Kunde legt Artikel in den Warenkorb.", List.of(WARENKORB)));

        assertThat(labels(cards)).doesNotContain("Uses terms", "Uses terms (not named in the text)");
    }

    @Test
    void buildsACockburnStyleCardWithTheFlowInOrder() {
        final UseCase uc = useCase(
                "Ziel.", null, null, ROLE, List.of(), null, null,
                List.of(new Step(1, "Artikel auswaehlen", List.of(new RequirementRef(FR_1))),
                        new Step(2, "Bestellung bestaetigen", List.of())),
                List.of(), List.of());
        final UseCaseCards cards = new UseCaseCards(
                (projectId, displayLocale) -> List.of(uc),
                (projectId, ids) -> List.of(new ResolvedRequirement(FR_1, new RequirementCode("FR-1"))),
                (projectId, displayLocale, ids) -> List.of());

        final Block.Flow flow = flowOf(cards.section(PROJECT, null, GLOSSARY));

        assertThat(flow.steps())
                .extracting(FlowStep::position, step -> step.text().text())
                .containsExactly(
                        Tuple.tuple(1, "Artikel auswaehlen"),
                        Tuple.tuple(2, "Bestellung bestaetigen"));
    }

    @Test
    void resolvesStepRequirementsToTheirBusinessCodes() {
        final UseCase uc = useCase(
                "Ziel.", null, null, ROLE, List.of(), null, null,
                List.of(new Step(1, "Schritt.", List.of(new RequirementRef(FR_1)))), List.of(), List.of());
        final UseCaseCards cards = new UseCaseCards(
                (projectId, displayLocale) -> List.of(uc),
                (projectId, ids) -> List.of(new ResolvedRequirement(FR_1, new RequirementCode("FR-1"))),
                (projectId, displayLocale, ids) -> List.of());

        final Block.Flow flow = flowOf(cards.section(PROJECT, null, GLOSSARY));

        assertThat(flow.steps().get(0).realises()).containsExactly(Ref.of("FR-1", FR_1.value()));
    }

    /**
     * A reference the owning context cannot resolve must still be shown - falling back to its
     * IRI keeps a broken link visible instead of quietly dropping the edge.
     */
    @Test
    void fallsBackToTheBareIriWhenAReferenceCannotBeResolved() {
        final UseCase uc = useCase(
                "Ziel.", null, null, ROLE, List.of(), null, null,
                List.of(new Step(1, "Schritt.", List.of(new RequirementRef(FR_1)))), List.of(), List.of());
        final UseCaseCards cards = new UseCaseCards(
                (projectId, displayLocale) -> List.of(uc), (projectId, ids) -> List.of(),
                (projectId, displayLocale, ids) -> List.of());

        final ModelSection section = cards.section(PROJECT, null, Glossary.empty());

        assertThat(section.cards().getFirst().blocks()).contains(
                new Block.Refs("Primary role", List.of(Ref.of(ROLE.value(), ROLE.value()))));
        assertThat(flowOf(section).steps().get(0).realises())
                .containsExactly(Ref.of(FR_1.value(), FR_1.value()));
    }

    /** Optional fields that are absent are left out rather than rendered as empty blocks. */
    @Test
    void omitsAbsentOptionalFields() {
        final UseCase uc = useCase(
                "Ziel.", null, "Trigger.", ROLE, List.of(), null, "Nachbedingung.",
                List.of(new Step(1, "Schritt.", List.of())), List.of(), List.of());

        final List<String> labels = labels(cardsFor(uc));

        assertThat(labels).containsExactly("Goal", "Trigger", "Primary role", "Postcondition", "Main flow");
    }

    /**
     * Regression test for issue #143: sorting {@code String} codes naturally puts {@code UC10}
     * before {@code UC2} once a project passes ten use cases.
     */
    @Test
    void ordersCardsByBusinessCodeNumericallyNotLexicographically() {
        final UseCaseCards cards = new UseCaseCards(
                (projectId, displayLocale) -> List.of(
                        useCase("UC2", ID + "uc-2"),
                        useCase("UC10", ID + "uc-10"),
                        useCase("UC1", ID + "uc-1")),
                (projectId, ids) -> List.of(),
                (projectId, displayLocale, ids) -> List.of());

        assertThat(cards.section(PROJECT, null, GLOSSARY).cards())
                .extracting(ModelCard::code).containsExactly("UC1", "UC2", "UC10");
    }

    @Test
    void sectionIsEmptyWhenThereAreNoUseCases() {
        final UseCaseCards cards = new UseCaseCards(
                (projectId, displayLocale) -> List.of(), (projectId, ids) -> List.of(),
                (projectId, displayLocale, ids) -> List.of());

        assertThat(cards.section(PROJECT, null, GLOSSARY).isEmpty()).isTrue();
    }

    private static RichText goal(final UseCaseCards cards) {
        return ProseParts.soleParagraph(block(cards, "Goal"));
    }

    private static Block block(final UseCaseCards cards, final String label) {
        return cards.section(PROJECT, null, GLOSSARY).cards().getFirst().blocks().stream()
                .filter(b -> b.label().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("no block " + label + " in " + labels(cards)));
    }

    private static List<String> labels(final UseCaseCards cards) {
        return cards.section(PROJECT, null, GLOSSARY).cards().getFirst().blocks().stream()
                .map(Block::label).toList();
    }

    private static Block.Flow flowOf(final ModelSection section) {
        return (Block.Flow) section.cards().getFirst().blocks().stream()
                .filter(Block.Flow.class::isInstance).findFirst().orElseThrow();
    }

    private static UseCaseCards cardsFor(final UseCase useCase) {
        return new UseCaseCards(
                (projectId, displayLocale) -> List.of(useCase), (projectId, ids) -> List.of(), RESOLVE_ROLES);
    }

    /** A use case with just a goal and its usesTerm edges - the shape most markup tests need. */
    private static UseCase useCase(final String goal, final List<ResourceId> usesTerms) {
        return useCase(goal, null, null, ROLE, List.of(), null, null,
                List.of(new Step(1, "Ein Schritt", List.of())), List.of(), usesTerms);
    }

    private static UseCase useCase(
            final String goal, final String scope, final String trigger,
            final ResourceId primaryRole, final List<ResourceId> supportingRoles,
            final String precondition, final String postcondition,
            final List<Step> steps, final List<String> extensions, final List<ResourceId> usesTerms) {
        return new UseCase(
                new UseCaseId(ResourceId.of(ID + "uc-1")), new UseCaseCode("UC1"), "Bestellung aufgeben",
                goal, scope, trigger, new RoleRef(primaryRole),
                supportingRoles.stream().map(RoleRef::new).toList(),
                precondition, postcondition, steps, extensions,
                usesTerms.stream().map(TermRef::new).toList(), List.of());
    }

    private static UseCase useCase(final String code, final String iri) {
        return new UseCase(
                new UseCaseId(ResourceId.of(iri)), new UseCaseCode(code), "Titel", "Ziel.", null, "Trigger.",
                new RoleRef(ROLE), List.of(), null, "Nachbedingung.",
                List.of(new Step(1, "Ein Schritt", List.of())), List.of(), List.of(), List.of());
    }

    private static Term term(final ResourceId id, final String code, final String label) {
        return new Term(new TermId(id), new TermCode(code), label, "Definition von " + label + ".", null);
    }
}
