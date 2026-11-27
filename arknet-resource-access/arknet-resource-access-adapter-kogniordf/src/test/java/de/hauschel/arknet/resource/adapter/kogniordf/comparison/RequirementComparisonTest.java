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

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.pr.shared.RequirementCode;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfConstraintRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfTermLookup;
import de.hauschel.arknet.req.application.ConstraintService;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.domain.AcceptanceCriterionPositionNotFoundException;
import de.hauschel.arknet.req.domain.AcceptanceCriterionTextPatch;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintNotLinkedException;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.RemovedPositions;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermNotLinkedException;
import de.hauschel.arknet.req.domain.UnsupportedRequirementStatusException;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.Term;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.terms.vocab.VocabDct;

/**
 * The requirement (FR, NFR) written through {@link RequirementService} and through {@code submit}:
 * the same steps leave the same content graph, and the same broken step is rejected by both sides,
 * each side naming its rule (kogn-io/arknet#744; rules in {@code docs/rules-inventory.md} section 1).
 *
 * <p>Not compared, because only one side can express it: a quality category added after the create,
 * a change of the requirement type and a priority outside MoSCoW (the core's {@link Priority} is an
 * enum). Those are pinned by {@code ProductRequirementsRulesRealStoreTest}. The delete guards for
 * edges that point at a requirement come from use cases and decisions and are compared with those
 * types.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RequirementComparisonTest {

    private static final String EN = "en";
    private static final String DE = "de";

    private static final String TITLE = VocabDct.NAMESPACE + "title";
    private static final String DESCRIPTION = VocabDct.NAMESPACE + "description";

    /** The rules the rejections name: core exception messages and {@code sh:message} fragments. */
    private static final String CORE_BLANK_TITLE = "title must not be blank";
    private static final String CORE_BLANK_DESCRIPTION = "description must not be blank";
    private static final String CORE_BLANK_RATIONALE = "rationale must not be blank";
    private static final String CORE_DUPLICATE_CRITERIA = "acceptanceCriteria must not contain duplicate entries";
    private static final String CORE_CRITERIA_EMPTY = "acceptanceCriteria must not be empty";
    private static final String CORE_BLANK_CRITERION = "text must not be blank";
    private static final String CORE_QUALITY_ONLY_NFR = "qualityCategory is only allowed";
    private static final String SHAPE_NOT_BLANK = "must contain a non-whitespace character";
    private static final String SHAPE_CRITERIA_DIFFER = "must not carry the same arkreq:criterionText";
    private static final String SHAPE_NO_QUALITY = "must not carry arkreq:qualityCategory";
    private static final String SHAPE_NEEDS_CRITERION = "needs at least one arkreq:acceptanceCriterion";
    private static final String ENGINE_NO_RESOURCE = "No resource found for id";
    private static final String ENGINE_HOLDS_NO = "holds no";
    private static final String ENGINE_HOLDS_NO_CHILD = "holds no child at position";
    private static final String ENGINE_ALREADY_HOLDS = "already holds";
    private static final String ENGINE_TRANSITION = "allows it; from this status it leads to";

    /** What differs on purpose: the core links a target the requirement already has idempotently, submit rejects it. */
    private static final DeviationList DEVIATIONS = DeviationList.of(
            Deviation.onlyCoreAccepts("G-9a", "docs/rules-inventory.md section 1 (usesTerm)",
                    "linking a term the requirement already uses is idempotent in the core, rejected by submit"),
            Deviation.onlyCoreAccepts("G-9b", "docs/rules-inventory.md section 1 (constrainedBy)",
                    "linking a constraint the requirement already has is idempotent in the core, rejected by "
                            + "submit"));

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static RequirementService requirements;
    private static ConstraintService constraints;
    private static TermService terms;
    private static StoreReader reader;
    private static Comparison comparison;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        VersionedWriteFunnel funnel = KognioRdfRequirementRepositoryFactory.buildFunnel(store.lifecycle(), DisplayLocale.DEFAULT);
        ConstraintRepository constraintRepository = KognioRdfConstraintRepositoryFactory.over(store.lifecycle(),
                DisplayLocale.DEFAULT, funnel);
        requirements = new RequirementService(
                KognioRdfRequirementRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT, funnel),
                new UuidResourceIdFactory(), new KognioRdfTermLookup(store.lifecycle()), constraintRepository,
                List::of);
        constraints = new ConstraintService(constraintRepository, new UuidResourceIdFactory());
        terms = new TermService(KognioRdfTermRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT),
                new UuidResourceIdFactory());
        reader = new StoreReader(store.lifecycle());
        comparison = new Comparison(store, DEVIATIONS);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    // -- adding ------------------------------------------------------------------------------------

    @Test
    void addingAFunctionalRequirementWithEveryFieldWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-fr");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement("Write", "Writes it.", "Because.",
                        RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null, List.of("First.", "Second."), EN), EN),
                document().create(functional()
                        .with(ArkreqVocabulary.ACCEPTANCE_CRITERION, criterion(2, "Second.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingARequirementWithOnlyTheMandatoryFieldsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-minimal");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement("Write", "Writes it.", null,
                        RequirementType.FUNCTIONAL, null, null, List.of("First."), EN), EN),
                document().create(Node.typed(ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE)
                        .with(TITLE, text("Write", EN))
                        .with(DESCRIPTION, text("Writes it.", EN))
                        .with(ArkreqVocabulary.ACCEPTANCE_CRITERION, criterion(1, "First.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingANonFunctionalRequirementWithAQualityCategoryWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-nfr");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement("Fast", "Answers quickly.", "Users wait.",
                        RequirementType.NON_FUNCTIONAL, Priority.SHOULD_HAVE, "performance", List.of("Under 1 s."),
                        EN), EN),
                document().create(Node.typed(ArkreqVocabulary.NON_FUNCTIONAL_REQUIREMENT_TYPE)
                        .with(TITLE, text("Fast", EN))
                        .with(DESCRIPTION, text("Answers quickly.", EN))
                        .with(RequirementTerms.RATIONALE, text("Users wait.", EN))
                        .with(RequirementTerms.PRIORITY, iri(RequirementTerms.SHOULD_HAVE))
                        .with(RequirementTerms.QUALITY_CATEGORY, qualityText("performance"))
                        .with(ArkreqVocabulary.ACCEPTANCE_CRITERION, criterion(1, "Under 1 s.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingARequirementInASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-de");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement("Schreiben", "Schreibt es.", "Darum.",
                        RequirementType.FUNCTIONAL, Priority.COULD_HAVE, null, List.of("Erstens."), DE), EN),
                document().create(Node.typed(ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE)
                        .with(TITLE, text("Schreiben", DE))
                        .with(DESCRIPTION, text("Schreibt es.", DE))
                        .with(RequirementTerms.RATIONALE, text("Darum.", DE))
                        .with(RequirementTerms.PRIORITY, iri(RequirementTerms.COULD_HAVE))
                        .with(ArkreqVocabulary.ACCEPTANCE_CRITERION, criterion(1, "Erstens.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingARequirementThatUsesATermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-term");
        Given<Term> term = pair.given(project -> addTerm(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement("Write", "Writes it.", "Because.",
                        RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null, List.of("First."), EN,
                        List.of(term.core().code().value())), EN),
                document().create(functional().with(ArkreqVocabulary.USES_TERM, iri(termIri(term.submit())))));

        // then
        outcome.assertSameGraph();
    }

    // -- updating the texts ------------------------------------------------------------------------

    @Test
    void changingTitleDescriptionAndRationaleWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("texts");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateTexts(project, requirement.core(), "Read", "Reads it.", "Since.", EN),
                document().update(handle(requirement.submit()), Node.fields()
                        .with(TITLE, text("Read", EN))
                        .with(DESCRIPTION, text("Reads it.", EN))
                        .with(RequirementTerms.RATIONALE, text("Since.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void changingOneFieldWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("one-text");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateTexts(project, requirement.core(), null, null, "Since.", EN),
                document().update(handle(requirement.submit()), Node.fields()
                        .with(RequirementTerms.RATIONALE, text("Since.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingTheTextsIntoASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("second-language");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateTexts(project, requirement.core(), "Schreiben", "Schreibt es.", "Darum.", DE),
                document().update(handle(requirement.submit()), Node.fields()
                        .with(TITLE, text("Schreiben", DE))
                        .with(DESCRIPTION, text("Schreibt es.", DE))
                        .with(RequirementTerms.RATIONALE, text("Darum.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void correctingTheSecondLanguageLeavesTheFirstAndWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("correct-second-language");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));
        pair.givenOnBoth(project -> updateTexts(project, requirement.core(), "Schreiben", "Schreibt es.", "Darum.",
                DE));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateTexts(project, requirement.core(), null, "Schreibt alles.", null, DE),
                document().update(handle(requirement.submit()), Node.fields()
                        .with(DESCRIPTION, text("Schreibt alles.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void changingThePriorityWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("priority");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.update(project, requirement.core().code(), null, null, null, List.of(),
                        List.of(), RemovedPositions.NONE, Priority.WONT_HAVE, null, EN, EN),
                document().update(handle(requirement.submit()), Node.fields()
                        .with(RequirementTerms.PRIORITY, iri(RequirementTerms.WONT_HAVE))));

        // then
        outcome.assertSameGraph();
    }

    // -- updating the criteria ---------------------------------------------------------------------

    @Test
    void appendingCriteriaWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("append-criteria");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateCriteria(project, requirement.core(), List.of("Second.", "Third."), List.of(),
                        RemovedPositions.NONE),
                document()
                        .addTo(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION, plainCriterion("Second.", EN))
                        .addTo(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION, plainCriterion("Third.", EN)));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void patchingACriterionTextWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("patch-criterion");
        Given<Requirement> requirement = pair.given(project -> addRequirementWithThreeCriteria(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateCriteria(project, requirement.core(), List.of(),
                        List.of(new AcceptanceCriterionTextPatch(2, "Middle.")), RemovedPositions.NONE),
                patchCriterionAt2(requirement.submit()));

        // then
        outcome.assertSameGraph();
    }

    /**
     * Intended difference of the child identity: the core patches the text in place and keeps the
     * criterion's IRI, {@code submit} has no child update and removes the child and adds a new one.
     * The canonical graph names children by position, so this is the one place the identity is pinned.
     */
    @Test
    void patchingACriterionTextKeepsTheCoreChildAndReplacesTheSubmitChild() {
        // given
        ProjectPair pair = store.pair("patch-identity");
        Given<Requirement> requirement = pair.given(project -> addRequirementWithThreeCriteria(project));
        Map<Integer, String> coreBefore = criterionIris(pair.core(), requirement.core());
        Map<Integer, String> submitBefore = criterionIris(pair.submit(), requirement.submit());

        // when
        comparison.run(pair,
                project -> updateCriteria(project, requirement.core(), List.of(),
                        List.of(new AcceptanceCriterionTextPatch(2, "Middle.")), RemovedPositions.NONE),
                patchCriterionAt2(requirement.submit())).assertSameGraph();

        // then: the core patched in place; submit kept positions 1 and 3 and minted a new child at 2
        assertEquals(coreBefore, criterionIris(pair.core(), requirement.core()));
        Map<Integer, String> submitAfter = criterionIris(pair.submit(), requirement.submit());
        assertEquals(submitBefore.get(1), submitAfter.get(1));
        assertEquals(submitBefore.get(3), submitAfter.get(3));
        assertNotEquals(submitBefore.get(2), submitAfter.get(2));
        assertEquals(Set.of(1, 2, 3), submitAfter.keySet());
    }

    @Test
    void removingACriterionInTheMiddleRenumbersTheSameWay() {
        // given
        ProjectPair pair = store.pair("remove-middle");
        Given<Requirement> requirement = pair.given(project -> addRequirementWithThreeCriteria(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateCriteria(project, requirement.core(), List.of(), List.of(),
                        new RemovedPositions(Set.of(2))),
                document().removeFrom(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION, 2));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void removingAndAddingCriteriaInOneStepWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("remove-and-add");
        Given<Requirement> requirement = pair.given(project -> addRequirementWithThreeCriteria(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateCriteria(project, requirement.core(), List.of("Fourth."), List.of(),
                        new RemovedPositions(Set.of(1, 3))),
                document()
                        .removeFrom(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION, 1)
                        .removeFrom(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION, 3)
                        .addTo(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION, plainCriterion("Fourth.", EN)));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingACriterionInASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("criterion-de");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.update(project, requirement.core().code(), null, null, null,
                        List.of("Zweitens."), List.of(), RemovedPositions.NONE, null, null, DE, EN),
                document().addTo(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION,
                        plainCriterion("Zweitens.", DE)));

        // then
        outcome.assertSameGraph();
    }

    // -- linking -----------------------------------------------------------------------------------

    @Test
    void replacingTheUsedTermsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("replace-terms");
        Given<Term> first = pair.given(project -> addTerm(project));
        Given<Term> second = pair.given(project -> addTerm(project));
        Given<Requirement> requirement = pair.given(project -> requirements.add(project, new NewRequirement("Write",
                "Writes it.", "Because.", RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null, List.of("First."),
                EN, List.of(first.core().code().value())), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.update(project, requirement.core().code(), null, null, null, List.of(),
                        List.of(), RemovedPositions.NONE, null, List.of(second.core().code().value()), EN, EN),
                document()
                        .removeFrom(handle(requirement.submit()), ArkreqVocabulary.USES_TERM, iri(termIri(first.submit())))
                        .addTo(handle(requirement.submit()), ArkreqVocabulary.USES_TERM, iri(termIri(second.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingATermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("link-term");
        Given<Term> term = pair.given(project -> addTerm(project));
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.linkTerm(project, requirement.core().code(), term.core().code().value(), EN),
                document().addTo(handle(requirement.submit()), ArkreqVocabulary.USES_TERM, iri(termIri(term.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void unlinkingATermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("unlink-term");
        Given<Term> term = pair.given(project -> addTerm(project));
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));
        pair.givenOnBoth(project -> requirements.linkTerm(project, requirement.core().code(),
                term.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.unlinkTerm(project, requirement.core().code(), term.core().code().value(),
                        EN),
                document().removeFrom(handle(requirement.submit()), ArkreqVocabulary.USES_TERM,
                        iri(termIri(term.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingAConstraintWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("link-constraint");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project));
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.linkConstraint(project, requirement.core().code(),
                        constraint.core().code().value(), EN),
                document().addTo(handle(requirement.submit()), ArkreqVocabulary.CONSTRAINED_BY,
                        iri(constraintIri(constraint.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void unlinkingAConstraintWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("unlink-constraint");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project));
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));
        pair.givenOnBoth(project -> requirements.linkConstraint(project, requirement.core().code(),
                constraint.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.unlinkConstraint(project, requirement.core().code(),
                        constraint.core().code().value(), EN),
                document().removeFrom(handle(requirement.submit()), ArkreqVocabulary.CONSTRAINED_BY,
                        iri(constraintIri(constraint.submit()))));

        // then
        outcome.assertSameGraph();
    }

    // -- status and delete -------------------------------------------------------------------------

    @Test
    void acceptingARequirementWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("accept");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.accept(project, requirement.core().code(), EN),
                document().update(handle(requirement.submit()), Node.fields()
                        .with(RequirementTerms.STATUS, iri(RequirementTerms.ACCEPTED))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void proposingAnAcceptedRequirementWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("propose");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));
        pair.givenOnBoth(project -> requirements.accept(project, requirement.core().code(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.propose(project, requirement.core().code(), EN),
                document().update(handle(requirement.submit()), Node.fields()
                        .with(RequirementTerms.STATUS, iri(RequirementTerms.PROPOSED))));

        // then
        outcome.assertSameGraph();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingARequirementInEveryStatusWritesTheSameGraph(final boolean accepted) {
        // given
        ProjectPair pair = store.pair("delete-" + accepted);
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));
        pair.givenOnBoth(project -> addRequirement(project));
        if (accepted) {
            pair.givenOnBoth(project -> requirements.accept(project, requirement.core().code(), EN));
        }

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.delete(project, requirement.core().code()),
                document().delete(handle(requirement.submit())));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void deletingARequirementThatUsesATermAndHasAConstraintWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("delete-linked");
        Given<Term> term = pair.given(project -> addTerm(project));
        Given<Constraint> constraint = pair.given(project -> addConstraint(project));
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));
        pair.givenOnBoth(project -> requirements.linkTerm(project, requirement.core().code(),
                term.core().code().value(), EN));
        pair.givenOnBoth(project -> requirements.linkConstraint(project, requirement.core().code(),
                constraint.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.delete(project, requirement.core().code()),
                document().delete(handle(requirement.submit())));

        // then
        outcome.assertSameGraph();
    }

    // -- rejecting ---------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void aBlankTitleOnAddIsRejectedByBoth(final String blank) {
        // given
        ProjectPair pair = store.pair("blank-title");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement(blank, "Writes it.", "Because.",
                        RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null, List.of("First."), EN), EN),
                document().create(functional(blank, "Writes it.")));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_TITLE),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void aBlankDescriptionOnAddIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-description");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement("Write", "   ", "Because.",
                        RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null, List.of("First."), EN), EN),
                document().create(functional("Write", "   ")));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_DESCRIPTION),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void aBlankRationaleOnUpdateIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-rationale");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateTexts(project, requirement.core(), null, null, "     ", EN),
                document().update(handle(requirement.submit()), Node.fields()
                        .with(RequirementTerms.RATIONALE, text("     ", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_RATIONALE),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void aBlankCriterionOnUpdateIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-criterion");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateCriteria(project, requirement.core(), List.of("   "), List.of(),
                        RemovedPositions.NONE),
                document().addTo(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION,
                        plainCriterion("   ", EN)));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_CRITERION),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void twoEqualCriteriaOnAddAreRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("duplicate-criteria-add");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement("Write", "Writes it.", "Because.",
                        RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null, List.of("First.", "First."), EN), EN),
                document().create(functional().with(ArkreqVocabulary.ACCEPTANCE_CRITERION,
                        criterion(2, "First.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_DUPLICATE_CRITERIA),
                Rejection.submit(SHAPE_CRITERIA_DIFFER));
    }

    @Test
    void aCriterionEqualToAnExistingOneIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("duplicate-criterion-update");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateCriteria(project, requirement.core(), List.of("First."), List.of(),
                        RemovedPositions.NONE),
                document().addTo(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION,
                        plainCriterion("First.", EN)));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_DUPLICATE_CRITERIA),
                Rejection.submit(SHAPE_CRITERIA_DIFFER));
    }

    @Test
    void aFunctionalRequirementWithAQualityCategoryIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("fr-quality");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement("Write", "Writes it.", "Because.",
                        RequirementType.FUNCTIONAL, Priority.MUST_HAVE, "performance", List.of("First."), EN), EN),
                document().create(functional().with(RequirementTerms.QUALITY_CATEGORY, qualityText("performance"))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_QUALITY_ONLY_NFR),
                Rejection.submit(SHAPE_NO_QUALITY));
    }

    @Test
    void aRequirementWithoutACriterionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("no-criterion");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.add(project, new NewRequirement("Write", "Writes it.", "Because.",
                        RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null, List.of(), EN), EN),
                document().create(Node.typed(ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE)
                        .with(TITLE, text("Write", EN))
                        .with(DESCRIPTION, text("Writes it.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_CRITERIA_EMPTY),
                Rejection.submit(SHAPE_NEEDS_CRITERION));
    }

    @Test
    void removingTheOnlyCriterionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("remove-last-criterion");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateCriteria(project, requirement.core(), List.of(), List.of(),
                        new RemovedPositions(Set.of(1))),
                document().removeFrom(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION, 1));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_CRITERIA_EMPTY),
                Rejection.submit(SHAPE_NEEDS_CRITERION));
    }

    @Test
    void removingACriterionPositionTheRequirementDoesNotHoldIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("remove-missing-position");
        Given<Requirement> requirement = pair.given(project -> addRequirementWithThreeCriteria(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateCriteria(project, requirement.core(), List.of(), List.of(),
                        new RemovedPositions(Set.of(7))),
                document().removeFrom(handle(requirement.submit()), ArkreqVocabulary.ACCEPTANCE_CRITERION, 7));

        // then
        outcome.assertBothReject(Rejection.core(AcceptanceCriterionPositionNotFoundException.class, null),
                Rejection.submit(ENGINE_HOLDS_NO_CHILD));
    }

    @Test
    void unlinkingATermTheRequirementDoesNotUseIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unlink-unlinked-term");
        Given<Term> term = pair.given(project -> addTerm(project));
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.unlinkTerm(project, requirement.core().code(), term.core().code().value(),
                        EN),
                document().removeFrom(handle(requirement.submit()), ArkreqVocabulary.USES_TERM,
                        iri(termIri(term.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(TermNotLinkedException.class, null),
                Rejection.submit(ENGINE_HOLDS_NO));
    }

    @Test
    void unlinkingAConstraintTheRequirementDoesNotHaveIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unlink-unlinked-constraint");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project));
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.unlinkConstraint(project, requirement.core().code(),
                        constraint.core().code().value(), EN),
                document().removeFrom(handle(requirement.submit()), ArkreqVocabulary.CONSTRAINED_BY,
                        iri(constraintIri(constraint.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(ConstraintNotLinkedException.class, null),
                Rejection.submit(ENGINE_HOLDS_NO));
    }

    @Test
    void linkingATermTheRequirementAlreadyUsesIsIdempotentInTheCoreAndRejectedBySubmit() {
        // given
        ProjectPair pair = store.pair("link-term-twice");
        Given<Term> term = pair.given(project -> addTerm(project));
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));
        pair.givenOnBoth(project -> requirements.linkTerm(project, requirement.core().code(),
                term.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.linkTerm(project, requirement.core().code(), term.core().code().value(), EN),
                document().addTo(handle(requirement.submit()), ArkreqVocabulary.USES_TERM, iri(termIri(term.submit()))));

        // then
        outcome.assertDeviation("G-9a", Rejection.submit(ENGINE_ALREADY_HOLDS));
    }

    @Test
    void linkingAConstraintTheRequirementAlreadyHasIsIdempotentInTheCoreAndRejectedBySubmit() {
        // given
        ProjectPair pair = store.pair("link-constraint-twice");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project));
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));
        pair.givenOnBoth(project -> requirements.linkConstraint(project, requirement.core().code(),
                constraint.core().code().value(), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.linkConstraint(project, requirement.core().code(),
                        constraint.core().code().value(), EN),
                document().addTo(handle(requirement.submit()), ArkreqVocabulary.CONSTRAINED_BY,
                        iri(constraintIri(constraint.submit()))));

        // then
        outcome.assertDeviation("G-9b", Rejection.submit(ENGINE_ALREADY_HOLDS));
    }

    @Test
    void anUnknownRequirementIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unknown");
        pair.givenOnBoth(project -> addRequirement(project));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> updateTexts(project, new RequirementCode("FR-9"), "Read", null, null, EN),
                document().update("FR-9", Node.fields().with(TITLE, text("Read", EN))));

        // then
        outcome.assertBothReject(Rejection.core(RequirementNotFoundException.class, null),
                Rejection.submit(ENGINE_NO_RESOURCE));
    }

    @Test
    void aRequirementStoredInAStatusNoTransitionLeadsFromIsRejectedByBoth() {
        // given: the status stored past the gate, as older data holds it
        ProjectPair pair = store.pair("unsupported-status");
        Given<Requirement> requirement = pair.given(project -> addRequirement(project));
        storeStatusPastTheGate(pair.core(), requirement.core());
        storeStatusPastTheGate(pair.submit(), requirement.submit());

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> requirements.accept(project, requirement.core().code(), EN),
                document().update(handle(requirement.submit()), Node.fields()
                        .with(RequirementTerms.STATUS, iri(RequirementTerms.ACCEPTED))));

        // then
        outcome.assertBothReject(Rejection.core(UnsupportedRequirementStatusException.class, null),
                Rejection.submit(ENGINE_TRANSITION));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void everyListedDeviationIsNamedByACase() {
        DEVIATIONS.assertEveryEntryUsed();
    }

    // -- steps and documents -----------------------------------------------------------------------

    private static Requirement addRequirement(final ProjectId project) {
        return requirements.add(project, new NewRequirement("Write", "Writes it.", "Because.",
                RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null, List.of("First."), EN), EN);
    }

    private static Requirement addRequirementWithThreeCriteria(final ProjectId project) {
        return requirements.add(project, new NewRequirement("Write", "Writes it.", "Because.",
                RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null, List.of("First.", "Second.", "Third."), EN),
                EN);
    }

    private static Requirement updateTexts(final ProjectId project, final Requirement requirement,
            final String title, final String description, final String rationale, final String language) {
        return updateTexts(project, requirement.code(), title, description, rationale, language);
    }

    private static Requirement updateTexts(final ProjectId project, final RequirementCode code, final String title,
            final String description, final String rationale, final String language) {
        return requirements.update(project, code, title, description, rationale, List.of(), List.of(),
                RemovedPositions.NONE, null, null, language, EN);
    }

    private static Requirement updateCriteria(final ProjectId project, final Requirement requirement,
            final List<String> added, final List<AcceptanceCriterionTextPatch> patches,
            final RemovedPositions removed) {
        return requirements.update(project, requirement.code(), null, null, null, added, patches, removed, null, null,
                EN, EN);
    }

    private static Term addTerm(final ProjectId project) {
        return terms.add(project, new NewTerm("Glossary", "A list of terms.", EN), EN);
    }

    private static Constraint addConstraint(final ProjectId project) {
        return constraints.add(project, new NewConstraint("Java 25", "Runs on Java 25.", ConstraintType.TECHNICAL,
                null), EN);
    }

    private static Document patchCriterionAt2(final Requirement submitSide) {
        return document()
                .removeFrom(handle(submitSide), ArkreqVocabulary.ACCEPTANCE_CRITERION, 2)
                .addTo(handle(submitSide), ArkreqVocabulary.ACCEPTANCE_CRITERION, criterion(2, "Middle.", EN));
    }

    private static Node functional() {
        return functional("Write", "Writes it.");
    }

    private static Node functional(final String title, final String description) {
        return Node.typed(ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE)
                .with(TITLE, text(title, EN))
                .with(DESCRIPTION, text(description, EN))
                .with(RequirementTerms.RATIONALE, text("Because.", EN))
                .with(RequirementTerms.PRIORITY, iri(RequirementTerms.MUST_HAVE))
                .with(ArkreqVocabulary.ACCEPTANCE_CRITERION, criterion(1, "First.", EN));
    }

    private static Document.Value criterion(final int position, final String text, final String language) {
        return Node.fields()
                .with(ArkreqVocabulary.POSITION, integer(position))
                .with(ArkreqVocabulary.CRITERION_TEXT, text(text, language))
                .value();
    }

    /** The quality category is a plain string, not a language-tagged text. */
    private static Document.Value qualityText(final String text) {
        return new Document.Value("\"" + text + "\"");
    }

    private static Document.Value plainCriterion(final String text, final String language) {
        return Node.fields().with(ArkreqVocabulary.CRITERION_TEXT, text(text, language)).value();
    }

    /** The IRI of each criterion by its position. */
    private static Map<Integer, String> criterionIris(final ProjectId project, final Requirement requirement) {
        Map<Integer, String> byPosition = new TreeMap<>();
        reader.outgoing(project, requirementIri(requirement)).stream()
                .filter(triple -> ArkreqVocabulary.ACCEPTANCE_CRITERION.equals(triple.predicate()))
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

    private static void storeStatusPastTheGate(final ProjectId project, final Requirement requirement) {
        String subject = requirementIri(requirement);
        try (DatasetHandle handle = store.lifecycle().acquire(new DatasetId(project.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("DELETE { GRAPH <" + ContentGraph.GRAPH_IRI + "> { <" + subject + "> <"
                        + RequirementTerms.STATUS + "> ?old } } INSERT { GRAPH <" + ContentGraph.GRAPH_IRI + "> { <"
                        + subject + "> <" + RequirementTerms.STATUS + "> <" + RequirementTerms.IMPLEMENTED
                        + "> } } WHERE { GRAPH <" + ContentGraph.GRAPH_IRI + "> { <" + subject + "> <"
                        + RequirementTerms.STATUS + "> ?old } }");
                return null;
            });
        }
    }

    private static String requirementIri(final Requirement requirement) {
        return requirement.id().value().value();
    }

    private static String handle(final Requirement requirement) {
        return requirementIri(requirement);
    }

    private static String termIri(final Term term) {
        return term.id().value().value();
    }

    private static String constraintIri(final Constraint constraint) {
        return constraint.id().value().value();
    }
}
