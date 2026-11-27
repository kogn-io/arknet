// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.pr.shared.ConstraintCode;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfConstraintRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.ConstraintService;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;

/**
 * The constraint (TCON, BCON, RCON) written through {@link ConstraintService} and through
 * {@code submit}: the same steps leave the same content graph, and the same broken step is
 * rejected by both sides, each naming the rule of {@code docs/rules-inventory.md} section 1
 * (kogn-io/arknet#744, pilot type of the comparison harness).
 *
 * <p>Incoming edges of a constraint come from requirements and use cases only; the delete guard
 * for them is compared with those types.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConstraintComparisonTest {

    private static final String EN = "en";
    private static final String DE = "de";

    /** {@code dcterms:title} - not a namespace of a context, untouched by kogn-io/arknet#745. */
    private static final String TITLE = "http://purl.org/dc/terms/title";

    /** The rules the rejections name: core exception messages and {@code sh:message} fragments. */
    private static final String CORE_BLANK_TITLE = "title must not be blank";
    private static final String CORE_BLANK_STATEMENT = "statement must not be blank";
    private static final String SHAPE_NOT_BLANK = "must contain a non-whitespace character";
    private static final String SHAPE_TITLE = "needs at least one dcterms:title";
    private static final String SHAPE_STATEMENT = "needs at least one arkreq:constraintStatement";
    private static final String ENGINE_NO_RESOURCE = "No resource found for id";

    private static final String NO_BREAK_SPACE = "  ";

    /**
     * Nothing differs for a constraint. The intended difference "Blank text" of
     * {@code rules-inventory.md} section 7 does not reach it: the core's own write gate carries the
     * same non-blank pattern, so a text of no-break spaces is rejected on both sides
     * ({@link #aTitleOfOnlyNoBreakSpacesIsRejectedByBoth}).
     */
    private static final DeviationList DEVIATIONS = DeviationList.of();

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static ConstraintService constraints;
    private static Comparison comparison;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        constraints = new ConstraintService(KognioRdfConstraintRepositoryFactory.over(store.lifecycle(),
                DisplayLocale.DEFAULT,
                KognioRdfRequirementRepositoryFactory.buildFunnel(store.lifecycle(), DisplayLocale.DEFAULT)),
                new UuidResourceIdFactory());
        comparison = new Comparison(store, DEVIATIONS);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    static Stream<Arguments> constraintTypes() {
        return Stream.of(
                Arguments.of(ConstraintType.TECHNICAL, ArkreqVocabulary.TECHNICAL_CONSTRAINT_TYPE),
                Arguments.of(ConstraintType.BUSINESS, ArkreqVocabulary.BUSINESS_CONSTRAINT_TYPE),
                Arguments.of(ConstraintType.REGULATORY, ArkreqVocabulary.REGULATORY_CONSTRAINT_TYPE));
    }

    // -- writing -----------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("constraintTypes")
    void addingAConstraintWritesTheSameGraph(final ConstraintType type, final String typeIri) {
        // given
        ProjectPair pair = store.pair("add-" + type);
        pair.givenOnBoth(project -> addConstraint(project, ConstraintType.TECHNICAL));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.add(project, new NewConstraint("Java 25", "Runs on Java 25.", type, null), EN),
                document().create(Node.typed(typeIri)
                        .with(TITLE, text("Java 25", EN))
                        .with(ArkreqVocabulary.CONSTRAINT_STATEMENT, text("Runs on Java 25.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void changingTheTitleWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("title");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project, ConstraintType.TECHNICAL));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.update(project, constraint.core().code(), "Java 26", null, EN, EN),
                document().update(iri(constraint.submit()), Node.fields().with(TITLE, text("Java 26", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void changingTitleAndStatementTogetherWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("title-statement");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project, ConstraintType.BUSINESS));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.update(project, constraint.core().code(), "Budget", "Stays within budget.",
                        EN, EN),
                document().update(iri(constraint.submit()), Node.fields()
                        .with(TITLE, text("Budget", EN))
                        .with(ArkreqVocabulary.CONSTRAINT_STATEMENT, text("Stays within budget.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingBothTextsIntoASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("second-language");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project, ConstraintType.TECHNICAL));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.update(project, constraint.core().code(), "Java 25", "Laeuft auf Java 25.",
                        DE, EN),
                document().update(iri(constraint.submit()), Node.fields()
                        .with(TITLE, text("Java 25", DE))
                        .with(ArkreqVocabulary.CONSTRAINT_STATEMENT, text("Laeuft auf Java 25.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void correctingTheSecondLanguageLeavesTheFirstAndWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("correct-second-language");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project, ConstraintType.TECHNICAL));
        // the code counters run per project, so the core's code names the constraint on both
        pair.givenOnBoth(project -> constraints.update(project, constraint.core().code(), null,
                "Laeuft auf Java 25.", DE, EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.update(project, constraint.core().code(), null, "Laeuft nur auf Java 25.",
                        DE, EN),
                document().update(iri(constraint.submit()), Node.fields()
                        .with(ArkreqVocabulary.CONSTRAINT_STATEMENT, text("Laeuft nur auf Java 25.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void deletingAnUnreferencedConstraintWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("delete");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project, ConstraintType.REGULATORY));
        pair.givenOnBoth(project -> addConstraint(project, ConstraintType.REGULATORY));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.delete(project, constraint.core().code()),
                document().delete(iri(constraint.submit())));

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
                project -> constraints.add(project,
                        new NewConstraint(blank, "Runs on Java 25.", ConstraintType.TECHNICAL, null), EN),
                document().create(Node.typed(ArkreqVocabulary.TECHNICAL_CONSTRAINT_TYPE)
                        .with(TITLE, text(blank, EN))
                        .with(ArkreqVocabulary.CONSTRAINT_STATEMENT, text("Runs on Java 25.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_TITLE),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void aBlankStatementOnUpdateIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-statement");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project, ConstraintType.TECHNICAL));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.update(project, constraint.core().code(), null, "      ", EN, EN),
                document().update(iri(constraint.submit()), Node.fields()
                        .with(ArkreqVocabulary.CONSTRAINT_STATEMENT, text("      ", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_STATEMENT),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    void aTitleOfOneCharacterIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("short-title");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project, ConstraintType.TECHNICAL));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.update(project, constraint.core().code(), "J", null, EN, EN),
                document().update(iri(constraint.submit()), Node.fields().with(TITLE, text("J", EN))));

        // then
        outcome.assertBothReject(Rejection.core(WriteConstraintViolationException.class, SHAPE_TITLE),
                Rejection.submit(SHAPE_TITLE));
    }

    @Test
    void aStatementShorterThanFiveCharactersIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("short-statement");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.add(project,
                        new NewConstraint("Java 25", "Java", ConstraintType.TECHNICAL, null), EN),
                document().create(Node.typed(ArkreqVocabulary.TECHNICAL_CONSTRAINT_TYPE)
                        .with(TITLE, text("Java 25", EN))
                        .with(ArkreqVocabulary.CONSTRAINT_STATEMENT, text("Java", EN))));

        // then
        outcome.assertBothReject(Rejection.core(WriteConstraintViolationException.class, SHAPE_STATEMENT),
                Rejection.submit(SHAPE_STATEMENT));
    }

    @Test
    void anUnknownConstraintIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unknown");
        pair.givenOnBoth(project -> addConstraint(project, ConstraintType.TECHNICAL));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.update(project, new ConstraintCode("TCON-9"), "Java 26", null, EN, EN),
                document().update("TCON-9", Node.fields().with(TITLE, text("Java 26", EN))));

        // then
        outcome.assertBothReject(Rejection.core(ConstraintNotFoundException.class, null),
                Rejection.submit(ENGINE_NO_RESOURCE));
    }

    @Test
    void aTitleOfOnlyNoBreakSpacesIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("no-break-space");
        Given<Constraint> constraint = pair.given(project -> addConstraint(project, ConstraintType.TECHNICAL));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> constraints.update(project, constraint.core().code(), NO_BREAK_SPACE, null, EN, EN),
                document().update(iri(constraint.submit()), Node.fields().with(TITLE, text(NO_BREAK_SPACE, EN))));

        // then
        outcome.assertBothReject(Rejection.core(WriteConstraintViolationException.class, SHAPE_NOT_BLANK),
                Rejection.submit(SHAPE_NOT_BLANK));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void everyListedDeviationIsNamedByACase() {
        DEVIATIONS.assertEveryEntryUsed();
    }

    private static Constraint addConstraint(final ProjectId project, final ConstraintType type) {
        return constraints.add(project, new NewConstraint("Java 25", "Runs on Java 25.", type, null), EN);
    }

    private static String iri(final Constraint constraint) {
        return constraint.id().value().value();
    }
}
