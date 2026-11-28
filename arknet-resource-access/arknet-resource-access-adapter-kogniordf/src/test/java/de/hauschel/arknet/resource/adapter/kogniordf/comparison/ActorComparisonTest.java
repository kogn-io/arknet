// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import de.hauschel.arknet.actor.application.ActorService;
import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorReferencedException;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The actor, of each of its four concrete types, written through {@link ActorService} and through
 * {@code submit}: the same steps leave the same content graph, and the same broken step is
 * rejected by both sides, each naming the rule of {@code docs/rules-inventory.md} section 4
 * (kogn-io/arknet#744).
 *
 * <p>One step has no counterpart in the core: an actor typed only as the abstract {@code Actor}
 * ({@code ActorType} has no such constant), which only {@code submit} can be asked to write.</p>
 */
class ActorComparisonTest {

    private static final String EN = "en";
    private static final String DE = "de";
    private static final String NO_BREAK_SPACES = "  ";

    private static final String CORE_BLANK_NAME = "name must not be blank";
    private static final String CORE_BLANK_DESCRIPTION = "description must not be blank when present";
    private static final String SHAPE_NAME = "needs at least one 'arknet:name'";
    private static final String SHAPE_NAME_LENGTH = "min. 2 characters";
    private static final String SHAPE_DESCRIPTION = "must not be empty or only whitespace";
    private static final String ENGINE_ONE_RESOURCE_TYPE = "a created resource is typed with one resource type";
    private static final String ENGINE_NO_RESOURCE = "No resource found for id";
    private static final String ENGINE_STILL_POINTS = "still points at it";

    private static final DeviationList DEVIATIONS = DeviationList.of();

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static ActorService actors;
    private static RoleService roles;
    private static Comparison comparison;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        ActorRegisterCores cores = ActorRegisterCores.over(store.lifecycle());
        actors = cores.actors();
        roles = cores.roles();
        comparison = new Comparison(store, DEVIATIONS);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    // -- writing -----------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ActorType.class)
    void addingAnActorOfEachTypeWritesTheSameGraph(final ActorType type) {
        // given
        ProjectPair pair = store.pair("add-" + type);
        pair.givenOnBoth(project -> addActor(project, ActorType.HUMAN, "Existing"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.add(project, new NewActor(type, "Clerk", "Files the orders.", EN), EN),
                document().create(Node.typed(typeIri(type))
                        .with(ArkarchVocabulary.NAME, text("Clerk", EN))
                        .with(ActorRegisterCores.DESCRIPTION, text("Files the orders.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingAnActorWithoutADescriptionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-no-description");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.add(project, new NewActor(ActorType.SYSTEM, "Billing", null, EN), EN),
                document().create(Node.typed(ArkprocVocabulary.SYSTEM_ACTOR_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Billing", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingAnActorInASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-de");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.add(project, new NewActor(ActorType.HUMAN, "Sachbearbeiter", "Bearbeitet.", DE),
                        EN),
                document().create(Node.typed(ArkprocVocabulary.HUMAN_ACTOR_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Sachbearbeiter", DE))
                        .with(ActorRegisterCores.DESCRIPTION, text("Bearbeitet.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void renamingAnActorWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("rename");
        Given<Actor> actor = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.update(project, actor.core().code(), "Case worker", null, EN, EN),
                document().update(actorIri(actor.submit()),
                        Node.fields().with(ArkarchVocabulary.NAME, text("Case worker", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void changingTheDescriptionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("description");
        Given<Actor> actor = pair.given(project -> addActor(project, ActorType.LEGAL, "Acme Ltd"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.update(project, actor.core().code(), null, "The customer.", EN, EN),
                document().update(actorIri(actor.submit()),
                        Node.fields().with(ActorRegisterCores.DESCRIPTION, text("The customer.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void givingAnActorADescriptionItDidNotHaveWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("description-new");
        Given<Actor> actor = pair.given(project -> actors.add(project,
                new NewActor(ActorType.GROUP, "Back office", null, EN), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.update(project, actor.core().code(), null, "Handles the orders.", EN, EN),
                document().update(actorIri(actor.submit()),
                        Node.fields().with(ActorRegisterCores.DESCRIPTION, text("Handles the orders.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingNameAndDescriptionIntoASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("second-language");
        Given<Actor> actor = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.update(project, actor.core().code(), "Sachbearbeiter", "Bearbeitet.", DE, EN),
                document().update(actorIri(actor.submit()), Node.fields()
                        .with(ArkarchVocabulary.NAME, text("Sachbearbeiter", DE))
                        .with(ActorRegisterCores.DESCRIPTION, text("Bearbeitet.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void deletingAnActorNoRoleIsFilledByWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("delete");
        Given<Actor> actor = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));
        pair.givenOnBoth(project -> addActor(project, ActorType.SYSTEM, "Billing"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.delete(project, actor.core().code()),
                document().delete(actorIri(actor.submit())));

        // then
        outcome.assertSameGraph();
    }

    // -- rejecting ---------------------------------------------------------------------------------

    @Test
    void aBlankNameOnAddIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-name");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.add(project, new NewActor(ActorType.HUMAN, "  ", "Files.", EN), EN),
                document().create(Node.typed(ArkprocVocabulary.HUMAN_ACTOR_TYPE)
                        .with(ArkarchVocabulary.NAME, text("  ", EN))
                        .with(ActorRegisterCores.DESCRIPTION, text("Files.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_NAME),
                Rejection.submit(SHAPE_NAME));
    }

    @Test
    void aNameOfOnlyNoBreakSpacesIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("no-break-space");
        Given<Actor> actor = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.update(project, actor.core().code(), NO_BREAK_SPACES, null, EN, EN),
                document().update(actorIri(actor.submit()),
                        Node.fields().with(ArkarchVocabulary.NAME, text(NO_BREAK_SPACES, EN))));

        // then
        outcome.assertBothReject(Rejection.core(WriteConstraintViolationException.class, SHAPE_NAME),
                Rejection.submit(SHAPE_NAME));
    }

    @Test
    void aNameOfOneCharacterIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("short-name");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.add(project, new NewActor(ActorType.HUMAN, "A", null, EN), EN),
                document().create(Node.typed(ArkprocVocabulary.HUMAN_ACTOR_TYPE)
                        .with(ArkarchVocabulary.NAME, text("A", EN))));

        // then
        outcome.assertBothReject(Rejection.core(WriteConstraintViolationException.class, SHAPE_NAME_LENGTH),
                Rejection.submit(SHAPE_NAME_LENGTH));
    }

    @Test
    void aBlankDescriptionOnAddIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-description");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.add(project, new NewActor(ActorType.HUMAN, "Clerk", "  ", EN), EN),
                document().create(Node.typed(ArkprocVocabulary.HUMAN_ACTOR_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Clerk", EN))
                        .with(ActorRegisterCores.DESCRIPTION, text("  ", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_DESCRIPTION),
                Rejection.submit(SHAPE_DESCRIPTION));
    }

    @Test
    void anEmptyDescriptionOnUpdateIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("empty-description");
        Given<Actor> actor = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.update(project, actor.core().code(), null, "", EN, EN),
                document().update(actorIri(actor.submit()),
                        Node.fields().with(ActorRegisterCores.DESCRIPTION, text("", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_DESCRIPTION),
                Rejection.submit(SHAPE_DESCRIPTION));
    }

    @Test
    void anUnknownActorIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unknown");
        pair.givenOnBoth(project -> addActor(project, ActorType.HUMAN, "Clerk"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.update(project, new ActorCode("ACTOR-9"), "Case worker", null, EN, EN),
                document().update("ACTOR-9", Node.fields().with(ArkarchVocabulary.NAME, text("Case worker", EN))));

        // then
        outcome.assertBothReject(Rejection.core(ActorNotFoundException.class, null),
                Rejection.submit(ENGINE_NO_RESOURCE));
    }

    @Test
    void deletingAnActorARoleIsFilledByIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("delete-filled");
        Given<Actor> actor = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));
        pair.givenOnBoth(project -> roles.add(project,
                new NewRole("Reviewer", null, List.of(actor.core().code().value()), EN), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> actors.delete(project, actor.core().code()),
                document().delete(actorIri(actor.submit())));

        // then
        outcome.assertBothReject(Rejection.core(ActorReferencedException.class, null),
                Rejection.submit(ENGINE_STILL_POINTS));
    }

    @Test
    void anActorTypedOnlyAsTheAbstractSuperclassIsRejectedBySubmit() {
        // given: the core has no operation that writes such an actor
        ProjectPair pair = store.pair("abstract-type");
        CanonicalGraph before = store.graph(pair.submit());

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> store.submit(pair.submit(), document().create(Node.typed(ArkprocVocabulary.ACTOR_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Clerk", EN)))));

        // then
        Rejection.submit(ENGINE_ONE_RESOURCE_TYPE).assertShownBy("submit", rejected);
        assertEquals(before, store.graph(pair.submit()));
    }

    private static Actor addActor(final ProjectId project, final ActorType type, final String name) {
        return actors.add(project, new NewActor(type, name, "Files the orders.", EN), EN);
    }

    private static String typeIri(final ActorType type) {
        return switch (type) {
            case HUMAN -> ArkprocVocabulary.HUMAN_ACTOR_TYPE;
            case SYSTEM -> ArkprocVocabulary.SYSTEM_ACTOR_TYPE;
            case LEGAL -> ArkprocVocabulary.LEGAL_ACTOR_TYPE;
            case GROUP -> ArkprocVocabulary.GROUP_ACTOR_TYPE;
        };
    }

    private static String actorIri(final Actor actor) {
        return actor.id().value().value();
    }
}
