// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.hauschel.arknet.actor.application.ActorService;
import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleNotFoundException;
import de.hauschel.arknet.actor.domain.RoleReferencedException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;

/**
 * The role, with the actors that fill it, written through {@link RoleService} and through
 * {@code submit}: the same steps leave the same content graph - occupants set, added, removed and
 * cleared - and the same broken step is rejected by both sides, each naming the rule of
 * {@code docs/rules-inventory.md} section 4 (kogn-io/arknet#744).
 *
 * <p>The occupants of a role are a set: the core replaces the whole list in one call, a document
 * names the occupants it adds or removes, and both leave the same edges.</p>
 */
class RoleComparisonTest {

    private static final String EN = "en";
    private static final String DE = "de";
    private static final String NO_BREAK_SPACES = "  ";

    private static final String CORE_BLANK_NAME = "name must not be blank";
    private static final String CORE_BLANK_DESCRIPTION = "description must not be blank when present";
    private static final String SHAPE_NAME = "needs at least one 'arknet:name'";
    private static final String SHAPE_NAME_LENGTH = "min. 2 characters";
    private static final String SHAPE_DESCRIPTION = "must not be empty or only whitespace";
    private static final String SHAPE_FILLED_BY = "must be an IRI of an existing arkproc:Actor";
    private static final String SHAPE_UNIQUE_LANGUAGE = "sh:uniqueLang";
    private static final String ENGINE_NO_RESOURCE = "No resource found for id";
    private static final String ENGINE_STILL_POINTS = "still points at it";

    private static final DeviationList DEVIATIONS = DeviationList.of();

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static ActorRegisterCores cores;
    private static ActorService actors;
    private static RoleService roles;
    private static UseCaseService useCases;
    private static Comparison comparison;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        cores = ActorRegisterCores.over(store.lifecycle());
        actors = cores.actors();
        roles = cores.roles();
        useCases = cores.useCases();
        comparison = new Comparison(store, DEVIATIONS);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    // -- writing -----------------------------------------------------------------------------------

