// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepository;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfContextRelationshipRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfTermLookup;
import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.BoundedContextReferencedException;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermNotLinkedException;
import de.hauschel.arknet.bc.domain.TermRelation;
import de.hauschel.arknet.bc.domain.TermRelationConflictException;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.Term;

/**
 * The bounded context written through {@link BoundedContextService} and through {@code submit}:
 * the same steps leave the same content graph, and the same broken step is rejected by both sides
 * (kogn-io/arknet#744).
 *
 * <p>Every case is without a subdomain: {@code submit} cannot write one ({@code rules-inventory.md}
 * section 6, X-1); the one case with a subdomain lists that difference as a graph deviation.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BoundedContextComparisonTest {

    private static final String EN = "en";
    private static final String DE = "de";
    private static final String VISION = "Keeps the model consistent.";

    private static final String CORE_BLANK_NAME = "name must not be blank";
    private static final String SHAPE_NAME = "needs at least one 'arknet:name'";
    private static final String SHAPE_DISJOINT_TERM = "must not carry the same term as both";
    private static final String ENGINE_HOLDS_NO = "holds no";

    /**
     * G-9 (intended difference): linking a term the context already carries is idempotent in the
     * core and rejected by {@code submit} ("already holds").
     */
    private static final Deviation LINKING_TWICE = Deviation.onlyCoreAccepts("G-9",
            "rules-inventory.md section 7; DA submit AddTo", "linking an already linked term: core idempotent");

    private static final String CONTEXT = CanonicalGraph.CODE_SCHEME + "BC-1";
    private static final String SUBDOMAIN = CONTEXT + "#" + KognioRdfBoundedContextRepository.SUBDOMAIN_FRAGMENT;

    /**
     * X-1: the core writes a subdomain as a fragment child of the context
     * ({@code <context>#Subdomain}, typed {@code arkddd:Subdomain}, with its {@code arkddd:subdomainType})
     * and links it by {@code arkddd:partOf}; {@code submit} has no way to create that node. The node
     * is no resource type, so its name is {@code <context>#Subdomain}, not its content, and the
     * difference stays narrow. To flip with kogn-io/arknet#745.
     */
    private static final Deviation SUBDOMAIN_ONLY_FROM_CORE = Deviation.inGraph("X-1",
            "rules-inventory.md section 6", "the subdomain node and its partOf edge are written by the typed core only",
            line -> line.startsWith(SUBDOMAIN + " ")
                    || line.equals(CONTEXT + " " + ArkdddVocabulary.PART_OF_PROPERTY + " " + SUBDOMAIN));

    private static final DeviationList DEVIATIONS = DeviationList.of(LINKING_TWICE, SUBDOMAIN_ONLY_FROM_CORE);

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static BoundedContextService contexts;
    private static TermService terms;
    private static Comparison comparison;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        contexts = new BoundedContextService(
                KognioRdfBoundedContextRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT),
                new UuidResourceIdFactory(), new KognioRdfTermLookup(store.lifecycle()),
                KognioRdfContextRelationshipRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT));
        terms = new TermService(KognioRdfTermRepositoryFactory.over(store.lifecycle(), DisplayLocale.DEFAULT),
                new UuidResourceIdFactory());
        comparison = new Comparison(store, DEVIATIONS);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    // -- writing -----------------------------------------------------------------------------------

    @Test
    void addingABoundedContextWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add");
        pair.givenOnBoth(project -> addContext(project, "Ordering"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.add(project, new NewBoundedContext("Billing", VISION, null, null, EN), EN),
                document().create(Node.typed(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Billing", EN))
                        .with(ArkdddVocabulary.DOMAIN_VISION, text(VISION, EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingABoundedContextWithATeamWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-owner");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.add(project, new NewBoundedContext("Billing", VISION, null, "Finance", EN), EN),
                document().create(Node.typed(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Billing", EN))
                        .with(ArkdddVocabulary.DOMAIN_VISION, text(VISION, EN))
                        .with(ArkdddVocabulary.OWNED_BY_PROPERTY, new Document.Value("\"Finance\""))));

        // then
        outcome.assertSameGraph();
    }

    /**
     * X-1: the document cannot create the subdomain node the core derives, so the graphs differ by
     * exactly that node and its {@code arkddd:partOf} edge.
     */
    @Test
    void addingABoundedContextWithASubdomainLeavesNoSubdomainThroughSubmit() {
        // given
        ProjectPair pair = store.pair("add-subdomain");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.add(project,
                        new NewBoundedContext("Billing", VISION, Subdomain.CORE_DOMAIN, null, EN), EN),
                document().create(Node.typed(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Billing", EN))
                        .with(ArkdddVocabulary.DOMAIN_VISION, text(VISION, EN))));

        // then
        outcome.assertDeviation("X-1", null);
    }

    @Test
    void changingTheNameWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("name");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.update(project, context.core().code(), "Sales", null, null, EN, EN),
                document().update(iri(context.submit()),
                        Node.fields().with(ArkarchVocabulary.NAME, text("Sales", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingNameAndVisionIntoASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("second-language");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.update(project, context.core().code(), "Bestellwesen",
                        "Haelt das Modell konsistent.", null, DE, EN),
                document().update(iri(context.submit()), Node.fields()
                        .with(ArkarchVocabulary.NAME, text("Bestellwesen", DE))
                        .with(ArkdddVocabulary.DOMAIN_VISION, text("Haelt das Modell konsistent.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingALanguageTermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("link-uses");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkTerm(project, context.core().code(), term.core().code().value(),
                        TermRelation.USES),
                document().addTo(iri(context.submit()), ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM,
                        Document.iri(iri(term.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingADelimitedTermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("link-delimits");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkTerm(project, context.core().code(), term.core().code().value(),
                        TermRelation.DELIMITS),
                document().addTo(iri(context.submit()), ArkdddVocabulary.DELIMITS_TERM,
                        Document.iri(iri(term.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingATermAlreadyLinkedIsOnlyAcceptedByTheCore() {
        // given
        ProjectPair pair = store.pair("link-twice");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));
        pair.givenOnBoth(project -> contexts.linkTerm(project, context.core().code(),
                term.core().code().value(), TermRelation.USES));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkTerm(project, context.core().code(), term.core().code().value(),
                        TermRelation.USES),
                document().addTo(iri(context.submit()), ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM,
                        Document.iri(iri(term.submit()))));

        // then
        outcome.assertDeviation("G-9", Rejection.submit("already holds"));
    }

    @Test
    void unlinkingATermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("unlink");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));
        pair.givenOnBoth(project -> contexts.linkTerm(project, context.core().code(),
                term.core().code().value(), TermRelation.DELIMITS));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.unlinkTerm(project, context.core().code(), term.core().code().value(),
                        TermRelation.DELIMITS),
                document().removeFrom(iri(context.submit()), ArkdddVocabulary.DELIMITS_TERM,
                        Document.iri(iri(term.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void deletingAnUnreferencedBoundedContextWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("delete");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));
        pair.givenOnBoth(project -> addContext(project, "Billing"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.delete(project, context.core().code()),
                document().delete(iri(context.submit())));

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
                project -> contexts.add(project, new NewBoundedContext("  ", VISION, null, null, EN), EN),
                document().create(Node.typed(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE)
                        .with(ArkarchVocabulary.NAME, text("  ", EN))
                        .with(ArkdddVocabulary.DOMAIN_VISION, text(VISION, EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_NAME),
                Rejection.submit(SHAPE_NAME));
    }

    @Test
    void aTermBothLanguageAndDelimitedIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("term-conflict");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));
        pair.givenOnBoth(project -> contexts.linkTerm(project, context.core().code(),
                term.core().code().value(), TermRelation.USES));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.linkTerm(project, context.core().code(), term.core().code().value(),
                        TermRelation.DELIMITS),
                document().addTo(iri(context.submit()), ArkdddVocabulary.DELIMITS_TERM,
                        Document.iri(iri(term.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(TermRelationConflictException.class, null),
                Rejection.submit(SHAPE_DISJOINT_TERM));
    }

    @Test
    void unlinkingATermThatIsNotLinkedIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unlink-unlinked");
        Given<BoundedContext> context = pair.given(project -> addContext(project, "Ordering"));
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.unlinkTerm(project, context.core().code(), term.core().code().value(),
                        TermRelation.USES),
                document().removeFrom(iri(context.submit()), ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM,
                        Document.iri(iri(term.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(TermNotLinkedException.class, null),
                Rejection.submit(ENGINE_HOLDS_NO));
    }

    @Test
    void aBoundedContextAContextRelationshipConnectsCannotBeDeletedByEither() {
        // given
        ProjectPair pair = store.pair("delete-referenced");
        Given<BoundedContext> upstream = pair.given(project -> addContext(project, "Ordering"));
        Given<BoundedContext> downstream = pair.given(project -> addContext(project, "Billing"));
        pair.givenOnBoth(project -> contexts.linkContext(project, upstream.core().code(),
                downstream.core().code(), RelationshipType.PARTNERSHIP));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.delete(project, upstream.core().code()),
                document().delete(iri(upstream.submit())));

        // then
        outcome.assertBothReject(Rejection.core(BoundedContextReferencedException.class, null),
                Rejection.submit("still points at it"));
    }

    @Test
    void anUnknownBoundedContextIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unknown");
        pair.givenOnBoth(project -> addContext(project, "Ordering"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> contexts.update(project, new BoundedContextCode("BC-9"), "Sales", null, null, EN, EN),
                document().update("BC-9", Node.fields().with(ArkarchVocabulary.NAME, text("Sales", EN))));

        // then
        outcome.assertBothReject(Rejection.core(BoundedContextNotFoundException.class, null),
                Rejection.submit("No resource found for id"));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void everyListedDeviationIsNamedByACase() {
        DEVIATIONS.assertEveryEntryUsed();
    }

    private static BoundedContext addContext(final ProjectId project, final String name) {
        return contexts.add(project, new NewBoundedContext(name, VISION, null, null, EN), EN);
    }

    private static Term addTerm(final ProjectId project, final String label) {
        return terms.add(project, new NewTerm(label, "A purchase request.", EN, null, List.of()), EN);
    }

    private static String iri(final BoundedContext context) {
        return context.id().value().value();
    }

    private static String iri(final Term term) {
        return term.id().value().value();
    }
}
