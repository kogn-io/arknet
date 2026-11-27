// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfContextRelationshipRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfTermLookup;
import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipNotFoundException;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;

/**
 * The context relationship written through {@link BoundedContextService#linkContext} and
 * {@code unlinkContext} and through {@code submit}: the same steps leave the same content graph,
 * and the same broken step is rejected by both sides (kogn-io/arknet#744).
 *
 * <p>A relationship has no code, so the canonical graph names it by its content
 * ({@link CanonicalGraph}); a graph deviation may therefore never drop its statements
 * ({@code Comparison.Outcome#assertDeviation}), and this class lists no deviation. The one finding
 * (F-bc-1, D-1 of the triage) is pinned by a case that states the difference directly.</p>
 */
class ContextRelationshipComparisonTest {

    private static final String EN = "en";
    private static final String VISION = "Keeps the model consistent.";
    private static final String SHAPE_UPSTREAM = "must not be its 'arkddd:downstream'";
    private static final String ENGINE_NO_RESOURCE = "No resource found for id";
    private static final String CORE_SAME_CONTEXT = "must not be the same bounded context";
    /** The name suffix of the second of two context relationships of identical content. */
    private static final String SECOND_RELATIONSHIP = CanonicalGraph.NO_CODE_MARK + "2";

    private static final DeviationList DEVIATIONS = DeviationList.of();

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static BoundedContextService contexts;
    private static Comparison comparison;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        contexts = new BoundedContextService(
                KognioRdfBoundedContextRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT),
                new UuidResourceIdFactory(), new KognioRdfTermLookup(store.lifecycle()),
                KognioRdfContextRelationshipRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT));
        comparison = new Comparison(store, DEVIATIONS);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void linkingTwoContextsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("link");
        Given<BoundedContext> upstream = pair.given(project -> addContext(project, "Ordering"));
        Given<BoundedContext> downstream = pair.given(project -> addContext(project, "Billing"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkContext(project, upstream.core().code(), downstream.core().code(),
                        RelationshipType.CUSTOMER_SUPPLIER),
                createRelationship(upstream.submit(), downstream.submit(), ArkdddVocabulary.CUSTOMER_SUPPLIER));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingTheSamePairWithAnotherTypeWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("link-other-type");
        Given<BoundedContext> upstream = pair.given(project -> addContext(project, "Ordering"));
        Given<BoundedContext> downstream = pair.given(project -> addContext(project, "Billing"));
        pair.givenOnBoth(project -> contexts.linkContext(project, upstream.core().code(),
                downstream.core().code(), RelationshipType.PARTNERSHIP));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkContext(project, upstream.core().code(), downstream.core().code(),
                        RelationshipType.SHARED_KERNEL),
                createRelationship(upstream.submit(), downstream.submit(), ArkdddVocabulary.SHARED_KERNEL));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void unlinkingARelationshipWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("unlink");
        Given<BoundedContext> upstream = pair.given(project -> addContext(project, "Ordering"));
        Given<BoundedContext> downstream = pair.given(project -> addContext(project, "Billing"));
        Given<ContextRelationship> relationship = pair.given(project -> contexts.linkContext(project,
                upstream.core().code(), downstream.core().code(), RelationshipType.PARTNERSHIP));
        pair.givenOnBoth(project -> contexts.linkContext(project, downstream.core().code(),
                upstream.core().code(), RelationshipType.PARTNERSHIP));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.unlinkContext(project, upstream.core().code(), downstream.core().code(),
                        RelationshipType.PARTNERSHIP),
                document().delete(relationship.submit().id().value().value()));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void aContextRelatedToItselfIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("self");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkContext(project, context.core().code(), context.core().code(),
                        RelationshipType.PARTNERSHIP),
                createRelationship(context.submit(), context.submit(), ArkdddVocabulary.PARTNERSHIP));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_SAME_CONTEXT),
                Rejection.submit(SHAPE_UPSTREAM));
    }

    @Test
    void anUnknownContextIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unknown");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkContext(project, context.core().code(), new BoundedContextCode("BC-9"),
                        RelationshipType.PARTNERSHIP),
                document().create(Node.typed(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE)
                        .with(ArkdddVocabulary.UPSTREAM, Document.iri(iri(context.submit())))
                        .with(ArkdddVocabulary.DOWNSTREAM, Document.iri("BC-9"))
                        .with(ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY,
                                Document.iri(ArkdddVocabulary.PARTNERSHIP))));

        // then
        outcome.assertBothReject(Rejection.core(BoundedContextNotFoundException.class, null),
                Rejection.submit(ENGINE_NO_RESOURCE));
    }

    @Test
    void unlinkingARelationshipThatIsNotRecordedIsRejectedByTheCore() {
        // given
        ProjectPair pair = store.pair("unlink-missing");
        Given<BoundedContext> upstream = pair.given(project -> addContext(project, "Ordering"));
        Given<BoundedContext> downstream = pair.given(project -> addContext(project, "Billing"));

        // when: a document addresses a resource by handle, so there is none to name; the core is
        // asked for the pair and nothing happens to either graph
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.unlinkContext(project, upstream.core().code(), downstream.core().code(),
                        RelationshipType.PARTNERSHIP),
                document().delete("CR-1"));

        // then
        outcome.assertBothReject(Rejection.core(ContextRelationshipNotFoundException.class, null),
                Rejection.submit(ENGINE_NO_RESOURCE));
    }

    /**
     * F-bc-1 (D-1 of the triage): the same triple linked twice. {@code linkContext} returns the
     * relationship that exists ({@code BoundedContextService#linkContext}, idempotent since #565);
     * {@code submit} creates a second one, because no shape limits a triple to one relationship.
     * The case states the difference itself: the graphs differ by exactly one resource, the core
     * changed nothing. It must flip when a shape closes the gap.
     */
    @Test
    void linkingTheSameTripleTwiceLeavesOneRelationshipInTheCoreAndTwoThroughSubmit() {
        // given
        ProjectPair pair = store.pair("link-twice");
        Given<BoundedContext> upstream = pair.given(project -> addContext(project, "Ordering"));
        Given<BoundedContext> downstream = pair.given(project -> addContext(project, "Billing"));
        pair.givenOnBoth(project -> contexts.linkContext(project, upstream.core().code(),
                downstream.core().code(), RelationshipType.PARTNERSHIP));
        CanonicalGraph before = store.graph(pair.core());

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkContext(project, upstream.core().code(), downstream.core().code(),
                        RelationshipType.PARTNERSHIP),
                createRelationship(upstream.submit(), downstream.submit(), ArkdddVocabulary.PARTNERSHIP));

        // then
        assertThrows(AssertionError.class, outcome::assertSameGraph);
        CanonicalGraph coreGraph = store.graph(pair.core());
        CanonicalGraph submitGraph = store.graph(pair.submit());
        assertEquals(before, coreGraph, "the core links an existing triple again");
        Map<String, Long> coreResources = coreGraph.resourcesPerType();
        Map<String, Long> submitResources = submitGraph.resourcesPerType();
        assertEquals(1L, coreResources.get(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE));
        assertEquals(2L, submitResources.get(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE));
        assertTrue(submitGraph.statements().containsAll(coreGraph.statements()), "submit keeps every core statement");
        List<String> onlySubmit = submitGraph.statements().stream()
                .filter(line -> !coreGraph.statements().contains(line))
                .toList();
        assertFalse(onlySubmit.isEmpty(), "submit writes a second relationship");
        assertTrue(onlySubmit.stream().allMatch(line -> line.contains(SECOND_RELATIONSHIP)),
                () -> "submit differs beyond the second relationship: " + onlySubmit);
    }

    private static Document createRelationship(final BoundedContext upstream, final BoundedContext downstream,
            final String type) {
        return document().create(Node.typed(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE)
                .with(ArkdddVocabulary.UPSTREAM, Document.iri(iri(upstream)))
                .with(ArkdddVocabulary.DOWNSTREAM, Document.iri(iri(downstream)))
                .with(ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY, Document.iri(type)));
    }

    private static BoundedContext addContext(final ProjectId project, final String name) {
        return contexts.add(project, new NewBoundedContext(name, VISION, null, null, EN), EN);
    }

    private static String iri(final BoundedContext context) {
        return context.id().value().value();
    }
}