    @Test
    void addingAnUnfilledRoleWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-unfilled");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.add(project, new NewRole("Reviewer", "Reads the orders.", List.of(), EN), EN),
                document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Reviewer", EN))
                        .with(ActorRegisterCores.DESCRIPTION, text("Reads the orders.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingARoleWithoutADescriptionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-no-description");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.add(project, new NewRole("Reviewer", null, List.of(), EN), EN),
                document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Reviewer", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingARoleFilledByTwoActorsOfDifferentTypesWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-filled");
        Given<Actor> human = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));
        Given<Actor> system = pair.given(project -> addActor(project, ActorType.SYSTEM, "Billing"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.add(project, new NewRole("Reviewer", "Reads the orders.",
                        List.of(human.core().code().value(), system.core().code().value()), EN), EN),
                document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Reviewer", EN))
                        .with(ActorRegisterCores.DESCRIPTION, text("Reads the orders.", EN))
                        .with(ArkprocVocabulary.FILLED_BY, Document.iri(actorIri(human.submit())))
                        .with(ArkprocVocabulary.FILLED_BY, Document.iri(actorIri(system.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingARoleInASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-de");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.add(project, new NewRole("Pruefer", "Liest.", List.of(), DE), EN),
                document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Pruefer", DE))
                        .with(ActorRegisterCores.DESCRIPTION, text("Liest.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void renamingARoleWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("rename");
        Given<RoleDetail> role = pair.given(project -> addRole(project, "Reviewer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), "Approver", null, null, EN, EN),
                document().update(roleIri(role.submit()),
                        Node.fields().with(ArkarchVocabulary.NAME, text("Approver", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void changingTheDescriptionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("description");
        Given<RoleDetail> role = pair.given(project -> addRole(project, "Reviewer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), null, "Approves the orders.", null, EN, EN),
                document().update(roleIri(role.submit()),
                        Node.fields().with(ActorRegisterCores.DESCRIPTION, text("Approves the orders.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingNameAndDescriptionIntoASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("second-language");
        Given<RoleDetail> role = pair.given(project -> addRole(project, "Reviewer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), "Pruefer", "Liest.", null, DE, EN),
                document().update(roleIri(role.submit()), Node.fields()
                        .with(ArkarchVocabulary.NAME, text("Pruefer", DE))
                        .with(ActorRegisterCores.DESCRIPTION, text("Liest.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void fillingAnUnfilledRoleWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("fill");
        Given<Actor> actor = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));
        Given<RoleDetail> role = pair.given(project -> addRole(project, "Reviewer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), null, null,
                        List.of(actor.core().code().value()), EN, EN),
                document().addTo(roleIri(role.submit()), ArkprocVocabulary.FILLED_BY,
                        Document.iri(actorIri(actor.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void replacingTheOccupantsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("replace");
        Given<Actor> clerk = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));
        Given<Actor> billing = pair.given(project -> addActor(project, ActorType.SYSTEM, "Billing"));
        Given<RoleDetail> role = pair.given(project -> addRoleFilledBy(project, "Reviewer", clerk.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), null, null,
                        List.of(billing.core().code().value()), EN, EN),
                document().update(roleIri(role.submit()), Node.fields()
                        .with(ArkprocVocabulary.FILLED_BY, Document.iri(actorIri(billing.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingAnOccupantWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-occupant");
        Given<Actor> clerk = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));
        Given<Actor> billing = pair.given(project -> addActor(project, ActorType.SYSTEM, "Billing"));
        Given<RoleDetail> role = pair.given(project -> addRoleFilledBy(project, "Reviewer", clerk.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), null, null,
                        List.of(clerk.core().code().value(), billing.core().code().value()), EN, EN),
                document().addTo(roleIri(role.submit()), ArkprocVocabulary.FILLED_BY,
                        Document.iri(actorIri(billing.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void removingAnOccupantWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("remove-occupant");
        Given<Actor> clerk = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));
        Given<Actor> billing = pair.given(project -> addActor(project, ActorType.SYSTEM, "Billing"));
        Given<RoleDetail> role = pair.given(project -> roles.add(project, new NewRole("Reviewer", null,
                List.of(clerk.core().code().value(), billing.core().code().value()), EN), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), null, null,
                        List.of(clerk.core().code().value()), EN, EN),
                document().removeFrom(roleIri(role.submit()), ArkprocVocabulary.FILLED_BY,
                        Document.iri(actorIri(billing.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void clearingTheOccupantsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("clear-occupants");
        Given<Actor> clerk = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));
        Given<RoleDetail> role = pair.given(project -> addRoleFilledBy(project, "Reviewer", clerk.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), null, null, List.of(), EN, EN),
                document().clear(roleIri(role.submit()), ArkprocVocabulary.FILLED_BY));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void deletingARoleNoUseCaseNamesWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("delete");
        Given<RoleDetail> role = pair.given(project -> addRole(project, "Reviewer"));
        pair.givenOnBoth(project -> addRole(project, "Approver"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.delete(project, code(role.core())),
                document().delete(roleIri(role.submit())));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void deletingAFilledRoleLeavesItsActorBehindOnBothSides() {
        // given
        ProjectPair pair = store.pair("delete-filled");
        Given<Actor> clerk = pair.given(project -> addActor(project, ActorType.HUMAN, "Clerk"));
        Given<RoleDetail> role = pair.given(project -> addRoleFilledBy(project, "Reviewer", clerk.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.delete(project, code(role.core())),
                document().delete(roleIri(role.submit())));

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
                project -> roles.add(project, new NewRole("  ", "Reads.", List.of(), EN), EN),
                document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                        .with(ArkarchVocabulary.NAME, text("  ", EN))
                        .with(ActorRegisterCores.DESCRIPTION, text("Reads.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_NAME),
                Rejection.submit(SHAPE_NAME));
    }

    @Test
    void aNameOfOnlyNoBreakSpacesIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("no-break-space");
        Given<RoleDetail> role = pair.given(project -> addRole(project, "Reviewer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), NO_BREAK_SPACES, null, null, EN, EN),
                document().update(roleIri(role.submit()),
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
                project -> roles.add(project, new NewRole("R", null, List.of(), EN), EN),
                document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                        .with(ArkarchVocabulary.NAME, text("R", EN))));

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
                project -> roles.add(project, new NewRole("Reviewer", "  ", List.of(), EN), EN),
                document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Reviewer", EN))
                        .with(ActorRegisterCores.DESCRIPTION, text("  ", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_DESCRIPTION),
                Rejection.submit(SHAPE_DESCRIPTION));
    }

    @Test
    void anEmptyDescriptionOnUpdateIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("empty-description");
        Given<RoleDetail> role = pair.given(project -> addRole(project, "Reviewer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, code(role.core()), null, "", null, EN, EN),
                document().update(roleIri(role.submit()),
                        Node.fields().with(ActorRegisterCores.DESCRIPTION, text("", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_DESCRIPTION),
                Rejection.submit(SHAPE_DESCRIPTION));
    }

    @Test
    void aRoleFilledByAResourceThatIsNoActorIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("filled-by-role");
        Given<RoleDetail> other = pair.given(project -> addRole(project, "Approver"));

        // when: the core resolves an actor code, a document names an identity
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.add(project, new NewRole("Reviewer", null, List.of(code(other.core()).value()),
                        EN), EN),
                document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Reviewer", EN))
                        .with(ArkprocVocabulary.FILLED_BY, Document.iri(roleIri(other.submit())))));

        // then
        outcome.assertBothReject(Rejection.core(ActorNotFoundException.class, null),
                Rejection.submit(SHAPE_FILLED_BY));
    }

    @Test
    void anUnknownRoleIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unknown");
        pair.givenOnBoth(project -> addRole(project, "Reviewer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.update(project, new RoleCode("ROLE-9"), "Approver", null, null, EN, EN),
                document().update("ROLE-9", Node.fields().with(ArkarchVocabulary.NAME, text("Approver", EN))));

        // then
        outcome.assertBothReject(Rejection.core(RoleNotFoundException.class, null),
                Rejection.submit(ENGINE_NO_RESOURCE));
    }

    @Test
    void deletingARoleAUseCaseNamesIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("delete-named");
        Given<RoleDetail> role = pair.given(project -> addRole(project, "Reviewer"));
        pair.givenOnBoth(project -> useCases.add(project, new NewUseCase("Review order", "Order is checked", null,
                null, code(role.core()).value(), List.of(), null, null,
                List.of(new NewStep(1, "Open the order", List.of())), List.of(), null), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.delete(project, code(role.core())),
                document().delete(roleIri(role.submit())));

        // then
        outcome.assertBothReject(Rejection.core(RoleReferencedException.class, null),
                Rejection.submit(ENGINE_STILL_POINTS));
    }

    @Test
    void aRoleFilledByAnActorBreakingTheActorShapesIsRejectedByBoth() {
        // given: an actor stored past the gate with two names in one language
        ProjectPair pair = store.pair("legacy-actor");
        Given<Actor> actor = pair.given(project -> {
            Actor added = addActor(project, ActorType.HUMAN, "Clerk");
            cores.seedSecondNameInTheSameLanguage(project, actorIri(added), EN);
            return added;
        });

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> roles.add(project, new NewRole("Reviewer", null,
                        List.of(actor.core().code().value()), EN), EN),
                document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Reviewer", EN))
                        .with(ArkprocVocabulary.FILLED_BY, Document.iri(actorIri(actor.submit())))));

        // then
        outcome.assertBothReject(Rejection.core(WriteConstraintViolationException.class, SHAPE_UNIQUE_LANGUAGE),
                Rejection.submit(SHAPE_UNIQUE_LANGUAGE));
    }

    private static Actor addActor(final ProjectId project, final ActorType type, final String name) {
        return actors.add(project, new NewActor(type, name, "Files the orders.", EN), EN);
    }

    private static RoleDetail addRole(final ProjectId project, final String name) {
        return roles.add(project, new NewRole(name, "Reads the orders.", List.of(), EN), EN);
    }

    private static RoleDetail addRoleFilledBy(final ProjectId project, final String name, final Actor actor) {
        return roles.add(project, new NewRole(name, "Reads the orders.", List.of(actor.code().value()), EN), EN);
    }

    private static RoleCode code(final RoleDetail role) {
        return role.role().code();
    }

    private static String roleIri(final RoleDetail role) {
        return role.role().id().value().value();
    }

    private static String actorIri(final Actor actor) {
        return actor.id().value().value();
    }
}
