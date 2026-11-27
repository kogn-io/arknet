// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCycleException;
import de.hauschel.arknet.ul.domain.TermNotFoundException;
import de.hauschel.arknet.ul.domain.TermNotRelatedException;

/**
 * The glossary term written through {@link TermService} and through {@code submit}: the same
 * steps leave the same content graph, and the same broken step is rejected by both sides
 * (kogn-io/arknet#744, {@code docs/rules-inventory.md} section 1.4 of the triage).
 *
 * <p>Edges between terms run through {@code linkRelated}/{@code unlinkRelated}, which read both
 * terms before they write ({@code TermService#linkRelated}, {@code #unlinkRelated}); the
 * differences that follow are listed below as findings, none of them fixed here.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TermComparisonTest {

    private static final String EN = "en";
    private static final String DE = "de";

    /** {@code skos} - the glossary vocabulary, not a namespace of a context. */
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String PREF_LABEL = SKOS + "prefLabel";
    private static final String IN_SCHEME = SKOS + "inScheme";
    private static final String ENGINE_HOLDS_NO = "holds no";
    private static final String FIRST_TERM = CanonicalGraph.CODE_SCHEME + "TERM-1";
    private static final String SECOND_TERM = CanonicalGraph.CODE_SCHEME + "TERM-2";

    private static final String CORE_BLANK_LABEL = "prefLabel must not be blank";

    /**
     * X-2 ({@code rules-inventory.md} section 6): {@code skos:inScheme} is written by the core
     * only. Narrow: the one property, on the term the case adds (the second of its project).
     *
     * <p>D-2 (finding, kogn-io/arknet#744): {@code linkRelated(A, B)} is a no-op when B already
     * points at A ({@code TermService#linkRelated}, the {@code peer.related().contains(code)}
     * branch), while {@code submit} AddTo writes the opposite direction as well, so the glossary
     * then holds the pair twice. Dropped: the statement only {@code submit} writes and the history entry of
     * the first term that comes with it. To flip when
     * a shape forbids {@code skos:related} in both directions.</p>
     *
     * <p>D-3 (intended difference G-10, assumption): {@code unlinkRelated(A, B)} removes the edge
     * from whichever side holds it ({@code TermService#unlinkRelated}); a document names the
     * holder, and RemoveFrom on the other term finds nothing to remove.</p>
     */
    private static final Deviation SCHEME_ONLY_FROM_CORE = Deviation.inGraph("X-2",
            "rules-inventory.md section 6", "skos:inScheme is written by the typed core only",
            line -> line.startsWith(SECOND_TERM + " " + IN_SCHEME + " "));
    private static final Deviation RELATED_BOTH_WAYS = Deviation.inGraph("F-ul-1",
            "triage 744 D-2; TermService.linkRelated (#598)",
            "linkRelated(A, B) is a no-op when B already points at A; submit AddTo writes A to B as well",
            line -> line.equals(FIRST_TERM + " " + ArkreqVocabulary.RELATED + " " + SECOND_TERM)
                    || line.startsWith("ledger " + FIRST_TERM + " history "));
    private static final Deviation UNLINK_FROM_EITHER_SIDE = Deviation.onlyCoreAccepts("G-10",
            "triage 744 D-3; TermService.unlinkRelated",
            "unlinkRelated(A, B) removes the edge from whichever term holds it; RemoveFrom names the holder");

    private static final DeviationList DEVIATIONS = DeviationList.of(SCHEME_ONLY_FROM_CORE, RELATED_BOTH_WAYS,
            UNLINK_FROM_EITHER_SIDE);

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static TermService terms;
    private static Comparison comparison;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
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
    void addingATermDiffersOnlyInTheSchemeTheCoreAlonePlaces() {
        // given
        ProjectPair pair = store.pair("add");
        pair.givenOnBoth(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.add(project, new NewTerm("Invoice", "A bill for an order.", EN, null), EN),
                document().create(Node.typed(ArkreqVocabulary.CONCEPT_TYPE)
                        .with(PREF_LABEL, text("Invoice", EN))
                        .with(ArkreqVocabulary.DEFINITION, text("A bill for an order.", EN))));

        // then
        outcome.assertDeviation("X-2", null);
    }

    @Test
    void addingATermWithABroaderTermDiffersOnlyInTheScheme() {
        // given
        ProjectPair pair = store.pair("add-broader");
        Given<Term> order = pair.given(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.add(project, new NewTerm("Invoice", "A bill for an order.", EN,
                        order.core().code()), EN),
                document().create(Node.typed(ArkreqVocabulary.CONCEPT_TYPE)
                        .with(PREF_LABEL, text("Invoice", EN))
                        .with(ArkreqVocabulary.DEFINITION, text("A bill for an order.", EN))
                        .with(ArkreqVocabulary.BROADER, Document.iri(iri(order.submit())))));

        // then
        outcome.assertDeviation("X-2", null);
    }

    @Test
    void changingTheDefinitionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("definition");
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.update(project, term.core().code(), null, "A purchase request.", EN, EN, null,
                        null),
                document().update(iri(term.submit()),
                        Node.fields().with(ArkreqVocabulary.DEFINITION, text("A purchase request.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void renamingATermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("rename");
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.update(project, term.core().code(), "Purchase", null, null, EN, null, null),
                document().update(iri(term.submit()), Node.fields().with(PREF_LABEL, text("Purchase", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingTheDefinitionIntoASecondLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("second-language");
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.update(project, term.core().code(), null, "Eine Bestellung.", DE, EN, null, null),
                document().update(iri(term.submit()),
                        Node.fields().with(ArkreqVocabulary.DEFINITION, text("Eine Bestellung.", DE))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void settingTheBroaderTermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("broader-set");
        Given<Term> broader = pair.given(project -> addTerm(project, "Order"));
        Given<Term> narrower = pair.given(project -> addTerm(project, "Invoice"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.update(project, narrower.core().code(), null, null, null, EN,
                        Optional.of(broader.core().code()), null),
                document().update(iri(narrower.submit()),
                        Node.fields().with(ArkreqVocabulary.BROADER, Document.iri(iri(broader.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void clearingTheBroaderTermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("broader-clear");
        Given<Term> broader = pair.given(project -> addTerm(project, "Order"));
        Given<Term> narrower = pair.given(project -> terms.add(project,
                new NewTerm("Invoice", "A bill for an order.", EN, broader.core().code()), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.update(project, narrower.core().code(), null, null, null, EN, Optional.empty(),
                        null),
                document().clear(iri(narrower.submit()), ArkreqVocabulary.BROADER));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingTwoTermsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("related-link");
        Given<Term> first = pair.given(project -> addTerm(project, "Order"));
        Given<Term> second = pair.given(project -> addTerm(project, "Invoice"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.linkRelated(project, first.core().code(), second.core().code()),
                document().addTo(iri(first.submit()), ArkreqVocabulary.RELATED, Document.iri(iri(second.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void linkingTermsAlreadyRelatedTheOtherWayDiffers() {
        // given: TERM-2 points at TERM-1
        ProjectPair pair = store.pair("related-reverse");
        Given<Term> first = pair.given(project -> addTerm(project, "Order"));
        Given<Term> second = pair.given(project -> addTerm(project, "Invoice"));
        pair.givenOnBoth(project -> terms.linkRelated(project, second.core().code(), first.core().code()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.linkRelated(project, first.core().code(), second.core().code()),
                document().addTo(iri(first.submit()), ArkreqVocabulary.RELATED, Document.iri(iri(second.submit()))));

        // then
        outcome.assertDeviation("F-ul-1", null);
    }

    @Test
    void unlinkingFromTheHoldingTermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("related-unlink");
        Given<Term> first = pair.given(project -> addTerm(project, "Order"));
        Given<Term> second = pair.given(project -> addTerm(project, "Invoice"));
        pair.givenOnBoth(project -> terms.linkRelated(project, first.core().code(), second.core().code()));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.unlinkRelated(project, first.core().code(), second.core().code()),
                document().removeFrom(iri(first.submit()), ArkreqVocabulary.RELATED, Document.iri(iri(second.submit()))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void unlinkingFromTheTermThatDoesNotHoldTheEdgeDiffers() {
        // given: TERM-1 points at TERM-2
        ProjectPair pair = store.pair("related-unlink-reverse");
        Given<Term> first = pair.given(project -> addTerm(project, "Order"));
        Given<Term> second = pair.given(project -> addTerm(project, "Invoice"));
        pair.givenOnBoth(project -> terms.linkRelated(project, first.core().code(), second.core().code()));

        // when: the core is asked from the other end
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.unlinkRelated(project, second.core().code(), first.core().code()),
                document().removeFrom(iri(second.submit()), ArkreqVocabulary.RELATED, Document.iri(iri(first.submit()))));

        // then
        outcome.assertDeviation("G-10", Rejection.submit(ENGINE_HOLDS_NO));
    }

    @Test
    void deletingAnUnreferencedTermWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("delete");
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));
        pair.givenOnBoth(project -> addTerm(project, "Invoice"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.delete(project, term.core().code()),
                document().delete(iri(term.submit())));

        // then
        outcome.assertSameGraph();
    }

    // -- rejecting ---------------------------------------------------------------------------------

    @Test
    void aBlankLabelOnAddIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-label");

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.add(project, new NewTerm("  ", "A bill for an order.", EN, null), EN),
                document().create(Node.typed(ArkreqVocabulary.CONCEPT_TYPE)
                        .with(PREF_LABEL, text("  ", EN))
                        .with(ArkreqVocabulary.DEFINITION, text("A bill for an order.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_LABEL),
                Rejection.submit("needs at least one skos:prefLabel"));
    }

    @Test
    void aTermRelatedToItselfIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("related-self");
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.linkRelated(project, term.core().code(), term.core().code()),
                document().addTo(iri(term.submit()), ArkreqVocabulary.RELATED, Document.iri(iri(term.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, "must not be related to itself"),
                Rejection.submit("must not be related to itself"));
    }

    @Test
    void aTermItsOwnBroaderTermIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("broader-self");
        Given<Term> term = pair.given(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.update(project, term.core().code(), null, null, null, EN,
                        Optional.of(term.core().code()), null),
                document().update(iri(term.submit()),
                        Node.fields().with(ArkreqVocabulary.BROADER, Document.iri(iri(term.submit())))));

        // then
        outcome.assertBothReject(Rejection.core(TermCycleException.class, null),
                Rejection.submit("own broader term"));
    }

    @Test
    void anUnknownTermIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unknown");
        pair.givenOnBoth(project -> addTerm(project, "Order"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.update(project, new TermCode("TERM-9"), null, "A purchase request.", EN, EN, null,
                        null),
                document().update("TERM-9", Node.fields().with(ArkreqVocabulary.DEFINITION,
                        text("A purchase request.", EN))));

        // then
        outcome.assertBothReject(Rejection.core(TermNotFoundException.class, null),
                Rejection.submit("No resource found for id"));
    }

    @Test
    void unlinkingTermsThatAreNotRelatedIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unlink-unrelated");
        Given<Term> first = pair.given(project -> addTerm(project, "Order"));
        Given<Term> second = pair.given(project -> addTerm(project, "Invoice"));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> terms.unlinkRelated(project, first.core().code(), second.core().code()),
                document().removeFrom(iri(first.submit()), ArkreqVocabulary.RELATED, Document.iri(iri(second.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(TermNotRelatedException.class, null),
                Rejection.submit(ENGINE_HOLDS_NO));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void everyListedDeviationIsNamedByACase() {
        DEVIATIONS.assertEveryEntryUsed();
    }

    private static Term addTerm(final ProjectId project, final String label) {
        return terms.add(project, new NewTerm(label, "A bill for an order.", EN, null, List.of()), EN);
    }

    private static String iri(final Term term) {
        return term.id().value().value();
    }
}
