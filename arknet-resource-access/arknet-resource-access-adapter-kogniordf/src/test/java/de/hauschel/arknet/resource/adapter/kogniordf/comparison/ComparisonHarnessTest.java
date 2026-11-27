// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;

import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfContextRelationshipRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfTermLookup;
import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ContentGraph;
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
    private static final String TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String INTEGER = "http://www.w3.org/2001/XMLSchema#integer";

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static ConstraintService constraints;
    private static BoundedContextService contexts;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        constraints = new ConstraintService(KognioRdfConstraintRepositoryFactory.over(store.lifecycle(),
                DisplayLocale.DEFAULT,
                KognioRdfRequirementRepositoryFactory.buildFunnel(store.lifecycle(), DisplayLocale.DEFAULT)),
                new UuidResourceIdFactory());
        contexts = new BoundedContextService(
                KognioRdfBoundedContextRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT),
                new UuidResourceIdFactory(), new KognioRdfTermLookup(store.lifecycle()),
                KognioRdfContextRelationshipRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT));
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

    @Test
    void aGraphDeviationDroppingAStatementBothSidesHoldFailsTheCase() {
        // given: an entry whose predicate also matches the identifier, which both sides write alike
        Comparison comparison = new Comparison(store, DeviationList.of(Deviation.inGraph("T-3", "harness test",
                "too broad", line -> line.contains(TITLE) || line.contains(CanonicalGraph.IDENTIFIER))));

        // when
        Comparison.Outcome outcome = retitle(comparison, "Java 26", "Java 27");

        // then
        AssertionError failure = assertThrows(AssertionError.class, () -> outcome.assertDeviation("T-3", null));
        assertTrue(failure.getMessage().contains("drops statements both sides hold"), failure.getMessage());
    }

    @Test
    void aDeleteWithoutATombstoneDiffersFromADeleteWithOne() {
        // given
        ProjectPair pair = store.pair("tombstone");
        Given<Constraint> constraint = pair.given(ComparisonHarnessTest::addConstraint);
        assertEquals(store.graph(pair.core()), store.graph(pair.submit()));

        // when: the core deletes, the other side only drops the content, as a writer without history would
        constraints.delete(pair.core(), constraint.core().code());
        String iri = constraint.submit().id().value().value();
        try (DatasetHandle handle = store.lifecycle().acquire(new DatasetId(pair.submit().value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("DELETE WHERE { GRAPH <" + ContentGraph.GRAPH_IRI + "> { <" + iri + "> ?p ?o } }");
                return null;
            });
        }

        // then
        CanonicalGraph core = store.graph(pair.core());
        CanonicalGraph submit = store.graph(pair.submit());
        assertNotEquals(core, submit, () -> "history, tombstone and retained code are not compared:\n" + core);
    }

    @Test
    void aContextRelationshipLinkedByTheCoreComparesWithOneCreatedByADocument() {
        // given
        Comparison comparison = new Comparison(store, DeviationList.of());
        ProjectPair pair = store.pair("relationship");
        Given<BoundedContext> upstream = pair.given(project -> addContext(project, "Ordering"));
        Given<BoundedContext> downstream = pair.given(project -> addContext(project, "Billing"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkContext(project, upstream.core().code(), downstream.core().code(),
                        RelationshipType.PARTNERSHIP),
                document().create(Node.typed(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE)
                        .with(ArkdddVocabulary.UPSTREAM, Document.iri(upstream.submit().id().value().value()))
                        .with(ArkdddVocabulary.DOWNSTREAM, Document.iri(downstream.submit().id().value().value()))
                        .with(ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY,
                                Document.iri(ArkdddVocabulary.PARTNERSHIP))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void aGraphDeviationDroppingAStatementOfAResourceWithoutACodeFailsTheCase() {
        // given: the type differs, so the graphs differ by it; an entry that drops it would hide a
        // second or a missing relationship just as well
        Comparison comparison = new Comparison(store, DeviationList.of(Deviation.inGraph("T-5", "harness test",
                "hides a context relationship",
                line -> line.contains(" " + ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY + " "))));
        ProjectPair pair = store.pair("relationship-guard");
        Given<BoundedContext> upstream = pair.given(project -> addContext(project, "Ordering"));
        Given<BoundedContext> downstream = pair.given(project -> addContext(project, "Billing"));
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkContext(project, upstream.core().code(), downstream.core().code(),
                        RelationshipType.PARTNERSHIP),
                document().create(Node.typed(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE)
                        .with(ArkdddVocabulary.UPSTREAM, Document.iri(upstream.submit().id().value().value()))
                        .with(ArkdddVocabulary.DOWNSTREAM, Document.iri(downstream.submit().id().value().value()))
                        .with(ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY,
                                Document.iri(ArkdddVocabulary.SHARED_KERNEL))));

        // when / then
        AssertionError failure = assertThrows(AssertionError.class, () -> outcome.assertDeviation("T-5", null));
        assertTrue(failure.getMessage().contains("resource without a code"), failure.getMessage());
    }

    @Test
    void twoIdenticalResourcesWithoutACodeStayTwo() {
        // given
        List<Triple> one = relationship("urn:cr:a");
        List<Triple> two = new ArrayList<>(relationship("urn:cr:a"));
        two.addAll(relationship("urn:cr:b"));

        // when
        CanonicalGraph single = CanonicalGraph.of(one, KognioRdfResourceRepositoryFactory.schema());
        CanonicalGraph doubled = CanonicalGraph.of(two, KognioRdfResourceRepositoryFactory.schema());

        // then
        assertEquals(single.statements().size() + 4, doubled.statements().size(), doubled::toString);
        assertTrue(doubled.statements().stream().anyMatch(line -> line.contains("}~2 ")), doubled::toString);
    }

    @Test
    void aSubjectWithTwoCodesFailsTheCanonicalGraph() {
        // given
        List<Triple> triples = List.of(
                new Triple(ID + "a1", CanonicalGraph.IDENTIFIER, literal("FR-1")),
                new Triple(ID + "a1", CanonicalGraph.IDENTIFIER, literal("FR-2")));

        // when / then
        AssertionError failure = assertThrows(AssertionError.class,
                () -> CanonicalGraph.of(triples, KognioRdfResourceRepositoryFactory.schema()));
        assertTrue(failure.getMessage().contains("carries two codes"), failure.getMessage());
    }

    @Test
    void aGraphDeviationDoesNotHideAResourceOnlyOneSideHolds() {
        // given: the relationship type is a listed difference; submit also writes a second relationship
        Comparison comparison = new Comparison(store, DeviationList.of(Deviation.inGraph("T-4", "harness test",
                "the relationship type differs",
                line -> line.contains(" " + ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY + " "))));
        ProjectPair pair = store.pair("extra-relationship");
        Given<BoundedContext> upstream = pair.given(project -> addContext(project, "Ordering"));
        Given<BoundedContext> downstream = pair.given(project -> addContext(project, "Billing"));
        Node sharedKernel = Node.typed(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE)
                .with(ArkdddVocabulary.UPSTREAM, Document.iri(upstream.submit().id().value().value()))
                .with(ArkdddVocabulary.DOWNSTREAM, Document.iri(downstream.submit().id().value().value()))
                .with(ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY, Document.iri(ArkdddVocabulary.SHARED_KERNEL));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkContext(project, upstream.core().code(), downstream.core().code(),
                        RelationshipType.PARTNERSHIP),
                document().create(sharedKernel).create(sharedKernel));

        // then
        AssertionError failure = assertThrows(AssertionError.class, () -> outcome.assertDeviation("T-4", null));
        assertTrue(failure.getMessage().contains("resources per type"), failure.getMessage());
    }

    @Test
    void aResourceWithoutACodeIsNamedByItsTypeAndItsEdges() {
        // given: the same relationship under two identities, as two projects mint them
        List<Triple> core = relationship("urn:cr:core");
        List<Triple> submit = relationship("urn:cr:submit");

        // when
        CanonicalGraph coreGraph = CanonicalGraph.of(core, KognioRdfResourceRepositoryFactory.schema());
        CanonicalGraph submitGraph = CanonicalGraph.of(submit, KognioRdfResourceRepositoryFactory.schema());

        // then
        assertEquals(coreGraph, submitGraph, () -> coreGraph.differenceTo(submitGraph));
    }

    @Test
    void twoResourcesWithTheSameCodeFailTheCanonicalGraph() {
        // given
        List<Triple> triples = List.of(
                new Triple(ID + "a1", CanonicalGraph.IDENTIFIER, literal("FR-1")),
                new Triple(ID + "a1", TITLE, new RdfNode.Literal("One.", null, EN)),
                new Triple(ID + "a2", CanonicalGraph.IDENTIFIER, literal("FR-1")),
                new Triple(ID + "a2", TITLE, new RdfNode.Literal("One.", null, EN)));

        // when / then
        AssertionError failure = assertThrows(AssertionError.class,
                () -> CanonicalGraph.of(triples, KognioRdfResourceRepositoryFactory.schema()));
        assertTrue(failure.getMessage().contains("code:FR-1"), failure.getMessage());
    }

    @Test
    void twoChildrenAtTheSamePositionFailTheCanonicalGraph() {
        // given
        String requirement = ID + "a1";
        List<Triple> triples = List.of(
                new Triple(requirement, CanonicalGraph.IDENTIFIER, literal("FR-1")),
                new Triple(requirement, ArkreqVocabulary.ACCEPTANCE_CRITERION,
                        new RdfNode.Resource(requirement + "#AC-1")),
                new Triple(requirement, ArkreqVocabulary.ACCEPTANCE_CRITERION,
                        new RdfNode.Resource(requirement + "#AC-2")),
                new Triple(requirement + "#AC-1", ArkreqVocabulary.POSITION, new RdfNode.Literal("1", INTEGER, null)),
                new Triple(requirement + "#AC-2", ArkreqVocabulary.POSITION, new RdfNode.Literal("1", INTEGER, null)));

        // when / then
        AssertionError failure = assertThrows(AssertionError.class,
                () -> CanonicalGraph.of(triples, KognioRdfResourceRepositoryFactory.schema()));
        assertTrue(failure.getMessage().contains("code:FR-1#AC@1"), failure.getMessage());
    }

    @Test
    void aChildWithTwoPositionsFailsTheCanonicalGraph() {
        // given
        String requirement = ID + "a1";
        List<Triple> triples = List.of(
                new Triple(requirement, CanonicalGraph.IDENTIFIER, literal("FR-1")),
                new Triple(requirement, ArkreqVocabulary.ACCEPTANCE_CRITERION,
                        new RdfNode.Resource(requirement + "#AC-1")),
                new Triple(requirement + "#AC-1", ArkreqVocabulary.POSITION, new RdfNode.Literal("1", INTEGER, null)),
                new Triple(requirement + "#AC-1", ArkreqVocabulary.POSITION, new RdfNode.Literal("2", INTEGER, null)));

        // when / then
        AssertionError failure = assertThrows(AssertionError.class,
                () -> CanonicalGraph.of(triples, KognioRdfResourceRepositoryFactory.schema()));
        assertTrue(failure.getMessage().contains("2 values of"), failure.getMessage());
    }

    @Test
    void aChildOwnedTwiceFailsTheCanonicalGraph() {
        // given
        List<Triple> triples = List.of(
                new Triple(ID + "a1", CanonicalGraph.IDENTIFIER, literal("FR-1")),
                new Triple(ID + "a2", CanonicalGraph.IDENTIFIER, literal("FR-2")),
                new Triple(ID + "a1", ArkreqVocabulary.ACCEPTANCE_CRITERION, new RdfNode.Resource(ID + "a1#AC-1")),
                new Triple(ID + "a2", ArkreqVocabulary.ACCEPTANCE_CRITERION, new RdfNode.Resource(ID + "a1#AC-1")),
                new Triple(ID + "a1#AC-1", ArkreqVocabulary.POSITION, new RdfNode.Literal("1", INTEGER, null)));

        // when / then
        AssertionError failure = assertThrows(AssertionError.class,
                () -> CanonicalGraph.of(triples, KognioRdfResourceRepositoryFactory.schema()));
        assertTrue(failure.getMessage().contains("owned 2 times"), failure.getMessage());
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

    private static BoundedContext addContext(final ProjectId project, final String name) {
        return contexts.add(project,
                new NewBoundedContext(name, "Keeps the model consistent.", null, null, null), EN);
    }

    private static List<Triple> relationship(final String iri) {
        return List.of(
                new Triple(ID + "bc1", CanonicalGraph.IDENTIFIER, literal("BC-1")),
                new Triple(ID + "bc2", CanonicalGraph.IDENTIFIER, literal("BC-2")),
                new Triple(iri, TYPE, new RdfNode.Resource(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE)),
                new Triple(iri, ArkdddVocabulary.UPSTREAM, new RdfNode.Resource(ID + "bc1")),
                new Triple(iri, ArkdddVocabulary.DOWNSTREAM, new RdfNode.Resource(ID + "bc2")),
                new Triple(iri, ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY,
                        new RdfNode.Resource(ArkdddVocabulary.PARTNERSHIP)));
    }

    private static RdfNode.Literal literal(final String code) {
        return new RdfNode.Literal(code, "http://www.w3.org/2001/XMLSchema#string", null);
    }
}
