// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.Triple;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfConstraintRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.ConstraintService;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.resource.adapter.kogniordf.KognioRdfResourceRepositoryFactory;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;

/**
 * The comparison harness itself: a difference without a listed deviation fails a case, a listed
 * deviation that no longer occurs fails it too, an entry no case names fails the list, and the
 * canonical graph names resources and children without a namespace of a context.
 */
class ComparisonHarnessTest {

    private static final String EN = "en";
    private static final String TITLE = "http://purl.org/dc/terms/title";
    private static final String ID = "https://w3id.org/arknet/id/";

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static ConstraintService constraints;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        constraints = new ConstraintService(KognioRdfConstraintRepositoryFactory.over(store.lifecycle(),
                DisplayLocale.DEFAULT,
                KognioRdfRequirementRepositoryFactory.buildFunnel(store.lifecycle(), DisplayLocale.DEFAULT)),
                new UuidResourceIdFactory());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    // -- cases -------------------------------------------------------------------------------------

    @Test
    void differentGraphsWithoutAListedDeviationFailTheCase() {
        // given
        Comparison comparison = new Comparison(store, DeviationList.of());

        // when
        Comparison.Outcome outcome = retitle(comparison, "Java 26", "Java 27");

        // then
        AssertionError failure = assertThrows(AssertionError.class, outcome::assertSameGraph);
        assertTrue(failure.getMessage().contains("core-only:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("submit-only:"), failure.getMessage());
    }

    @Test
    void aGraphDeviationEqualsTheGraphsByWhatItDrops() {
        // given
        DeviationList list = DeviationList.of(titleDiffers());
        Comparison comparison = new Comparison(store, list);

        // when
        Comparison.Outcome outcome = retitle(comparison, "Java 26", "Java 27");

        // then
        assertDoesNotThrow(() -> outcome.assertDeviation("T-1", null));
        assertDoesNotThrow(list::assertEveryEntryUsed);
    }

    @Test
    void aGraphDeviationThatNoLongerOccursFailsTheCase() {
        // given
        Comparison comparison = new Comparison(store, DeviationList.of(titleDiffers()));

        // when
        Comparison.Outcome outcome = retitle(comparison, "Java 26", "Java 26");

        // then
        AssertionError failure = assertThrows(AssertionError.class, () -> outcome.assertDeviation("T-1", null));
        assertTrue(failure.getMessage().contains("no longer occurs"), failure.getMessage());
    }

    @Test
    void anOutcomeDeviationThatNoLongerOccursFailsTheCase() {
        // given
        Comparison comparison = new Comparison(store, DeviationList.of(
                Deviation.onlyCoreAccepts("T-2", "harness test", "submit rejects a new title")));

        // when
        Comparison.Outcome outcome = retitle(comparison, "Java 26", "Java 26");

        // then
        AssertionError failure = assertThrows(AssertionError.class,
                () -> outcome.assertDeviation("T-2", Rejection.submit("anything")));
        assertTrue(failure.getMessage().contains("accepted, expected a rejection"), failure.getMessage());
    }

    @Test
    void aRejectionNamingAnotherRuleFailsTheCase() {
        // given
        Comparison comparison = new Comparison(store, DeviationList.of());

        // when
        Comparison.Outcome outcome = retitle(comparison, "   ", "   ");

        // then
        AssertionError failure = assertThrows(AssertionError.class, () -> outcome.assertBothReject(
                Rejection.core(IllegalArgumentException.class, "title must not be blank"),
                Rejection.submit("a rule nobody wrote")));
        assertTrue(failure.getMessage().contains("does not name"), failure.getMessage());
    }

    @Test
    void anEntryNoCaseNamesFailsTheList() {
        // given
        DeviationList list = DeviationList.of(titleDiffers());

        // when / then
        AssertionError failure = assertThrows(AssertionError.class, list::assertEveryEntryUsed);
        assertTrue(failure.getMessage().contains("T-1"), failure.getMessage());
    }

    // -- canonical graph ---------------------------------------------------------------------------

    @Test
    void theCanonicalGraphNamesResourcesByCodeAndChildrenByPosition() {
        // given
        String requirement = ID + "a1";
        String criterion = requirement + "#AC-x7";
        List<Triple> triples = List.of(
                new Triple(requirement, CanonicalGraph.IDENTIFIER, literal("FR-1")),
                new Triple(requirement, ArkreqVocabulary.ACCEPTANCE_CRITERION, new RdfNode.Resource(criterion)),
                new Triple(criterion, ArkreqVocabulary.POSITION, new RdfNode.Literal("2",
                        "http://www.w3.org/2001/XMLSchema#integer", null)),
                new Triple(criterion, ArkreqVocabulary.CRITERION_TEXT, new RdfNode.Literal("Works.", null, EN)),
                new Triple(requirement + "#Other", TITLE, new RdfNode.Literal("Fragment.", null, EN)));

        // when
        CanonicalGraph graph = CanonicalGraph.of(triples, KognioRdfResourceRepositoryFactory.schema());

        // then
        assertEquals(Set.of(
                "code:FR-1 http://purl.org/dc/terms/identifier \"FR-1\"^^http://www.w3.org/2001/XMLSchema#string",
                "code:FR-1 " + ArkreqVocabulary.ACCEPTANCE_CRITERION + " code:FR-1#AC@2",
                "code:FR-1#AC@2 " + ArkreqVocabulary.POSITION
                        + " \"2\"^^http://www.w3.org/2001/XMLSchema#integer",
                "code:FR-1#AC@2 " + ArkreqVocabulary.CRITERION_TEXT + " \"Works.\"@en",
                "code:FR-1#Other " + TITLE + " \"Fragment.\"@en"), graph.statements());
    }

    @Test
    void aBlankNodeFailsTheCanonicalGraph() {
        // given
        List<Triple> triples = List.of(new Triple("_:b0", TITLE, new RdfNode.Literal("Loose.", null, EN)));

        // when / then
        assertThrows(AssertionError.class,
                () -> CanonicalGraph.of(triples, KognioRdfResourceRepositoryFactory.schema()));
    }

    private static Deviation titleDiffers() {
        return Deviation.inGraph("T-1", "harness test", "the two sides write different titles",
                line -> line.contains(TITLE));
    }

    private static Comparison.Outcome retitle(final Comparison comparison, final String coreTitle,
            final String submitTitle) {
        ProjectPair pair = store.pair("harness");
        Given<Constraint> constraint = pair.given(ComparisonHarnessTest::addConstraint);
        return comparison.run(pair,
                project -> constraints.update(project, constraint.core().code(), coreTitle, null, EN, EN),
                document().update(constraint.submit().id().value().value(),
                        Node.fields().with(TITLE, text(submitTitle, EN))));
    }

    private static Constraint addConstraint(final ProjectId project) {
        return constraints.add(project,
                new NewConstraint("Java 25", "Runs on Java 25.", ConstraintType.TECHNICAL, null), EN);
    }

    private static RdfNode.Literal literal(final String code) {
        return new RdfNode.Literal(code, "http://www.w3.org/2001/XMLSchema#string", null);
    }
}
