// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.integer;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import de.hauschel.arknet.adr.application.AdrService;
import de.hauschel.arknet.adr.application.port.in.AddAdr.NewAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.UpdateAdr.AdrCorrection;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrNotDeletableException;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrReferencedException;
import de.hauschel.arknet.adr.domain.AdrTextImmutableException;
import de.hauschel.arknet.adr.domain.ConsequenceCorrection;
import de.hauschel.arknet.adr.domain.ConsequencePositionNotFoundException;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.ConsideredOptionCorrection;
import de.hauschel.arknet.adr.domain.ConsideredOptionPositionNotFoundException;
import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.adr.domain.NewConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.adr.domain.RemovedPositions;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Comparison.Rejection;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.Given;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Value;
import de.hauschel.arknet.resource.domain.ArkresVocabulary;
import de.hauschel.arknet.ul.domain.Term;

/**
 * The architecture decision record written through {@link AdrService} and through {@code submit}:
 * the same steps leave the same content graph - with its consequences and considered options, its
 * four edge lists, its status and decision date, and the {@code supersededBy}/{@code relatedTo}
 * edges between records - and the same broken step is rejected by both sides, each naming the rule
 * of {@code docs/rules-inventory.md} section 3 (kogn-io/arknet#744).
 *
 * <p>Where the core corrects a child in place and a document removes and adds it (section 7), the
 * canonical graph names a child by its position, so the different child identities compare equal
 * and need no entry. Where the core takes two calls and a document one - choosing the option while
 * accepting - the case runs the core's sequence against the one document; the content is the same,
 * the number of versions is not (G-4). Superseding by a new successor - accepting a Proposed record
 * and superseding with it - runs the core's two calls against one document the same way (G-3).</p>
 *
 * <p>A child text in a new language on a decided record is the one step only the core takes
 * (section 6): its corrections exempt a position written in a language it did not carry, while no
 * activity changes a child in place and a document's remove + add removes a child of a decided
 * record.</p>
 *
 * <p>Not compared, because the core has no operation that could express them: a decision date on a
 * Proposed record and re-dating a decided one (the core takes the date only with the transition), and
 * legacy data ({@code AdrServiceLegacyProposedDateRealStoreTest}, {@code EngineGuaranteesRealStoreTest}
 * pin those).</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdrComparisonTest {

    private static final String EN = "en";
    private static final String DE = "de";

    /** {@code xsd:date} - the datatype of the decision date; not a namespace of a context. */
    private static final String XSD_DATE = "http://www.w3.org/2001/XMLSchema#date";

    private static final LocalDate DECIDED_ON = LocalDate.of(2026, 11, 20);

    /** The rules the rejections name: core exception messages and engine or {@code sh:message} fragments. */
    private static final String CORE_BLANK_NAME = "name must not be blank";
    private static final String CORE_BLANK_CONTEXT = "context must not be blank";
    private static final String CORE_BLANK_DECISION = "decision must not be blank";
    private static final String CORE_BLANK_STATEMENT = "statement must not be blank";
    private static final String CORE_ONE_CHOSEN = "exactly one of them";
    private static final String CORE_SUCCESSOR_ACCEPTED = "can only supersede another decision while ACCEPTED";
    private static final String CORE_SUPERSEDED_WHILE_ACCEPTED = "can only be superseded while ACCEPTED";
    private static final String CORE_SUPERSEDED_NEEDS_EDGE = "status SUPERSEDED requires supersededBy to be set";
    private static final String CORE_RELATED_TO_ITSELF = "must not be related to itself";
    private static final String CORE_SUPERSEDE_ITSELF = "must not supersede itself";
    private static final String SHAPE_NAME = "An ADR needs an arknet:name";
    private static final String SHAPE_CONTEXT = "An ADR must have an arkarch:adrContext";
    private static final String SHAPE_DECISION = "An ADR must have an arkarch:adrDecision";
    private static final String SHAPE_STATEMENT = "needs at least one arkarch:consequenceStatement";
    private static final String SHAPE_CONSEQUENCE_TYPE = "needs exactly one arkarch:consequenceType";
    private static final String SHAPE_ONE_CHOSEN = "exactly one arkarch:consideredOption";
    private static final String SHAPE_SUCCESSOR_ACCEPTED = "only by a decision that is Accepted";
    private static final String SHAPE_NO_SELF_REFERENCE = "must not point at itself";
    private static final String CORE_TEXT_FROZEN = "can only be corrected while PROPOSED";
    private static final String ENGINE_FIELDS_ONLY_GROW = "only grow (" + ArkresVocabulary.FIELD_GROUP;
    private static final String ENGINE_NOT_DELETABLE = "none of the statuses it may be deleted in ("
            + ArkresVocabulary.DELETABLE_IN;
    private static final String ENGINE_NO_TRANSITION = "no " + ArkresVocabulary.TRANSITION;
    private static final String ENGINE_REMOVES_CHILD = "removes the child";
    private static final String ENGINE_NO_CHILD_AT = "holds no child at position";
    private static final String ENGINE_STILL_POINTS = "still points at it";
    private static final String ENGINE_NO_RESOURCE = "No resource found for id";

    private static final String NO_BREAK_SPACE = "  ";

    private static final String X_3_CLEAR = "X-3-clear";
    private static final String X_3_REPOINT = "X-3-repoint";
    private static final String G_4 = "G-4";
    private static final String CHILD_NEW_LANGUAGE = "child-new-language";

    /** The ledger lines {@link ComparisonStore} adds for the versions of ADR-1 in G-4: core, then submit. */
    private static final Set<String> HISTORY_OF_ADR_1 = Set.of("ledger code:ADR-1 history 3 last version",
            "ledger code:ADR-1 history 2 last version");

    /**
     * The successor edge of a Superseded record, {@code rules-inventory.md} section 6: the core
     * refuses to clear or to re-point it, {@code submit} allows both without a status change.
     * Clearing is an expected inequality until the reverse-direction shape lands
     * (kogn-io/arknet#746); re-pointing stays an intended difference. A child text in a new language
     * on a decided record is the reverse: only the core takes it, until a child-update activity lands.
     */
    private static final DeviationList DEVIATIONS = DeviationList.of(
            Deviation.onlyCoreAccepts(CHILD_NEW_LANGUAGE, "rules-inventory.md section 6",
                    "the core corrects a consequence of a decided record in a language it did not carry; no"
                            + " activity changes a child in place, and remove + add removes a child of a decided"
                            + " record"),
            Deviation.onlySubmitAccepts(X_3_CLEAR, "rules-inventory.md section 6 (kogn-io/arknet#746)",
                    "a document clears supersededBy of a Superseded record without a transition; the core's"
                            + " record refuses a Superseded status without the edge"),
            Deviation.onlySubmitAccepts(X_3_REPOINT, "rules-inventory.md section 6",
                    "a document re-points supersededBy of a Superseded record to another accepted record; the"
                            + " core supersedes only an Accepted record"),
            Deviation.inGraph(G_4, "rules-inventory.md section 7, 'Choosing the option when accepting'",
                    "the core corrects the outcome and accepts in two calls, two versions; one document does"
                            + " both, one version - the content is the same",
                    HISTORY_OF_ADR_1::contains));

    @TempDir
    static Path storageRoot;

    private static ComparisonStore store;
    private static AdrNeighbourhood neighbourhood;
    private static AdrService adrs;
    private static Comparison comparison;

    @BeforeAll
    static void openStore() {
        store = ComparisonStore.under(storageRoot);
        neighbourhood = AdrNeighbourhood.over(store);
        adrs = neighbourhood.adrs();
        comparison = new Comparison(store, DEVIATIONS);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    // -- writing -----------------------------------------------------------------------------------

    @Test
    void addingADecisionWithChildrenAndEveryEdgeWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-full");
        Given<Requirement> requirement = pair.given(project -> neighbourhood.requirement(project, "versions"));
        Given<BoundedContext> context = pair.given(project -> neighbourhood.context(project, "Decisions"));
        Given<Term> term = pair.given(project -> neighbourhood.term(project, "Record"));
        Given<AdrDetail> peer = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.add(project, new NewAdr("Use RDF", "Links must be first-class.",
                        "The store keeps RDF.",
                        List.of(new NewConsequence("Links are first-class.", ConsequenceType.POSITIVE),
                                new NewConsequence("Tooling is rarer.", ConsequenceType.NEGATIVE)),
                        List.of(new NewConsideredOption("RDF", "Links are first-class.", OptionOutcome.CHOSEN),
                                new NewConsideredOption("JSON", "No links at all.", OptionOutcome.REJECTED)),
                        EN, List.of(requirement.core().code().value()), List.of(context.core().code().value()),
                        List.of(term.core().code().value()), List.of(peer.core().adr().code().value())), EN),
                document().create(Node.typed(ArkarchVocabulary.ADR_TYPE)
                        .with(ArkarchVocabulary.NAME, text("Use RDF", EN))
                        .with(ArkarchVocabulary.ADR_CONTEXT, text("Links must be first-class.", EN))
                        .with(ArkarchVocabulary.ADR_DECISION, text("The store keeps RDF.", EN))
                        .with(ArkarchVocabulary.CONSEQUENCE, consequence(1, "Links are first-class.",
                                ArkarchVocabulary.POSITIVE))
                        .with(ArkarchVocabulary.CONSEQUENCE, consequence(2, "Tooling is rarer.",
                                ArkarchVocabulary.NEGATIVE))
                        .with(ArkarchVocabulary.CONSIDERED_OPTION, option(1, "RDF", "Links are first-class.",
                                ArkarchVocabulary.CHOSEN))
                        .with(ArkarchVocabulary.CONSIDERED_OPTION, option(2, "JSON", "No links at all.",
                                ArkarchVocabulary.OPTION_REJECTED))
                        .with(ArkarchVocabulary.ADDRESSES_REQUIREMENT,
                                Document.iri(requirement.submit().id().value().value()))
                        .with(ArkarchVocabulary.AFFECTS_CONTEXT, Document.iri(context.submit().id().value().value()))
                        .with(ArkarchVocabulary.USES_TERM, Document.iri(term.submit().id().value().value()))
                        .with(ArkarchVocabulary.RELATED_TO, Document.iri(iri(peer.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void addingABareDecisionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("add-bare");

        // when
        Comparison.Outcome outcome = comparison.run(pair, AdrComparisonTest::proposed, bareDecision());

        // then
        outcome.assertSameGraph();
    }

    @Test
    void rewritingTheTextsOfAProposedDecisionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("rewrite-proposed");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder().name("Use Turtle")
                        .context("Files must stay readable.").decision("The store reads Turtle.").language(EN)
                        .build(), EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.NAME, text("Use Turtle", EN))
                        .with(ArkarchVocabulary.ADR_CONTEXT, text("Files must stay readable.", EN))
                        .with(ArkarchVocabulary.ADR_DECISION, text("The store reads Turtle.", EN))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingAProposedDecisionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("translate-proposed");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), germanTexts(), EN),
                document().update(iri(adr.submit()), germanFields()));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void translatingADecidedDecisionIntoANewLanguageWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("translate-accepted");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);
        pair.givenOnBoth(project -> adrs.accept(project, code(adr), DECIDED_ON, EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), germanTexts(), EN),
                document().update(iri(adr.submit()), germanFields()));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void appendingAConsequenceWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("append-consequence");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .newConsequences(List.of(new NewConsequence("Queries need SPARQL.", ConsequenceType.NEUTRAL)))
                        .language(EN).build(), EN),
                document().addTo(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE,
                        consequence(null, "Queries need SPARQL.", ArkarchVocabulary.NEUTRAL)));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void appendingARejectedOptionToAnAcceptedDecisionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("append-option-accepted");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);
        pair.givenOnBoth(project -> adrs.accept(project, code(adr), DECIDED_ON, EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .newConsideredOptions(List.of(new NewConsideredOption("XML", "Too verbose by far.",
                                OptionOutcome.REJECTED)))
                        .language(EN).build(), EN),
                document().addTo(iri(adr.submit()), ArkarchVocabulary.CONSIDERED_OPTION,
                        option(null, "XML", "Too verbose by far.", ArkarchVocabulary.OPTION_REJECTED)));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void removingTheFirstConsequenceRenumbersTheRestAndWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("remove-consequence");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .removedConsequencePositions(new RemovedPositions(Set.of(1))).build(), EN),
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE, 1));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void removingAnOptionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("remove-option");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .removedConsideredOptionPositions(new RemovedPositions(Set.of(2))).build(), EN),
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSIDERED_OPTION, 2));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void correctingTheTypeAndTextOfAConsequenceWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("correct-consequence");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .consequenceCorrections(List.of(new ConsequenceCorrection(1, "Links are typed edges.",
                                ConsequenceType.NEUTRAL)))
                        .language(EN).build(), EN),
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE, 1)
                        .addTo(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE,
                                consequence(1, "Links are typed edges.", ArkarchVocabulary.NEUTRAL)));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void correctingTheOutcomeOfAnOptionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("correct-option");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .consideredOptionCorrections(List.of(
                                new ConsideredOptionCorrection(1, "RDF", "Links are first-class.",
                                        OptionOutcome.REJECTED)))
                        .language(EN).build(), EN),
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSIDERED_OPTION, 1)
                        .addTo(iri(adr.submit()), ArkarchVocabulary.CONSIDERED_OPTION,
                                option(1, "RDF", "Links are first-class.", ArkarchVocabulary.OPTION_REJECTED)));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void replacingTheEdgeListsWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("edges");
        Given<Requirement> first = pair.given(project -> neighbourhood.requirement(project, "versions"));
        Given<Requirement> second = pair.given(project -> neighbourhood.requirement(project, "histories"));
        Given<BoundedContext> context = pair.given(project -> neighbourhood.context(project, "Decisions"));
        Given<Term> term = pair.given(project -> neighbourhood.term(project, "Record"));
        Given<AdrDetail> peer = pair.given(AdrComparisonTest::proposed);
        Given<AdrDetail> adr = pair.given(project -> adrs.add(project, new NewAdr("Use RDF",
                "Links must be first-class.", "The store keeps RDF.", null, null, EN,
                List.of(first.core().code().value()), List.of(context.core().code().value()), null,
                List.of(peer.core().adr().code().value())), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .addressesRequirementCodes(List.of(second.core().code().value()))
                        .affectsContextCodes(List.of())
                        .usesTermCodes(List.of(term.core().code().value()))
                        .relatedToCodes(List.of())
                        .build(), EN),
                document().update(iri(adr.submit()), Node.fields().with(ArkarchVocabulary.ADDRESSES_REQUIREMENT,
                                Document.iri(second.submit().id().value().value())))
                        .removeFrom(iri(adr.submit()), ArkarchVocabulary.AFFECTS_CONTEXT,
                                Document.iri(context.submit().id().value().value()))
                        .addTo(iri(adr.submit()), ArkarchVocabulary.USES_TERM,
                                Document.iri(term.submit().id().value().value()))
                        .clear(iri(adr.submit()), ArkarchVocabulary.RELATED_TO));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void acceptingOnANamedDayWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("accept-dated");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.accept(project, code(adr), DECIDED_ON, EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.ACCEPTED))
                        .with(ArkarchVocabulary.DECISION_DATE, date(DECIDED_ON))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void acceptingWithoutADayWritesTheSameGraphDatedToday() {
        // given
        ProjectPair pair = store.pair("accept-today");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);
        LocalDate before = LocalDate.now();

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.accept(project, code(adr), null, EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.ACCEPTED))));
        LocalDate after = LocalDate.now();

        // then
        outcome.assertSameGraph();
        LocalDate stamped = adrs.get(pair.core(), code(adr), EN).orElseThrow().adr().decisionDate();
        assertTrue(stamped.equals(before) || stamped.equals(after),
                () -> "stamped " + stamped + ", ran between " + before + " and " + after);
    }

    @Test
    void rejectingOnANamedDayWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("reject-dated");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.reject(project, code(adr), DECIDED_ON, EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.REJECTED))
                        .with(ArkarchVocabulary.DECISION_DATE, date(DECIDED_ON))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void choosingTheOptionWhileAcceptingWritesTheSameGraph() {
        // given: the only option still rejected
        ProjectPair pair = store.pair("choose-and-accept");
        Given<AdrDetail> adr = pair.given(project -> adrs.add(project, new NewAdr("Use RDF",
                "Links must be first-class.", "The store keeps RDF.", null,
                List.of(new NewConsideredOption("RDF", "Links are first-class.", OptionOutcome.REJECTED)),
                EN, null, null, null, null), EN));

        // when: the core corrects, then accepts; one document does both, in one version
        Comparison.Outcome outcome = comparison.run(pair,
                project -> {
                    adrs.update(project, code(adr), AdrCorrection.builder()
                            .consideredOptionCorrections(List.of(new ConsideredOptionCorrection(1, "RDF",
                                    "Links are first-class.", OptionOutcome.CHOSEN)))
                            .language(EN).build(), EN);
                    adrs.accept(project, code(adr), DECIDED_ON, EN);
                },
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSIDERED_OPTION, 1)
                        .addTo(iri(adr.submit()), ArkarchVocabulary.CONSIDERED_OPTION,
                                option(1, "RDF", "Links are first-class.", ArkarchVocabulary.CHOSEN))
                        .update(iri(adr.submit()), Node.fields()
                                .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.ACCEPTED))
                                .with(ArkarchVocabulary.DECISION_DATE, date(DECIDED_ON))));

        // then
        outcome.assertDeviation(G_4, null);
    }

    @Test
    void deprecatingWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("deprecate");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::accepted);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.deprecate(project, code(adr), EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.DEPRECATED))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void supersedingWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("supersede");
        Given<AdrDetail> old = pair.given(AdrComparisonTest::accepted);
        Given<AdrDetail> successor = pair.given(AdrComparisonTest::accepted);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.supersede(project, code(successor), code(old), EN),
                supersedeDocument(old, successor));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void supersedingByANewSuccessorWritesTheSameGraph() {
        // given: the successor still Proposed
        ProjectPair pair = store.pair("supersede-new-successor");
        Given<AdrDetail> old = pair.given(AdrComparisonTest::accepted);
        Given<AdrDetail> successor = pair.given(AdrComparisonTest::proposed);

        // when: the core accepts the successor, then supersedes; one document does both (G-3)
        Comparison.Outcome outcome = comparison.run(pair,
                project -> {
                    adrs.accept(project, code(successor), DECIDED_ON, EN);
                    adrs.supersede(project, code(successor), code(old), EN);
                },
                document().update(iri(successor.submit()), Node.fields()
                                .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.ACCEPTED))
                                .with(ArkarchVocabulary.DECISION_DATE, date(DECIDED_ON)))
                        .update(iri(old.submit()), Node.fields()
                                .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.SUPERSEDED))
                                .with(ArkarchVocabulary.SUPERSEDED_BY, Document.iri(iri(successor.submit())))));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void undoingASupersessionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("unsupersede");
        Given<AdrDetail> old = pair.given(AdrComparisonTest::accepted);
        Given<AdrDetail> successor = pair.given(AdrComparisonTest::accepted);
        pair.givenOnBoth(project -> adrs.supersede(project, code(successor), code(old), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.unsupersede(project, code(old), EN),
                document().update(iri(old.submit()), Node.fields()
                                .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.ACCEPTED)))
                        .clear(iri(old.submit()), ArkarchVocabulary.SUPERSEDED_BY));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void deletingAProposedDecisionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("delete-proposed");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);
        pair.givenOnBoth(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.delete(project, code(adr)),
                document().delete(iri(adr.submit())));

        // then
        outcome.assertSameGraph();
    }

    @Test
    void deletingAnAcceptedDecisionWritesTheSameGraph() {
        // given
        ProjectPair pair = store.pair("delete-accepted");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::accepted);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.delete(project, code(adr)),
                document().delete(iri(adr.submit())));

        // then
        outcome.assertSameGraph();
    }

    // -- rejecting ---------------------------------------------------------------------------------

    @Test
    void rewritingTheTextOfADecidedDecisionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("rewrite-accepted");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::accepted);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder().name("Use Turtle").language(EN)
                        .build(), EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.NAME, text("Use Turtle", EN))));

        // then
        outcome.assertBothReject(Rejection.core(AdrTextImmutableException.class, CORE_TEXT_FROZEN),
                Rejection.submit(ENGINE_FIELDS_ONLY_GROW));
    }

    @Test
    void removingAConsequenceOfADecidedDecisionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("remove-consequence-accepted");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);
        pair.givenOnBoth(project -> adrs.accept(project, code(adr), DECIDED_ON, EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .removedConsequencePositions(new RemovedPositions(Set.of(1))).build(), EN),
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE, 1));

        // then
        outcome.assertBothReject(Rejection.core(AdrTextImmutableException.class, CORE_TEXT_FROZEN),
                Rejection.submit(ENGINE_REMOVES_CHILD));
    }

    @Test
    void correctingAnOptionOfADecidedDecisionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("correct-option-accepted");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);
        pair.givenOnBoth(project -> adrs.accept(project, code(adr), DECIDED_ON, EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .consideredOptionCorrections(List.of(new ConsideredOptionCorrection(2, "JSON",
                                "No links, ever.", OptionOutcome.REJECTED)))
                        .language(EN).build(), EN),
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSIDERED_OPTION, 2)
                        .addTo(iri(adr.submit()), ArkarchVocabulary.CONSIDERED_OPTION,
                                option(2, "JSON", "No links, ever.", ArkarchVocabulary.OPTION_REJECTED)));

        // then
        outcome.assertBothReject(Rejection.core(AdrTextImmutableException.class, CORE_TEXT_FROZEN),
                Rejection.submit(ENGINE_REMOVES_CHILD));
    }

    @Test
    void acceptingWithoutAChosenOptionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("accept-unchosen");
        Given<AdrDetail> adr = pair.given(project -> adrs.add(project, new NewAdr("Use RDF",
                "Links must be first-class.", "The store keeps RDF.", null,
                List.of(new NewConsideredOption("JSON", "No links at all.", OptionOutcome.REJECTED)),
                EN, null, null, null, null), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.accept(project, code(adr), DECIDED_ON, EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.ACCEPTED))
                        .with(ArkarchVocabulary.DECISION_DATE, date(DECIDED_ON))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalStateException.class, CORE_ONE_CHOSEN),
                Rejection.submit(SHAPE_ONE_CHOSEN));
    }

    @Test
    void supersedingByAProposedDecisionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("supersede-by-proposed");
        Given<AdrDetail> old = pair.given(AdrComparisonTest::accepted);
        Given<AdrDetail> successor = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.supersede(project, code(successor), code(old), EN),
                supersedeDocument(old, successor));

        // then
        outcome.assertBothReject(Rejection.core(IllegalStateException.class, CORE_SUCCESSOR_ACCEPTED),
                Rejection.submit(SHAPE_SUCCESSOR_ACCEPTED));
    }

    @Test
    void deprecatingAProposedDecisionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("deprecate-proposed");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.deprecate(project, code(adr), EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.DEPRECATED))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalStateException.class, "can only be deprecated while ACCEPTED"),
                Rejection.submit(ENGINE_NO_TRANSITION));
    }

    @Test
    void rejectingAnAcceptedDecisionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("reject-accepted");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::accepted);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.reject(project, code(adr), DECIDED_ON, EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.REJECTED))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalStateException.class, "can only be rejected while PROPOSED"),
                Rejection.submit(ENGINE_NO_TRANSITION));
    }

    @Test
    void acceptingADeprecatedDecisionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("accept-deprecated");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::accepted);
        pair.givenOnBoth(project -> adrs.deprecate(project, code(adr), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.accept(project, code(adr), DECIDED_ON, EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.ACCEPTED))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalStateException.class, "can only be accepted while PROPOSED"),
                Rejection.submit(ENGINE_NO_TRANSITION));
    }

    @Test
    void relatingADecisionToItselfIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("related-to-itself");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .relatedToCodes(List.of(code(adr).value())).build(), EN),
                document().addTo(iri(adr.submit()), ArkarchVocabulary.RELATED_TO, Document.iri(iri(adr.submit()))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_RELATED_TO_ITSELF),
                Rejection.submit(SHAPE_NO_SELF_REFERENCE));
    }

    @Test
    void supersedingADecisionByItselfIsRejectedByBoth() {
        // submit names the condition of the transition, which a record superseded by itself fails as
        // well: after the document its successor is no longer Accepted
        // given
        ProjectPair pair = store.pair("supersede-itself");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::accepted);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.supersede(project, code(adr), code(adr), EN),
                supersedeDocument(adr, adr));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_SUPERSEDE_ITSELF),
                Rejection.submit(SHAPE_SUCCESSOR_ACCEPTED));
    }

    @Test
    void aBlankNameIsRejectedByBoth() {
        assertBlankTextOnAddIsRejected("blank-name", "   ", "Links must be first-class.", "The store keeps RDF.",
                CORE_BLANK_NAME, SHAPE_NAME);
    }

    @Test
    void aBlankContextIsRejectedByBoth() {
        assertBlankTextOnAddIsRejected("blank-context", "Use RDF", "\t\n", "The store keeps RDF.",
                CORE_BLANK_CONTEXT, SHAPE_CONTEXT);
    }

    @Test
    void aBlankDecisionIsRejectedByBoth() {
        assertBlankTextOnAddIsRejected("blank-decision", "Use RDF", "Links must be first-class.", "",
                CORE_BLANK_DECISION, SHAPE_DECISION);
    }

    @Test
    void aNameOfOnlyNoBreakSpacesIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("no-break-space");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder().name(NO_BREAK_SPACE).language(EN)
                        .build(), EN),
                document().update(iri(adr.submit()), Node.fields()
                        .with(ArkarchVocabulary.NAME, text(NO_BREAK_SPACE, EN))));

        // then
        outcome.assertBothReject(Rejection.core(WriteConstraintViolationException.class, SHAPE_NAME),
                Rejection.submit(SHAPE_NAME));
    }

    @Test
    void aBlankConsequenceIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("blank-consequence");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .newConsequences(List.of(new NewConsequence("     ", ConsequenceType.POSITIVE)))
                        .language(EN).build(), EN),
                document().addTo(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE,
                        consequence(null, "     ", ArkarchVocabulary.POSITIVE)));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, CORE_BLANK_STATEMENT),
                Rejection.submit(SHAPE_STATEMENT));
    }

    @Test
    void aConsequenceWithoutATypeIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("untyped-consequence");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .newConsequences(List.of(new NewConsequence("Queries need SPARQL.", null)))
                        .language(EN).build(), EN),
                document().addTo(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE, Node.fields()
                        .with(ArkarchVocabulary.CONSEQUENCE_STATEMENT, text("Queries need SPARQL.", EN)).value()));

        // then
        outcome.assertBothReject(Rejection.core(NullPointerException.class, "type"),
                Rejection.submit(SHAPE_CONSEQUENCE_TYPE));
    }

    @Test
    void removingAConsequenceAtAnUnknownPositionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("consequence-position");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .removedConsequencePositions(new RemovedPositions(Set.of(3))).build(), EN),
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE, 3));

        // then
        outcome.assertBothReject(Rejection.core(ConsequencePositionNotFoundException.class, null),
                Rejection.submit(ENGINE_NO_CHILD_AT));
    }

    @Test
    void removingAnOptionAtAnUnknownPositionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("option-position");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .removedConsideredOptionPositions(new RemovedPositions(Set.of(3))).build(), EN),
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSIDERED_OPTION, 3));

        // then
        outcome.assertBothReject(Rejection.core(ConsideredOptionPositionNotFoundException.class, null),
                Rejection.submit(ENGINE_NO_CHILD_AT));
    }

    @Test
    void deletingARejectedDecisionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("delete-rejected");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::proposed);
        pair.givenOnBoth(project -> adrs.reject(project, code(adr), DECIDED_ON, EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.delete(project, code(adr)),
                document().delete(iri(adr.submit())));

        // then
        outcome.assertBothReject(Rejection.core(AdrNotDeletableException.class, null),
                Rejection.submit(ENGINE_NOT_DELETABLE));
    }

    @Test
    void deletingADecisionAnotherRelatesToIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("delete-related");
        Given<AdrDetail> target = pair.given(AdrComparisonTest::proposed);
        pair.givenOnBoth(project -> adrs.add(project, new NewAdr("Use Turtle", "Files must stay readable.",
                "The store reads Turtle.", null, null, EN, null, null, null, List.of(code(target).value())), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.delete(project, code(target)),
                document().delete(iri(target.submit())));

        // then
        outcome.assertBothReject(Rejection.core(AdrReferencedException.class, null),
                Rejection.submit(ENGINE_STILL_POINTS));
    }

    @Test
    void anUnknownDecisionIsRejectedByBoth() {
        // given
        ProjectPair pair = store.pair("unknown");
        pair.givenOnBoth(AdrComparisonTest::proposed);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.deprecate(project, new AdrCode("ADR-9"), EN),
                document().update("ADR-9", Node.fields()
                        .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.DEPRECATED))));

        // then
        outcome.assertBothReject(Rejection.core(AdrNotFoundException.class, null),
                Rejection.submit(ENGINE_NO_RESOURCE));
    }

    // -- the successor edge of a Superseded record (rules-inventory.md section 6) -----------------

    @Test
    void clearingTheSuccessorEdgeOfASupersededDecisionOnlySubmitAccepts() {
        // given
        ProjectPair pair = store.pair("clear-superseded-by");
        Given<AdrDetail> old = pair.given(AdrComparisonTest::accepted);
        Given<AdrDetail> successor = pair.given(AdrComparisonTest::accepted);
        pair.givenOnBoth(project -> adrs.supersede(project, code(successor), code(old), EN));

        // when: no port of the core clears the edge alone; its record refuses the state it would leave
        Comparison.Outcome outcome = comparison.run(pair,
                project -> withoutSuccessor(adrs.get(project, code(old), EN).orElseThrow().adr()),
                document().clear(iri(old.submit()), ArkarchVocabulary.SUPERSEDED_BY));

        // then
        outcome.assertDeviation(X_3_CLEAR, Rejection.core(IllegalArgumentException.class,
                CORE_SUPERSEDED_NEEDS_EDGE));
    }

    @Test
    void repointingTheSuccessorEdgeOfASupersededDecisionOnlySubmitAccepts() {
        // given
        ProjectPair pair = store.pair("repoint-superseded-by");
        Given<AdrDetail> old = pair.given(AdrComparisonTest::accepted);
        Given<AdrDetail> successor = pair.given(AdrComparisonTest::accepted);
        Given<AdrDetail> other = pair.given(AdrComparisonTest::accepted);
        pair.givenOnBoth(project -> adrs.supersede(project, code(successor), code(old), EN));

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.supersede(project, code(other), code(old), EN),
                document().update(iri(old.submit()), Node.fields()
                        .with(ArkarchVocabulary.SUPERSEDED_BY, Document.iri(iri(other.submit())))));

        // then
        outcome.assertDeviation(X_3_REPOINT, Rejection.core(IllegalStateException.class,
                CORE_SUPERSEDED_WHILE_ACCEPTED));
    }

    // -- a child text in a new language on a decided record (rules-inventory.md section 6) ---------

    @Test
    void translatingAConsequenceOfADecidedDecisionOnlyCoreAccepts() {
        // given
        ProjectPair pair = store.pair("translate-consequence-accepted");
        Given<AdrDetail> adr = pair.given(AdrComparisonTest::withChildren);
        pair.givenOnBoth(project -> adrs.accept(project, code(adr), DECIDED_ON, EN));

        // when: no activity changes a child in place, so the document removes and adds it
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.update(project, code(adr), AdrCorrection.builder()
                        .consequenceCorrections(List.of(new ConsequenceCorrection(1, "Verweise sind erstrangig.",
                                ConsequenceType.POSITIVE)))
                        .language(DE).build(), DE),
                document().removeFrom(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE, 1)
                        .addTo(iri(adr.submit()), ArkarchVocabulary.CONSEQUENCE, Node.fields()
                                .with(ArkarchVocabulary.POSITION, integer(1))
                                .with(ArkarchVocabulary.CONSEQUENCE_STATEMENT, text("Links are first-class.", EN))
                                .with(ArkarchVocabulary.CONSEQUENCE_STATEMENT, text("Verweise sind erstrangig.", DE))
                                .with(ArkarchVocabulary.CONSEQUENCE_TYPE_PROPERTY,
                                        Document.iri(ArkarchVocabulary.POSITIVE))
                                .value()));

        // then
        outcome.assertDeviation(CHILD_NEW_LANGUAGE, Rejection.submit(ENGINE_REMOVES_CHILD));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void everyListedDeviationIsNamedByACase() {
        DEVIATIONS.assertEveryEntryUsed();
    }

    // -- helpers -----------------------------------------------------------------------------------

    private static void assertBlankTextOnAddIsRejected(final String caseName, final String name,
            final String context, final String decision, final String coreRule, final String shape) {
        // given
        ProjectPair pair = store.pair(caseName);

        // when
        Comparison.Outcome outcome = comparison.run(pair,
                project -> adrs.add(project, new NewAdr(name, context, decision, null, null, EN, null, null, null,
                        null), EN),
                document().create(Node.typed(ArkarchVocabulary.ADR_TYPE)
                        .with(ArkarchVocabulary.NAME, text(name, EN))
                        .with(ArkarchVocabulary.ADR_CONTEXT, text(context, EN))
                        .with(ArkarchVocabulary.ADR_DECISION, text(decision, EN))));

        // then
        outcome.assertBothReject(Rejection.core(IllegalArgumentException.class, coreRule),
                Rejection.submit(shape));
    }

    private static AdrDetail proposed(final ProjectId project) {
        return adrs.add(project, new NewAdr("Use RDF", "Links must be first-class.", "The store keeps RDF.", null,
                null, EN, null, null, null, null), EN);
    }

    private static AdrDetail withChildren(final ProjectId project) {
        return adrs.add(project, new NewAdr("Use RDF", "Links must be first-class.", "The store keeps RDF.",
                List.of(new NewConsequence("Links are first-class.", ConsequenceType.POSITIVE),
                        new NewConsequence("Tooling is rarer.", ConsequenceType.NEGATIVE)),
                List.of(new NewConsideredOption("RDF", "Links are first-class.", OptionOutcome.CHOSEN),
                        new NewConsideredOption("JSON", "No links at all.", OptionOutcome.REJECTED)),
                EN, null, null, null, null), EN);
    }

    private static AdrDetail accepted(final ProjectId project) {
        AdrDetail adr = proposed(project);
        return adrs.accept(project, adr.adr().code(), DECIDED_ON, EN);
    }

    private static Document bareDecision() {
        return document().create(Node.typed(ArkarchVocabulary.ADR_TYPE)
                .with(ArkarchVocabulary.NAME, text("Use RDF", EN))
                .with(ArkarchVocabulary.ADR_CONTEXT, text("Links must be first-class.", EN))
                .with(ArkarchVocabulary.ADR_DECISION, text("The store keeps RDF.", EN)));
    }

    private static AdrCorrection germanTexts() {
        return AdrCorrection.builder().name("RDF verwenden").context("Verweise muessen erstrangig sein.")
                .decision("Der Store fuehrt RDF.").language(DE).build();
    }

    private static Node germanFields() {
        return Node.fields()
                .with(ArkarchVocabulary.NAME, text("RDF verwenden", DE))
                .with(ArkarchVocabulary.ADR_CONTEXT, text("Verweise muessen erstrangig sein.", DE))
                .with(ArkarchVocabulary.ADR_DECISION, text("Der Store fuehrt RDF.", DE));
    }

    private static Document supersedeDocument(final Given<AdrDetail> old, final Given<AdrDetail> successor) {
        return document().update(iri(old.submit()), Node.fields()
                .with(ArkarchVocabulary.ADR_STATUS, Document.iri(ArkarchVocabulary.SUPERSEDED))
                .with(ArkarchVocabulary.SUPERSEDED_BY, Document.iri(iri(successor.submit()))));
    }

    /** The record the core would have to hold after clearing the edge alone: its constructor refuses it. */
    private static void withoutSuccessor(final Adr adr) {
        new Adr(adr.id(), adr.code(), adr.name(), adr.status(), adr.context(), adr.decision(), adr.consequences(),
                adr.consideredOptions(), adr.decisionDate(), adr.addressesRequirements(), adr.affectsContexts(),
                adr.usesTerms(), null, adr.relatedTo());
    }

    private static Value consequence(final Integer position, final String statement, final String type) {
        Node node = Node.fields();
        if (position != null) {
            node.with(ArkarchVocabulary.POSITION, integer(position));
        }
        return node.with(ArkarchVocabulary.CONSEQUENCE_STATEMENT, text(statement, EN))
                .with(ArkarchVocabulary.CONSEQUENCE_TYPE_PROPERTY, Document.iri(type))
                .value();
    }

    private static Value option(final Integer position, final String name, final String rationale,
            final String outcome) {
        Node node = Node.fields();
        if (position != null) {
            node.with(ArkarchVocabulary.POSITION, integer(position));
        }
        return node.with(ArkarchVocabulary.NAME, text(name, EN))
                .with(ArkarchVocabulary.OPTION_RATIONALE, text(rationale, EN))
                .with(ArkarchVocabulary.OPTION_OUTCOME_PROPERTY, Document.iri(outcome))
                .value();
    }

    private static Value date(final LocalDate day) {
        return new Value("\"" + day + "\"^^<" + XSD_DATE + ">");
    }

    private static AdrCode code(final Given<AdrDetail> adr) {
        return adr.core().adr().code();
    }

    private static String iri(final AdrDetail adr) {
        return adr.adr().id().value().value();
    }
}
