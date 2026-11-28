// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.integer;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.iri;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.NewMainStep;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.StepRealisesPatch;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.UseCaseCorrection;
import de.hauschel.arknet.uc.domain.ConstraintNotLinkedException;
import de.hauschel.arknet.uc.domain.MissingPrimaryRoleException;
import de.hauschel.arknet.uc.domain.RemovedPositions;
import de.hauschel.arknet.uc.domain.StepPositionNotFoundException;
import de.hauschel.arknet.uc.domain.StepTextPatch;
import de.hauschel.arknet.uc.domain.TermNotLinkedException;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.ul.domain.Term;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.terms.vocab.VocabDct;

/**
 * The use case written through {@link UseCaseService} and through {@code submit}: the same steps
 * leave the same content graph, and the same broken step is rejected by both sides, each side
 * naming its rule (kogn-io/arknet#744; rules in {@code docs/rules-inventory.md} section 1).
 *
 * <p>The author of a document writes {@code oslc_rm:satisfies} next to the steps' {@code stepRealises}
 * (the core derives it); every case that changes what a main step realises writes both.</p>
 *
 * <p>Not compared, because only one side can express it: {@code includesUseCase} and
 * {@code extendsUseCase}, which the core's port does not know.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UseCaseComparisonTest {

    private static final String EN = "en";
    private static final String DE = "de";

    private static final String TITLE = VocabDct.NAMESPACE + "title";
    private static final String NO_BREAK_SPACES = "\u00A0\u00A0\u00A0";

    /** The rules the rejections name: core exception messages and {@code sh:message} fragments. */
    private static final String CORE_BLANK_TITLE = "title must not be blank";
    private static final String CORE_BLANK_GOAL = "goal must not be blank";
    private static final String CORE_BLANK_STEP = "text must not be blank";
    private static final String CORE_NO_STEP = "a use case must have at least one step";
    private static final String CORE_NO_ROLE = "Role '";
    private static final String CORE_NO_REQUIREMENT = "Requirement '";
    private static final String CORE_NO_POSITION = "has no step at position";
    private static final String SHAPE_NOT_BLANK = "must contain a non-whitespace character";
    private static final String SHAPE_MAIN_FLOW = "needs a main flow";
    private static final String SHAPE_PRIMARY_ROLE = "exactly one arkreq:primaryRole";
    private static final String SHAPE_SUPPORTING_ROLE = "must reference a role";
    private static final String SHAPE_REALISES = "must point to an arkreq:Requirement";
    private static final String ENGINE_HOLDS_NO = "holds no";
    private static final String ENGINE_HOLDS_NO_CHILD = "holds no child at position";
    private static final String ENGINE_ALREADY_HOLDS = "already holds";

    /**
     * G-9a and G-9b: the core links a target the use case already has idempotently, {@code submit}
     * rejects it.
     */
    private static final DeviationList DEVIATIONS = DeviationList.of(
            Deviation.onlyCoreAccepts("G-9a", "docs/rules-inventory.md section 1 (usesTerm)",
                    "linking a term the use case already uses is idempotent in the core, rejected by submit"),
            Deviation.onlyCoreAccepts("G-9b", "docs/rules-inventory.md section 1 (constrainedBy)",
                    "linking a constraint the use case already has is idempotent in the core, rejected by "
                            + "submit"));

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static UseCaseNeighbourhood neighbours;
    private static UseCaseService useCases;
    private static StoreReader reader;
    private static Comparison comparison;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        neighbours = UseCaseNeighbourhood.over(store);
        useCases = neighbours.useCases();
        reader = new StoreReader(store.lifecycle());
        comparison = new Comparison(store, DEVIATIONS);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    // -- adding ------------------------------------------------------------------------------------

    @Test
    void addingAUseCaseWithEveryFieldWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-full");
        Given<Role> primary = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Role> first = pair.given(project -> neighbours.role(project, "Clerk"));
        Given<Role> second = pair.given(project -> neighbours.role(project, "Auditor"));
        Given<Requirement> one = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<Requirement> two = pair.given(project -> neighbours.requirement(project, "Invoices"));
        Given<Term> term = pair.given(project -> neighbours.term(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, new NewUseCase("Place order", "Buy a thing.", "Shop",
                        "A customer arrives.", primary.core().code().value(),
                        List.of(first.core().code().value(), second.core().code().value()), "The shop is open.",
                        "The order exists.",
                        List.of(new NewStep(1, "Open the form.", List.of(one.core().code().value())),
                                new NewStep(2, "Fill the form.", List.of(one.core().code().value(),
                                        two.core().code().value())),
                                new NewStep(3, "Send the form.", List.of())),
                        List.of("The form is invalid.", "The stock is empty."), EN,
                        List.of(term.core().code().value())), EN),
                document().create(useCaseNode(primary.submit())
                        .with(ArkreqVocabulary.SUPPORTING_ROLE, iri(roleIri(first.submit())))
                        .with(ArkreqVocabulary.SUPPORTING_ROLE, iri(roleIri(second.submit())))
                        .with(ArkreqVocabulary.MAIN_STEP, step(1, "Open the form.", EN, one.submit()))
                        .with(ArkreqVocabulary.MAIN_STEP, step(2, "Fill the form.", EN, one.submit(), two.submit()))
                        .with(ArkreqVocabulary.MAIN_STEP, step(3, "Send the form.", EN))
                        .with(ArkreqVocabulary.EXTENSION_STEP, step(1, "The form is invalid.", EN))
                        .with(ArkreqVocabulary.EXTENSION_STEP, step(2, "The stock is empty.", EN))
                        .with(ArkreqVocabulary.USES_TERM, iri(termIri(term.submit())))
                        .with(ArkreqVocabulary.SATISFIES, iri(requirementIri(one.submit())))
                        .with(ArkreqVocabulary.SATISFIES, iri(requirementIri(two.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingAUseCaseWithOnlyTheMandatoryFieldsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-minimal");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, new NewUseCase("Place order", "Buy a thing.", null, null,
                        role.core().code().value(), List.of(), null, null,
                        List.of(new NewStep(1, "Open the form.", List.of())), List.of(), EN), EN),
                document().create(Node.typed(ArkreqVocabulary.USE_CASE_TYPE)
                        .with(TITLE, text("Place order", EN))
                        .with(ArkreqVocabulary.USE_CASE_GOAL, text("Buy a thing.", EN))
                        .with(ArkreqVocabulary.PRIMARY_ROLE, iri(roleIri(role.submit())))
                        .with(ArkreqVocabulary.MAIN_STEP, step(1, "Open the form.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingAUseCaseWhoseStepRealisesARequirementWritesTheSatisfiesEdgeToo() {
        // given
        ProjectPair pair = store.pair("add-realises");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> requirement = pair.given(project -> neighbours.requirement(project, "Orders"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, newUseCase(role.core(),
                        new NewStep(1, "Open the form.", List.of(requirement.core().code().value()))), EN),
                document().create(useCaseNode(role.submit())
                        .with(ArkreqVocabulary.MAIN_STEP, step(1, "Open the form.", EN, requirement.submit()))
                        .with(ArkreqVocabulary.SATISFIES, iri(requirementIri(requirement.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingAUseCaseInAnotherLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-de");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, new NewUseCase("Bestellung aufgeben", "Etwas kaufen.", "Laden",
                        null, role.core().code().value(), List.of(), null, null,
                        List.of(new NewStep(1, "Das Formular oeffnen.", List.of())),
                        List.of("Das Formular ist ungueltig."), DE), EN),
                document().create(Node.typed(ArkreqVocabulary.USE_CASE_TYPE)
                        .with(TITLE, text("Bestellung aufgeben", DE))
                        .with(ArkreqVocabulary.USE_CASE_GOAL, text("Etwas kaufen.", DE))
                        .with(ArkreqVocabulary.DESIGN_SCOPE, text("Laden", DE))
                        .with(ArkreqVocabulary.PRIMARY_ROLE, iri(roleIri(role.submit())))
                        .with(ArkreqVocabulary.MAIN_STEP, step(1, "Das Formular oeffnen.", DE))
                        .with(ArkreqVocabulary.EXTENSION_STEP, step(1, "Das Formular ist ungueltig.", DE))));

        // then
        outcome.assertSameGraph();
    }

    // -- updating the texts ------------------------------------------------------------------------

    @Test
    void changingEveryTextWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("texts");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction()
                        .title("Cancel order").goal("Undo a purchase.").scope("Warehouse")
                        .trigger("A customer changes their mind.").precondition("An order exists.")
                        .postcondition("The order is gone.").build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(TITLE, text("Cancel order", EN))
                        .with(ArkreqVocabulary.USE_CASE_GOAL, text("Undo a purchase.", EN))
                        .with(ArkreqVocabulary.DESIGN_SCOPE, text("Warehouse", EN))
                        .with(ArkreqVocabulary.TRIGGER, text("A customer changes their mind.", EN))
                        .with(ArkreqVocabulary.USE_CASE_PRECONDITION, text("An order exists.", EN))
                        .with(ArkreqVocabulary.USE_CASE_POSTCONDITION, text("The order is gone.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void changingOneFieldWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("one-text");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction().goal("Undo a purchase.").build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(ArkreqVocabulary.USE_CASE_GOAL, text("Undo a purchase.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void fillingAnOptionalFieldTheUseCaseDidNotHaveWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("fill-optional");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addMinimalUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().scope("Shop").postcondition("The order exists.").build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(ArkreqVocabulary.DESIGN_SCOPE, text("Shop", EN))
                        .with(ArkreqVocabulary.USE_CASE_POSTCONDITION, text("The order exists.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingTheTextsIntoASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("second-language");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction()
                        .title("Bestellung aufgeben").goal("Etwas kaufen.").scope("Laden")
                        .trigger("Ein Kunde kommt.").precondition("Der Laden ist offen.")
                        .postcondition("Die Bestellung existiert.").language(DE).build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(TITLE, text("Bestellung aufgeben", DE))
                        .with(ArkreqVocabulary.USE_CASE_GOAL, text("Etwas kaufen.", DE))
                        .with(ArkreqVocabulary.DESIGN_SCOPE, text("Laden", DE))
                        .with(ArkreqVocabulary.TRIGGER, text("Ein Kunde kommt.", DE))
                        .with(ArkreqVocabulary.USE_CASE_PRECONDITION, text("Der Laden ist offen.", DE))
                        .with(ArkreqVocabulary.USE_CASE_POSTCONDITION, text("Die Bestellung existiert.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void correctingTheSecondLanguageLeavesTheFirstAndWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("correct-second-language");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));
        pair.givenOnBoth(project -> correct(project, useCase.core(),
                correction().title("Bestellung aufgeben").goal("Etwas kaufen.").language(DE).build()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction().goal("Alles kaufen.").language(DE).build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(ArkreqVocabulary.USE_CASE_GOAL, text("Alles kaufen.", DE))));

        // then
        outcome.assertSameGraph();
    }

    // -- updating the roles ------------------------------------------------------------------------

    @Test
    void replacingThePrimaryRoleWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("primary-role");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Role> other = pair.given(project -> neighbours.role(project, "Clerk"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().primaryRole(other.core().code().value()).build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(ArkreqVocabulary.PRIMARY_ROLE, iri(roleIri(other.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingSupportingRolesWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("supporting-add");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Role> first = pair.given(project -> neighbours.role(project, "Clerk"));
        Given<Role> second = pair.given(project -> neighbours.role(project, "Auditor"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction()
                        .supportingRoles(List.of(first.core().code().value(), second.core().code().value()))
                        .build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(ArkreqVocabulary.SUPPORTING_ROLE, iri(roleIri(first.submit())))
                        .with(ArkreqVocabulary.SUPPORTING_ROLE, iri(roleIri(second.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void replacingTheSupportingRolesWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("supporting-replace");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Role> first = pair.given(project -> neighbours.role(project, "Clerk"));
        Given<Role> second = pair.given(project -> neighbours.role(project, "Auditor"));
        Given<UseCase> useCase = pair.given(project -> useCases.add(project, new NewUseCase("Place order",
                "Buy a thing.", null, null, role.core().code().value(), List.of(first.core().code().value()),
                null, null, List.of(new NewStep(1, "Open the form.", List.of())), List.of(), EN), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().supportingRoles(List.of(second.core().code().value())).build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(ArkreqVocabulary.SUPPORTING_ROLE, iri(roleIri(second.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void clearingTheSupportingRolesWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("supporting-clear");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Role> first = pair.given(project -> neighbours.role(project, "Clerk"));
        Given<UseCase> useCase = pair.given(project -> useCases.add(project, new NewUseCase("Place order",
                "Buy a thing.", null, null, role.core().code().value(), List.of(first.core().code().value()),
                null, null, List.of(new NewStep(1, "Open the form.", List.of())), List.of(), EN), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction().supportingRoles(List.of()).build()),
                document().clear(useCaseIri(useCase.submit()), ArkreqVocabulary.SUPPORTING_ROLE));

        // then
        outcome.assertSameGraph();
    }

    // -- updating the steps ------------------------------------------------------------------------

    @Test
    void appendingMainStepsThatRealiseANewRequirementWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("append-new-requirement");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> requirement = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction()
                        .newMainSteps(List.of(new NewMainStep("Check the stock.", List.of()),
                                new NewMainStep("Send the form.", List.of(requirement.core().code().value()))))
                        .build()),
                document()
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                                plainStep("Check the stock.", EN))
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                                plainStep("Send the form.", EN, requirement.submit()))
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.SATISFIES,
                                iri(requirementIri(requirement.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void appendingAMainStepThatRealisesAnAlreadySatisfiedRequirementLeavesSatisfiesAlone() {
        // given
        ProjectPair pair = store.pair("append-same-requirement");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> requirement = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(),
                new NewStep(1, "Open the form.", List.of(requirement.core().code().value()))));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction()
                        .newMainSteps(List.of(new NewMainStep("Send the form.",
                                List.of(requirement.core().code().value()))))
                        .build()),
                document().addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                        plainStep("Send the form.", EN, requirement.submit())));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void appendingAMainStepInASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("append-de");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction()
                        .newMainSteps(List.of(new NewMainStep("Das Formular senden.", List.of())))
                        .language(DE).build()),
                document().addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                        plainStep("Das Formular senden.", DE)));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void removingAMainStepInTheMiddleRenumbersTheSameWay() {
        // given
        ProjectPair pair = store.pair("remove-middle");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(), threeSteps()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().removeMainStepPositions(new RemovedPositions(Set.of(2))).build()),
                document().removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 2));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void removingTheOnlyStepThatRealisesARequirementDropsTheSatisfiesEdgeToo() {
        // given
        ProjectPair pair = store.pair("remove-realising");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> requirement = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(),
                new NewStep(1, "Open the form.", List.of(requirement.core().code().value())),
                new NewStep(2, "Fill the form.", List.of())));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().removeMainStepPositions(new RemovedPositions(Set.of(1))).build()),
                document()
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 1)
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.SATISFIES,
                                iri(requirementIri(requirement.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void removingOneOfTwoStepsThatRealiseARequirementKeepsTheSatisfiesEdge() {
        // given
        ProjectPair pair = store.pair("remove-one-of-two");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> requirement = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(),
                new NewStep(1, "Open the form.", List.of(requirement.core().code().value())),
                new NewStep(2, "Fill the form.", List.of(requirement.core().code().value()))));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().removeMainStepPositions(new RemovedPositions(Set.of(1))).build()),
                document().removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 1));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void removingAndAppendingMainStepsInOneUpdateWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("remove-and-append");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> first = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<Requirement> second = pair.given(project -> neighbours.requirement(project, "Invoices"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(),
                new NewStep(1, "Open the form.", List.of(first.core().code().value())),
                new NewStep(2, "Fill the form.", List.of()),
                new NewStep(3, "Send the form.", List.of())));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction().title("Cancel order")
                        .removeMainStepPositions(new RemovedPositions(Set.of(1)))
                        .newMainSteps(List.of(new NewMainStep("Bill it.", List.of(second.core().code().value()))))
                        .build()),
                document()
                        .update(useCaseIri(useCase.submit()), Node.fields().with(TITLE, text("Cancel order", EN)))
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 1)
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                                plainStep("Bill it.", EN, second.submit()))
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.SATISFIES,
                                iri(requirementIri(first.submit())))
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.SATISFIES,
                                iri(requirementIri(second.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void patchingAStepTextWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("patch-step-text");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(), threeSteps()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().stepTextPatches(List.of(new StepTextPatch(2, "Fill in the form."))).build()),
                patchStepTextAt2(useCase.submit()));

        // then
        outcome.assertSameGraph();
    }

    /**
     * Intended difference of the child identity (G-8): the core patches the text in place and keeps the
     * step's IRI, {@code submit} has no child update and removes the child and adds a new one. The
     * canonical graph names children by position, so this is the one place the identity is pinned.
     */
    @Test
    void patchingAStepTextKeepsTheCoreChildAndReplacesTheSubmitChild() {
        // given
        ProjectPair pair = store.pair("patch-identity");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(), threeSteps()));
        Map<Integer, String> coreBefore = stepIris(pair.core(), useCase.core());
        Map<Integer, String> submitBefore = stepIris(pair.submit(), useCase.submit());

        // when
        comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().stepTextPatches(List.of(new StepTextPatch(2, "Fill in the form."))).build()),
                patchStepTextAt2(useCase.submit())).assertSameGraph();

        // then: the core patched in place; submit kept positions 1 and 3 and minted a new child at 2
        assertEquals(coreBefore, stepIris(pair.core(), useCase.core()));
        Map<Integer, String> submitAfter = stepIris(pair.submit(), useCase.submit());
        assertEquals(submitBefore.get(1), submitAfter.get(1));
        assertEquals(submitBefore.get(3), submitAfter.get(3));
        assertNotEquals(submitBefore.get(2), submitAfter.get(2));
        assertEquals(Set.of(1, 2, 3), submitAfter.keySet());
    }

    @Test
    void patchingTheTextOfAStepThatRealisesARequirementWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("patch-realising-step");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> requirement = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(),
                new NewStep(1, "Open the form.", List.of(requirement.core().code().value())),
                new NewStep(2, "Fill the form.", List.of())));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().stepTextPatches(List.of(new StepTextPatch(1, "Show the form."))).build()),
                document()
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 1)
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                                step(1, "Show the form.", EN, requirement.submit())));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingAStepTextKeepsBothLanguagesOnTheNewChild() {
        // given
        ProjectPair pair = store.pair("patch-step-de");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(), threeSteps()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction()
                        .stepTextPatches(List.of(new StepTextPatch(2, "Das Formular ausfuellen.")))
                        .language(DE).build()),
                document()
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 2)
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, Node.fields()
                                .with(ArkreqVocabulary.POSITION, integer(2))
                                .with(ArkreqVocabulary.STEP_TEXT, text("Fill the form.", EN))
                                .with(ArkreqVocabulary.STEP_TEXT, text("Das Formular ausfuellen.", DE))
                                .value()));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void movingAStepToAnotherRequirementWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("realises-patch");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> first = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<Requirement> second = pair.given(project -> neighbours.requirement(project, "Invoices"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(),
                new NewStep(1, "Open the form.", List.of(first.core().code().value())),
                new NewStep(2, "Fill the form.", List.of())));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction()
                        .stepRealisesPatches(List.of(new StepRealisesPatch(1, List.of(second.core().code().value()))))
                        .build()),
                document()
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 1)
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                                step(1, "Open the form.", EN, second.submit()))
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.SATISFIES,
                                iri(requirementIri(first.submit())))
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.SATISFIES,
                                iri(requirementIri(second.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void makingAStepRealiseNothingAnyMoreWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("realises-clear");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> requirement = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(),
                new NewStep(1, "Open the form.", List.of(requirement.core().code().value())),
                new NewStep(2, "Fill the form.", List.of())));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().stepRealisesPatches(List.of(new StepRealisesPatch(1, List.of()))).build()),
                document()
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 1)
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                                step(1, "Open the form.", EN))
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.SATISFIES,
                                iri(requirementIri(requirement.submit()))));

        // then
        outcome.assertSameGraph();
    }

    // -- updating the extensions -------------------------------------------------------------------

    @Test
    void settingTheExtensionsOfAUseCaseWithoutAnyWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("extensions-set");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().extensions(List.of("The form is invalid.", "The stock is empty.")).build()),
                document()
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.EXTENSION_STEP,
                                plainStep("The form is invalid.", EN))
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.EXTENSION_STEP,
                                plainStep("The stock is empty.", EN)));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void replacingTheExtensionsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("extensions-replace");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCaseWithExtensions(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().extensions(List.of("The payment fails.")).build()),
                document()
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.EXTENSION_STEP, 1)
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.EXTENSION_STEP, 2)
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.EXTENSION_STEP,
                                plainStep("The payment fails.", EN)));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void clearingTheExtensionsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("extensions-clear");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCaseWithExtensions(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction().extensions(List.of()).build()),
                document()
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.EXTENSION_STEP, 1)
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.EXTENSION_STEP, 2));

        // then
        outcome.assertSameGraph();
    }

    // -- linking -----------------------------------------------------------------------------------

    @Test
    void replacingTheUsedTermsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("replace-terms");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Term> first = pair.given(project -> neighbours.term(project, "Order"));
        Given<Term> second = pair.given(project -> neighbours.term(project, "Invoice"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));
        pair.givenOnBoth(project -> useCases.linkTerm(project, useCase.core().code(),
                first.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().usesTermCodes(List.of(second.core().code().value())).build()),
                document()
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.USES_TERM,
                                iri(termIri(first.submit())))
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.USES_TERM,
                                iri(termIri(second.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void clearingTheUsedTermsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("clear-terms");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Term> term = pair.given(project -> neighbours.term(project, "Order"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));
        pair.givenOnBoth(project -> useCases.linkTerm(project, useCase.core().code(),
                term.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction().usesTermCodes(List.of()).build()),
                document().clear(useCaseIri(useCase.submit()), ArkreqVocabulary.USES_TERM));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingATermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("link-term");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Term> term = pair.given(project -> neighbours.term(project, "Order"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.linkTerm(project, useCase.core().code(), term.core().code().value(), EN),
                document().addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.USES_TERM,
                        iri(termIri(term.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void unlinkingATermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("unlink-term");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Term> term = pair.given(project -> neighbours.term(project, "Order"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));
        pair.givenOnBoth(project -> useCases.linkTerm(project, useCase.core().code(),
                term.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.unlinkTerm(project, useCase.core().code(), term.core().code().value(), EN),
                document().removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.USES_TERM,
                        iri(termIri(term.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingAConstraintWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("link-constraint");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Constraint> constraint = pair.given(project -> neighbours.constraint(project, "Java 25"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.linkConstraint(project, useCase.core().code(),
                        constraint.core().code().value(), EN),
                document().addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.CONSTRAINED_BY,
                        iri(constraintIri(constraint.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void unlinkingAConstraintWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("unlink-constraint");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Constraint> constraint = pair.given(project -> neighbours.constraint(project, "Java 25"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));
        pair.givenOnBoth(project -> useCases.linkConstraint(project, useCase.core().code(),
                constraint.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.unlinkConstraint(project, useCase.core().code(),
                        constraint.core().code().value(), EN),
                document().removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.CONSTRAINED_BY,
                        iri(constraintIri(constraint.submit()))));

        // then
        outcome.assertSameGraph();
    }

    // -- deleting ----------------------------------------------------------------------------------

    @Test
    void deletingAUseCaseWithStepsAndEdgesWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("delete");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Requirement> requirement = pair.given(project -> neighbours.requirement(project, "Orders"));
        Given<Term> term = pair.given(project -> neighbours.term(project, "Order"));
        Given<Constraint> constraint = pair.given(project -> neighbours.constraint(project, "Java 25"));
        Given<UseCase> useCase = pair.given(project -> useCases.add(project, new NewUseCase("Place order",
                "Buy a thing.", null, null, role.core().code().value(), List.of(), null, null,
                List.of(new NewStep(1, "Open the form.", List.of(requirement.core().code().value()))),
                List.of("The form is invalid."), EN, List.of(term.core().code().value())), EN));
        pair.givenOnBoth(project -> useCases.linkConstraint(project, useCase.core().code(),
                constraint.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.delete(project, useCase.core().code()),
                document().delete(useCaseIri(useCase.submit())));

        // then
        outcome.assertSameGraph();
    }

    // -- rejecting ---------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void aBlankTitleOnAddIsRejectedByBoth(final String blank) {
        // given
        ProjectPair pair = store.pair("blank-title");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, new NewUseCase(blank, "Buy a thing.", null, null,
                        role.core().code().value(), List.of(), null, null,
                        List.of(new NewStep(1, "Open the form.", List.of())), List.of(), EN), EN),
                document().create(useCaseNode(role.submit(), blank, "Buy a thing.")
                        .with(ArkreqVocabulary.MAIN_STEP, step(1, "Open the form.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_TITLE),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void aBlankGoalOnAddIsRejectedByBoth(final String blank) {
        // given
        ProjectPair pair = store.pair("blank-goal");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, new NewUseCase("Place order", blank, null, null,
                        role.core().code().value(), List.of(), null, null,
                        List.of(new NewStep(1, "Open the form.", List.of())), List.of(), EN), EN),
                document().create(useCaseNode(role.submit(), "Place order", blank)
                        .with(ArkreqVocabulary.MAIN_STEP, step(1, "Open the form.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_GOAL),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void aBlankTitleOnUpdateIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-title-update");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction().title("   ").build()),
                document().update(useCaseIri(useCase.submit()), Node.fields().with(TITLE, text("   ", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_TITLE),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n\t"})
    void aBlankStepTextOnAddIsRejectedByBoth(final String blank) {
        // given
        ProjectPair pair = store.pair("blank-step");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, newUseCase(role.core(), new NewStep(1, blank, List.of())), EN),
                document().create(useCaseNode(role.submit()).with(ArkreqVocabulary.MAIN_STEP,
                        step(1, blank, EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_STEP),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void aBlankAppendedStepOnUpdateIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-appended-step");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().newMainSteps(List.of(new NewMainStep("   ", List.of()))).build()),
                document().addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                        plainStep("   ", EN)));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_STEP),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void aUseCaseWithoutAMainStepIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("no-step");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, newUseCase(role.core()), EN),
                document().create(useCaseNode(role.submit())));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_NO_STEP),
                Rejection.submit(SHAPE_MAIN_FLOW));
    }

    @Test
    void removingEveryMainStepIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("remove-all-steps");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(),
                new NewStep(1, "Open the form.", List.of()), new NewStep(2, "Fill the form.", List.of())));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().removeMainStepPositions(new RemovedPositions(Set.of(1, 2))).build()),
                document()
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 1)
                        .removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 2));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_NO_STEP),
                Rejection.submit(SHAPE_MAIN_FLOW));
    }

    @Test
    void aPrimaryRoleThatIsNoRoleOnAddIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("primary-no-role-add");
        Given<Actor> actor = pair.given(project -> neighbours.actor(project, "Alice"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, new NewUseCase("Place order", "Buy a thing.", null, null,
                        actor.core().code().value(), List.of(), null, null,
                        List.of(new NewStep(1, "Open the form.", List.of())), List.of(), EN), EN),
                document().create(Node.typed(ArkreqVocabulary.USE_CASE_TYPE)
                        .with(TITLE, text("Place order", EN))
                        .with(ArkreqVocabulary.USE_CASE_GOAL, text("Buy a thing.", EN))
                        .with(ArkreqVocabulary.PRIMARY_ROLE, iri(actor.submit().id().value().value()))
                        .with(ArkreqVocabulary.MAIN_STEP, step(1, "Open the form.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(UnresolvedReferenceException.class, CORE_NO_ROLE),
                Rejection.submit(SHAPE_PRIMARY_ROLE));
    }

    @Test
    void aPrimaryRoleThatIsNoRoleOnUpdateIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("primary-no-role-update");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Actor> actor = pair.given(project -> neighbours.actor(project, "Alice"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().primaryRole(actor.core().code().value()).build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(ArkreqVocabulary.PRIMARY_ROLE, iri(actor.submit().id().value().value()))));

        // then
        outcome.assertBothReject(Rejection.core(UnresolvedReferenceException.class, CORE_NO_ROLE),
                Rejection.submit(SHAPE_PRIMARY_ROLE));
    }

    @Test
    void aSupportingRoleThatIsNoRoleIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("supporting-no-role");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Actor> actor = pair.given(project -> neighbours.actor(project, "Alice"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().supportingRoles(List.of(actor.core().code().value())).build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(ArkreqVocabulary.SUPPORTING_ROLE, iri(actor.submit().id().value().value()))));

        // then
        outcome.assertBothReject(Rejection.core(UnresolvedReferenceException.class, CORE_NO_ROLE),
                Rejection.submit(SHAPE_SUPPORTING_ROLE));
    }

    /**
     * A use case stored without a primary role is an old form (written before the edge existed). The
     * core shows it with a placeholder role and refuses every write that leaves it; the shape does the
     * same to a document that reaches it. Only that refusal is compared, not the old form itself.
     */
    @Test
    void anUpdateOfAUseCaseStoredWithoutAPrimaryRoleIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("legacy-no-primary-role");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));
        removePrimaryRolePastTheGate(pair.core(), useCase.core());
        removePrimaryRolePastTheGate(pair.submit(), useCase.submit());

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction().title("Cancel order").build()),
                document().update(useCaseIri(useCase.submit()), Node.fields()
                        .with(TITLE, text("Cancel order", EN))));

        // then
        outcome.assertBothReject(Rejection.core(MissingPrimaryRoleException.class, null),
                Rejection.submit(SHAPE_PRIMARY_ROLE));
    }

    @Test
    void aStepThatRealisesNoRequirementOnAddIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("realises-no-requirement-add");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Term> term = pair.given(project -> neighbours.term(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, newUseCase(role.core(),
                        new NewStep(1, "Open the form.", List.of(term.core().code().value()))), EN),
                document().create(useCaseNode(role.submit())
                        .with(ArkreqVocabulary.MAIN_STEP, stepRealising(1, "Open the form.", termIri(term.submit())))
                        .with(ArkreqVocabulary.SATISFIES, iri(termIri(term.submit())))));

        // then
        outcome.assertBothReject(Rejection.core(UnresolvedReferenceException.class, CORE_NO_REQUIREMENT),
                Rejection.submit(SHAPE_REALISES));
    }

    @Test
    void anAppendedStepThatRealisesNoRequirementIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("realises-no-requirement-append");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Term> term = pair.given(project -> neighbours.term(project, "Order"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(), correction()
                        .newMainSteps(List.of(new NewMainStep("Send the form.", List.of(term.core().code().value()))))
                        .build()),
                document()
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP,
                                Node.fields().with(ArkreqVocabulary.STEP_TEXT, text("Send the form.", EN))
                                        .with(ArkreqVocabulary.STEP_REALISES, iri(termIri(term.submit()))).value())
                        .addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.SATISFIES,
                                iri(termIri(term.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(UnresolvedReferenceException.class, CORE_NO_REQUIREMENT),
                Rejection.submit(SHAPE_REALISES));
    }

    @Test
    void patchingAStepTextAtAMissingPositionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("patch-missing-position");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(), threeSteps()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().stepTextPatches(List.of(new StepTextPatch(5, "Fill in the form."))).build()),
                document().removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 5));

        // then
        outcome.assertBothReject(Rejection.core(StepPositionNotFoundException.class, CORE_NO_POSITION),
                Rejection.submit(ENGINE_HOLDS_NO_CHILD));
    }

    @Test
    void removingAMainStepAtAMissingPositionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("remove-missing-position");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(), threeSteps()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().removeMainStepPositions(new RemovedPositions(Set.of(5))).build()),
                document().removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 5));

        // then
        outcome.assertBothReject(Rejection.core(StepPositionNotFoundException.class, CORE_NO_POSITION),
                Rejection.submit(ENGINE_HOLDS_NO_CHILD));
    }

    @Test
    void patchingTheRealisesOfAMissingStepPositionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("realises-missing-position");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core(), threeSteps()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> correct(project, useCase.core(),
                        correction().stepRealisesPatches(List.of(new StepRealisesPatch(5, List.of()))).build()),
                document().removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.MAIN_STEP, 5));

        // then
        outcome.assertBothReject(Rejection.core(StepPositionNotFoundException.class, CORE_NO_POSITION),
                Rejection.submit(ENGINE_HOLDS_NO_CHILD));
    }

    @Test
    void unlinkingATermTheUseCaseDoesNotUseIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unlink-term-not-linked");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Term> term = pair.given(project -> neighbours.term(project, "Order"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.unlinkTerm(project, useCase.core().code(), term.core().code().value(), EN),
                document().removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.USES_TERM,
                        iri(termIri(term.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(TermNotLinkedException.class, null),
                Rejection.submit(ENGINE_HOLDS_NO));
    }

    @Test
    void unlinkingAConstraintTheUseCaseDoesNotHaveIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unlink-constraint-not-linked");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Constraint> constraint = pair.given(project -> neighbours.constraint(project, "Java 25"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.unlinkConstraint(project, useCase.core().code(),
                        constraint.core().code().value(), EN),
                document().removeFrom(useCaseIri(useCase.submit()), ArkreqVocabulary.CONSTRAINED_BY,
                        iri(constraintIri(constraint.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(ConstraintNotLinkedException.class, null),
                Rejection.submit(ENGINE_HOLDS_NO));
    }

    /**
     * {@code String#isBlank()}, the core's own test, lets a text of no-break spaces pass; the write gate of
     * the typed adapter holds the same shapes as {@code submit}, so both sides refuse it, naming one rule.
     */
    @Test
    void aTitleOfOnlyNoBreakSpacesIsRejectedByBothAtTheGate() {
        // given
        ProjectPair pair = store.pair("nbsp-title");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, new NewUseCase(NO_BREAK_SPACES, "Buy a thing.", null, null,
                        role.core().code().value(), List.of(), null, null,
                        List.of(new NewStep(1, "Open the form.", List.of())), List.of(), EN), EN),
                document().create(useCaseNode(role.submit(), NO_BREAK_SPACES, "Buy a thing.")
                        .with(ArkreqVocabulary.MAIN_STEP, step(1, "Open the form.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(WriteConstraintViolationException.class, SHAPE_NOT_BLANK),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void aStepTextOfOnlyNoBreakSpacesIsRejectedByBothAtTheGate() {
        // given
        ProjectPair pair = store.pair("nbsp-step");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.add(project, newUseCase(role.core(), new NewStep(1, NO_BREAK_SPACES,
                        List.of())), EN),
                document().create(useCaseNode(role.submit()).with(ArkreqVocabulary.MAIN_STEP,
                        step(1, NO_BREAK_SPACES, EN))));

        // then
        outcome.assertBothReject(Rejection.core(WriteConstraintViolationException.class, SHAPE_NOT_BLANK),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    // -- intended differences ----------------------------------------------------------------------

    @Test
    void linkingATermTheUseCaseAlreadyUsesIsIdempotentInTheCoreAndRejectedBySubmit() {
        // given
        ProjectPair pair = store.pair("link-term-twice");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Term> term = pair.given(project -> neighbours.term(project, "Order"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));
        pair.givenOnBoth(project -> useCases.linkTerm(project, useCase.core().code(),
                term.core().code().value(), EN));
        CanonicalGraph coreBefore = store.graph(pair.core());

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.linkTerm(project, useCase.core().code(), term.core().code().value(), EN),
                document().addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.USES_TERM,
                        iri(termIri(term.submit()))));

        // then
        outcome.assertDeviation("G-9a", Rejection.submit(ENGINE_ALREADY_HOLDS));
        assertEquals(coreBefore, store.graph(pair.core()), "the core link is a no-op, not a second edge");
    }

    @Test
    void linkingAConstraintTheUseCaseAlreadyHasIsIdempotentInTheCoreAndRejectedBySubmit() {
        // given
        ProjectPair pair = store.pair("link-constraint-twice");
        Given<Role> role = pair.given(project -> neighbours.role(project, "Customer"));
        Given<Constraint> constraint = pair.given(project -> neighbours.constraint(project, "Java 25"));
        Given<UseCase> useCase = pair.given(project -> addUseCase(project, role.core()));
        pair.givenOnBoth(project -> useCases.linkConstraint(project, useCase.core().code(),
                constraint.core().code().value(), EN));
        CanonicalGraph coreBefore = store.graph(pair.core());

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> useCases.linkConstraint(project, useCase.core().code(),
                        constraint.core().code().value(), EN),
                document().addTo(useCaseIri(useCase.submit()), ArkreqVocabulary.CONSTRAINED_BY,
                        iri(constraintIri(constraint.submit()))));

        // then
        outcome.assertDeviation("G-9b", Rejection.submit(ENGINE_ALREADY_HOLDS));
        assertEquals(coreBefore, store.graph(pair.core()), "the core link is a no-op, not a second edge");
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void everyListedDeviationIsNamedByACase() {
        DEVIATIONS.assertEveryEntryUsed();
    }

    // -- steps and documents -----------------------------------------------------------------------

    private static UseCaseCorrection.Builder correction() {
        return UseCaseCorrection.builder().language(EN);
    }

    private static UseCase correct(final ProjectId project, final UseCase useCase,
            final UseCaseCorrection correction) {
        return useCases.update(project, useCase.code(), correction, EN);
    }

    private static NewUseCase newUseCase(final Role primary, final NewStep... steps) {
        return new NewUseCase("Place order", "Buy a thing.", "Shop", "A customer arrives.",
                primary.code().value(), List.of(), "The shop is open.", "The order exists.", List.of(steps),
                List.of(), EN);
    }

    private static UseCase addUseCase(final ProjectId project, final Role primary) {
        return addUseCase(project, primary, new NewStep(1, "Open the form.", List.of()));
    }

    private static UseCase addUseCase(final ProjectId project, final Role primary, final NewStep... steps) {
        return useCases.add(project, newUseCase(primary, steps), EN);
    }

    private static NewStep[] threeSteps() {
        return new NewStep[] {new NewStep(1, "Open the form.", List.of()),
                new NewStep(2, "Fill the form.", List.of()), new NewStep(3, "Send the form.", List.of())};
    }

    private static UseCase addMinimalUseCase(final ProjectId project, final Role primary) {
        return useCases.add(project, new NewUseCase("Place order", "Buy a thing.", null, null,
                primary.code().value(), List.of(), null, null,
                List.of(new NewStep(1, "Open the form.", List.of())), List.of(), EN), EN);
    }

    private static UseCase addUseCaseWithExtensions(final ProjectId project, final Role primary) {
        return useCases.add(project, new NewUseCase("Place order", "Buy a thing.", "Shop", "A customer arrives.",
                primary.code().value(), List.of(), "The shop is open.", "The order exists.",
                List.of(new NewStep(1, "Open the form.", List.of())),
                List.of("The form is invalid.", "The stock is empty."), EN), EN);
    }

    /** The use case of {@link #newUseCase}, as a document node without steps. */
    private static Node useCaseNode(final Role primary) {
        return useCaseNode(primary, "Place order", "Buy a thing.");
    }

    private static Node useCaseNode(final Role primary, final String title, final String goal) {
        return Node.typed(ArkreqVocabulary.USE_CASE_TYPE)
                .with(TITLE, text(title, EN))
                .with(ArkreqVocabulary.USE_CASE_GOAL, text(goal, EN))
                .with(ArkreqVocabulary.DESIGN_SCOPE, text("Shop", EN))
                .with(ArkreqVocabulary.TRIGGER, text("A customer arrives.", EN))
                .with(ArkreqVocabulary.USE_CASE_PRECONDITION, text("The shop is open.", EN))
                .with(ArkreqVocabulary.USE_CASE_POSTCONDITION, text("The order exists.", EN))
                .with(ArkreqVocabulary.PRIMARY_ROLE, iri(roleIri(primary)));
    }

    private static Document.Value step(final int position, final String text, final String language,
            final Requirement... realises) {
        Node node = Node.fields()
                .with(ArkreqVocabulary.POSITION, integer(position))
                .with(ArkreqVocabulary.STEP_TEXT, text(text, language));
        for (Requirement requirement : realises) {
            node.with(ArkreqVocabulary.STEP_REALISES, iri(requirementIri(requirement)));
        }
        return node.value();
    }

    private static Document.Value stepRealising(final int position, final String text, final String target) {
        return Node.fields()
                .with(ArkreqVocabulary.POSITION, integer(position))
                .with(ArkreqVocabulary.STEP_TEXT, text(text, EN))
                .with(ArkreqVocabulary.STEP_REALISES, iri(target))
                .value();
    }

    private static Document.Value plainStep(final String text, final String language,
            final Requirement... realises) {
        Node node = Node.fields().with(ArkreqVocabulary.STEP_TEXT, text(text, language));
        for (Requirement requirement : realises) {
            node.with(ArkreqVocabulary.STEP_REALISES, iri(requirementIri(requirement)));
        }
        return node.value();
    }

    private static Document patchStepTextAt2(final UseCase submitSide) {
        return document()
                .removeFrom(useCaseIri(submitSide), ArkreqVocabulary.MAIN_STEP, 2)
                .addTo(useCaseIri(submitSide), ArkreqVocabulary.MAIN_STEP, step(2, "Fill in the form.", EN));
    }

    /** The IRI of each main step by its position. */
    private static Map<Integer, String> stepIris(final ProjectId project, final UseCase useCase) {
        Map<Integer, String> byPosition = new TreeMap<>();
        reader.outgoing(project, useCaseIri(useCase)).stream()
                .filter(triple -> ArkreqVocabulary.MAIN_STEP.equals(triple.predicate()))
                .map(triple -> ((RdfNode.Resource) triple.object()).iri())
                .forEach(child -> byPosition.put(positionOf(project, child), child));
        return byPosition;
    }

    private static int positionOf(final ProjectId project, final String child) {
        return reader.outgoing(project, child).stream()
                .filter(triple -> ArkreqVocabulary.POSITION.equals(triple.predicate()))
                .map(triple -> Integer.parseInt(((RdfNode.Literal) triple.object()).lexicalForm()))
                .findFirst().orElseThrow();
    }

    private static void removePrimaryRolePastTheGate(final ProjectId project, final UseCase useCase) {
        String subject = useCaseIri(useCase);
        try (DatasetHandle handle = store.lifecycle().acquire(new DatasetId(project.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("DELETE WHERE { GRAPH <" + ContentGraph.GRAPH_IRI + "> { <" + subject + "> <"
                        + ArkreqVocabulary.PRIMARY_ROLE + "> ?role } }");
                return null;
            });
        }
    }

    private static String useCaseIri(final UseCase useCase) {
        return useCase.id().value().value();
    }

    private static String roleIri(final Role role) {
        return role.id().value().value();
    }

    private static String requirementIri(final Requirement requirement) {
        return requirement.id().value().value();
    }

    private static String termIri(final Term term) {
        return term.id().value().value();
    }

    private static String constraintIri(final Constraint constraint) {
        return constraint.id().value().value();
    }
}
