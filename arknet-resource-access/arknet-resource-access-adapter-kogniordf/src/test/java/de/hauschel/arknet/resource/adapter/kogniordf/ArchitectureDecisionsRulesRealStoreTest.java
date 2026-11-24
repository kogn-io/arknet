// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The rules of the architecture-decision shapes against the real shipped shapes and a real store.
 * The end-state rules, each shown by a document that breaks it, and the rule data of the life cycle:
 * the initial status, the transitions with their conditions (exactly one chosen option on
 * acceptance, an accepted successor on supersession, a cleared edge on its undo, a decision date on
 * acceptance and rejection), the decision date as a default, the deletable statuses and the record
 * frozen once decided. The legal shapes of the real corpus (a supersession chain, rejected options
 * appended to an accepted decision) must keep passing: the transition rules hold only at the
 * transition. The day comes from a fixed clock.
 */
class ArchitectureDecisionsRulesRealStoreTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final String ARKARCH = "https://w3id.org/arknet/architecture#";
    private static final String STATUS = ARKARCH + "adrStatus";
    private static final String DECISION_DATE = ARKARCH + "decisionDate";
    private static final String TODAY = "2026-11-24";
    private static final String PREFIXES = SubmitRealStoreTest.PREFIXES + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n";
    private static final String CONSEQUENCE_OK = "[ arknet:position 1 ; arkarch:consequenceStatement \"Links are first-class.\"@en ;"
            + " arkarch:consequenceType arkarch:Positive ]";
    private static final String OPTION_OK = "[ arknet:position 1 ; arknet:name \"RDF\"@en ;"
            + " arkarch:optionRationale \"Links are first-class.\"@en ; arkarch:optionOutcome arkarch:Chosen ]";
    private static final String OPTION_REJECTED = "[ arknet:position 2 ; arknet:name \"JSON\"@en ;"
            + " arkarch:optionRationale \"No links at all.\"@en ; arkarch:optionOutcome arkarch:OptionRejected ]";

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private StoreReader reader;
    private SubmitService submit;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        reader = new StoreReader(lifecycle);
        submit = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                BeforeAfterRulesRealStoreTest.CLOCK);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static String adr(final String name, final String context, final String decision, final String more) {
        return "[] a arkres:Create ; arkres:object _:adr .\n_:adr a arkarch:ArchitectureDecisionRecord ;"
                + " arknet:name " + name + " ; arkarch:adrContext " + context + " ; arkarch:adrDecision " + decision
                + " ; " + more + " .\n";
    }

    private static String valid(final String more) {
        return adr("\"Use RDF\"@en", "\"Resources need identities.\"@en", "\"Every resource is RDF.\"@en", more);
    }

    static Stream<Arguments> violations() {
        return Stream.of(
                Arguments.of("blank name", adr("\"   \"@en", "\"Needs links.\"@en", "\"Use RDF.\"@en",
                        "arkarch:consequence " + CONSEQUENCE_OK), "arknet:name"),
                Arguments.of("empty name", adr("\"\"@en", "\"Needs links.\"@en", "\"Use RDF.\"@en",
                        "arkarch:consequence " + CONSEQUENCE_OK), "arknet:name"),
                Arguments.of("blank context", adr("\"Use RDF\"@en", "\"        \"@en", "\"Use RDF.\"@en",
                        "arkarch:consequence " + CONSEQUENCE_OK), "arkarch:adrContext"),
                Arguments.of("blank decision", adr("\"Use RDF\"@en", "\"Needs links.\"@en", "\"        \"@en",
                        "arkarch:consequence " + CONSEQUENCE_OK), "arkarch:adrDecision"),
                Arguments.of("blank consequence statement", valid("arkarch:consequence [ arknet:position 1 ;"
                        + " arkarch:consequenceStatement \"        \"@en ; arkarch:consequenceType arkarch:Positive ]"),
                        "arkarch:consequenceStatement"),
                Arguments.of("blank option name", valid("arkarch:consideredOption [ arknet:position 1 ;"
                        + " arknet:name \" \"@en ; arkarch:optionRationale \"Links are first-class.\"@en ;"
                        + " arkarch:optionOutcome arkarch:Chosen ]"), "arknet:name"),
                Arguments.of("blank option rationale", valid("arkarch:consideredOption [ arknet:position 1 ;"
                        + " arknet:name \"RDF\"@en ; arkarch:optionRationale \"        \"@en ;"
                        + " arkarch:optionOutcome arkarch:Chosen ]"), "arkarch:optionRationale"),
                Arguments.of("self relatedTo", valid("arkarch:relatedTo _:adr"), "itself"),
                Arguments.of("self supersededBy", valid("arkarch:supersededBy _:adr"), "itself"),
                Arguments.of("a proposed decision with a date", valid("arkarch:decisionDate \"2026-01-01\"^^xsd:date"),
                        "A Proposed decision carries no arkarch:decisionDate"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void aDocumentBreakingAnEndStateRuleIsRejectedWithTheRule(final String rule, final String document,
            final String message) {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit(document));

        // then
        assertTrue(rejected.getMessage().contains(message), rejected.getMessage());
    }

    static Stream<Arguments> decisionDates() {
        return Stream.of(
                Arguments.of("not a date", "arkarch:decisionDate \"yesterday\""),
                Arguments.of("two dates", "arkarch:decisionDate \"2026-01-01\"^^xsd:date , \"2026-01-02\"^^xsd:date"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decisionDates")
    void aDecisionDateTheDocumentNamesIsOneDate(final String rule, final String date) {
        // given
        String adr = proposed("arkarch:consequence " + CONSEQUENCE_OK);

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> setStatus(adr, "Accepted", date));

        // then
        assertTrue(rejected.getMessage().contains("An ADR carries at most one arkarch:decisionDate, an xsd:date"),
                rejected.getMessage());
    }

    static Stream<Arguments> blankTexts() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String escape : new String[] {"\\u2003", "\\u3000", "\\u00A0", "\\u001F", "\\t", "\\n", " "}) {
            String blank = "\"" + escape.repeat(6) + "\"@en";
            cases.add(Arguments.of("name " + escape, adr(blank, "\"Needs links.\"@en", "\"Use RDF.\"@en", "")));
            cases.add(Arguments.of("context " + escape, adr("\"Use RDF\"@en", blank, "\"Use RDF.\"@en", "")));
            cases.add(Arguments.of("decision " + escape, adr("\"Use RDF\"@en", "\"Needs links.\"@en", blank, "")));
            cases.add(Arguments.of("statement " + escape, valid("arkarch:consequence [ arknet:position 1 ;"
                    + " arkarch:consequenceStatement " + blank + " ; arkarch:consequenceType arkarch:Positive ]")));
            cases.add(Arguments.of("option name " + escape, valid("arkarch:consideredOption [ arknet:position 1 ;"
                    + " arknet:name " + blank + " ; arkarch:optionRationale \"Links.\"@en ;"
                    + " arkarch:optionOutcome arkarch:Chosen ]")));
            cases.add(Arguments.of("rationale " + escape, valid("arkarch:consideredOption [ arknet:position 1 ;"
                    + " arknet:name \"RDF\"@en ; arkarch:optionRationale " + blank
                    + " ; arkarch:optionOutcome arkarch:Chosen ]")));
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankTexts")
    void everyTextTheCoreCallsBlankIsRejected(final String field, final String document) {
        // when / then
        assertThrows(SubmitRejectedException.class, () -> submit(document));
    }

    @Test
    void aDecisionStartsProposedAndUndated() {
        // when
        String adr = proposed("");

        // then
        assertEquals(Set.of(ARKARCH + "Proposed"), field(adr, STATUS));
        assertEquals(Set.of(), field(adr, DECISION_DATE));
    }

    @Test
    void aDecisionIsNotCreatedDecided() {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> submit(valid("arkarch:adrStatus arkarch:Accepted")));

        // then
        assertTrue(rejected.getMessage().contains("starts in the status " + ARKARCH + "Proposed"),
                rejected.getMessage());
    }

    @Test
    void aProposedDecisionIsNotDatedWithoutBeingDecided() {
        // given
        String adr = proposed("");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + adr + "> ;"
                        + " arkres:set [ arkarch:decisionDate \"2026-01-01\"^^xsd:date ] .\n"));

        // then
        assertTrue(rejected.getMessage().contains("A Proposed decision carries no arkarch:decisionDate"),
                rejected.getMessage());
    }

    @Test
    void aProposedRecordDatedBeforeTheRuleClearsItsDate() {
        // given: a date stored past the gate, as written before the typed core stamped it at the transition
        String adr = proposed("");
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { <" + adr + "> <"
                        + DECISION_DATE + "> \"2026-08-01\"^^<http://www.w3.org/2001/XMLSchema#date> } }");
                return null;
            });
        }
        assertEquals(Set.of("2026-08-01"), field(adr, DECISION_DATE));

        // when
        submit("[] a arkres:Update ; arkres:object <" + adr + "> ; arkres:clear arkarch:decisionDate .\n");

        // then
        assertEquals(Set.of(), field(adr, DECISION_DATE));
        assertEquals(Set.of(ARKARCH + "Proposed"), field(adr, STATUS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Accepted", "Rejected"})
    void decidingWritesTodayUnlessTheDocumentNamesTheDay(final String status) {
        // given
        String stamped = proposed("");
        String dated = proposed("");

        // when
        setStatus(stamped, status, "");
        setStatus(dated, status, "arkarch:decisionDate \"2026-09-03\"^^xsd:date");

        // then
        assertEquals(Set.of(ARKARCH + status), field(stamped, STATUS));
        assertEquals(Set.of(TODAY), field(stamped, DECISION_DATE));
        assertEquals(Set.of("2026-09-03"), field(dated, DECISION_DATE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Accepted", "Rejected"})
    void decidingWithAClearedDateIsRejected(final String status) {
        // given
        String adr = proposed("");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + adr + "> ; arkres:set [ arkarch:adrStatus arkarch:" + status
                        + " ] ; arkres:clear arkarch:decisionDate .\n"));

        // then
        assertTrue(rejected.getMessage().contains("carries its arkarch:decisionDate"), rejected.getMessage());
        assertEquals(Set.of(ARKARCH + "Proposed"), field(adr, STATUS));
    }

    @Test
    void acceptingNeedsExactlyOneChosenOptionWhenOptionsExist() {
        // given
        String undecided = proposed("arkarch:consideredOption " + OPTION_REJECTED.replace("position 2", "position 1"));
        String decided = proposed("arkarch:consideredOption " + OPTION_OK + " , " + OPTION_REJECTED);
        String withoutOptions = proposed("");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> setStatus(undecided, "Accepted", ""));
        setStatus(decided, "Accepted", "");
        setStatus(withoutOptions, "Accepted", "");

        // then
        assertTrue(rejected.getMessage().contains("exactly one arkarch:consideredOption"), rejected.getMessage());
        assertEquals(Set.of(ARKARCH + "Accepted"), field(decided, STATUS));
        assertEquals(Set.of(ARKARCH + "Accepted"), field(withoutOptions, STATUS));
    }

    @Test
    void rejectingNeedsNoChosenOption() {
        // given
        String adr = proposed("arkarch:consideredOption " + OPTION_REJECTED.replace("position 2", "position 1"));

        // when
        setStatus(adr, "Rejected", "");

        // then
        assertEquals(Set.of(ARKARCH + "Rejected"), field(adr, STATUS));
    }

    @Test
    void aProposedDecisionChoosesAnOptionInTheDocumentThatAcceptsIt() {
        // given: the only option still rejected
        String adr = proposed("arkarch:consideredOption " + OPTION_REJECTED.replace("position 2", "position 1"));

        // when: replaced by a chosen one in the same document, which only a proposed record allows
        submit("[] a arkres:RemoveFrom ; arkres:object <" + adr + "> ; arkres:field arkarch:consideredOption ;"
                + " arkres:position 1 .\n[] a arkres:AddTo ; arkres:object <" + adr + "> ;"
                + " arkres:field arkarch:consideredOption ; arkres:value " + OPTION_OK.replace("arknet:position 1 ; ", "")
                + " .\n[] a arkres:Update ; arkres:object <" + adr + "> ; arkres:set [ arkarch:adrStatus arkarch:Accepted ] .\n");

        // then
        assertEquals(Set.of(ARKARCH + "Accepted"), field(adr, STATUS));
    }

    static Stream<Arguments> undeclaredTransitions() {
        return Stream.of(
                Arguments.of(List.of(), "Superseded"),
                Arguments.of(List.of(), "Deprecated"),
                Arguments.of(List.of("Accepted"), "Proposed"),
                Arguments.of(List.of("Accepted"), "Rejected"),
                Arguments.of(List.of("Rejected"), "Accepted"),
                Arguments.of(List.of("Rejected"), "Proposed"),
                Arguments.of(List.of("Accepted", "Deprecated"), "Accepted"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("undeclaredTransitions")
    void aStatusChangesOnlyAlongADeclaredTransition(final List<String> path, final String to) {
        // given
        String adr = proposed("");
        path.forEach(status -> setStatus(adr, status, ""));

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> setStatus(adr, to, ""));

        // then
        assertTrue(rejected.getMessage().contains("no https://w3id.org/arknet/resource#transition"),
                rejected.getMessage());
    }

    @Test
    void supersedingNeedsTheEdgeToAnAcceptedDecision() {
        // given
        String superseded = accepted();
        String proposedSuccessor = proposed("");

        // when
        SubmitRejectedException byProposed = assertThrows(SubmitRejectedException.class,
                () -> setStatus(superseded, "Superseded", "arkarch:supersededBy <" + proposedSuccessor + ">"));
        SubmitRejectedException withoutEdge = assertThrows(SubmitRejectedException.class,
                () -> setStatus(superseded, "Superseded", ""));

        // then
        String rule = "only by a decision that is Accepted";
        assertTrue(byProposed.getMessage().contains(rule), byProposed.getMessage());
        assertTrue(withoutEdge.getMessage().contains(rule), withoutEdge.getMessage());
        assertEquals(Set.of(ARKARCH + "Accepted"), field(superseded, STATUS));
    }

    @Test
    void undoingASupersessionClearsTheEdgeAndKeepsTheDate() {
        // given
        String superseded = accepted();
        String successor = accepted();
        setStatus(superseded, "Superseded", "arkarch:supersededBy <" + successor + ">");

        // when
        SubmitRejectedException keepingEdge = assertThrows(SubmitRejectedException.class,
                () -> setStatus(superseded, "Accepted", ""));
        submit("[] a arkres:Update ; arkres:object <" + superseded + "> ;"
                + " arkres:set [ arkarch:adrStatus arkarch:Accepted ] ; arkres:clear arkarch:supersededBy .\n");

        // then
        assertTrue(keepingEdge.getMessage().contains("clears arkarch:supersededBy"), keepingEdge.getMessage());
        assertEquals(Set.of(ARKARCH + "Accepted"), field(superseded, STATUS));
        assertEquals(Set.of(), field(superseded, ARKARCH + "supersededBy"));
        assertEquals(Set.of(TODAY), field(superseded, DECISION_DATE));
    }

    @Test
    void theSupersessionChainOfTheRealCorpusIsLegal() {
        // given: ADR-7 -> ADR-51 -> ADR-55 -> ADR-70, each superseded while its successor was accepted
        String first = accepted();
        String second = accepted();
        String third = accepted();
        String last = accepted();

        // when
        setStatus(first, "Superseded", "arkarch:supersededBy <" + second + "> ; arkarch:relatedTo <" + last + ">");
        setStatus(second, "Superseded", "arkarch:supersededBy <" + third + ">");
        setStatus(third, "Superseded", "arkarch:supersededBy <" + last + ">");

        // then: a later write on the start of the chain still passes its superseded successor
        assertDoesNotThrow(() -> submit("[] a arkres:AddTo ; arkres:object <" + first + "> ;"
                + " arkres:field arkarch:consequence ; arkres:value [ arkarch:consequenceStatement \"Found later.\"@en ;"
                + " arkarch:consequenceType arkarch:Neutral ] .\n"));
        assertEquals(Set.of(ARKARCH + "Superseded"), field(second, STATUS));
    }

    @Test
    void aDecisionIsDeletedOnlyWhileProposedOrAccepted() {
        // given
        String proposed = proposed("");
        String accepted = accepted();
        String rejected = proposed("");
        setStatus(rejected, "Rejected", "");
        String deprecated = accepted();
        setStatus(deprecated, "Deprecated", "");

        // when
        submit("[] a arkres:Delete ; arkres:object <" + proposed + "> .\n");
        submit("[] a arkres:Delete ; arkres:object <" + accepted + "> .\n");
        SubmitRejectedException rejectedKept = assertThrows(SubmitRejectedException.class,
                () -> submit("[] a arkres:Delete ; arkres:object <" + rejected + "> .\n"));
        SubmitRejectedException deprecatedKept = assertThrows(SubmitRejectedException.class,
                () -> submit("[] a arkres:Delete ; arkres:object <" + deprecated + "> .\n"));

        // then
        assertEquals(Set.of(), field(proposed, STATUS));
        assertEquals(Set.of(), field(accepted, STATUS));
        assertTrue(rejectedKept.getMessage().contains("https://w3id.org/arknet/resource#deletableIn"),
                rejectedKept.getMessage());
        assertTrue(deprecatedKept.getMessage().contains("https://w3id.org/arknet/resource#deletableIn"),
                deprecatedKept.getMessage());
    }

    @Test
    void aSupersededDecisionIsNotDeleted() {
        // given
        String superseded = accepted();
        setStatus(superseded, "Superseded", "arkarch:supersededBy <" + accepted() + ">");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> submit("[] a arkres:Delete ; arkres:object <" + superseded + "> .\n"));

        // then
        assertTrue(rejected.getMessage().contains("https://w3id.org/arknet/resource#deletableIn"),
                rejected.getMessage());
    }

    @Test
    void aProposedDecisionRewritesItsTextFreely() {
        // given
        String adr = proposed("");

        // when
        submit("[] a arkres:Update ; arkres:object <" + adr + "> ; arkres:set [ arknet:name \"Use RDF 1.2\"@en ] .\n");

        // then
        assertEquals(Set.of("Use RDF 1.2"), field(adr, "https://w3id.org/arknet/core#name"));
    }

    @Test
    void theTextOfADecidedRecordTakesOnlyALanguageNewToNameContextAndDecision() {
        // given
        String adr = accepted();

        // when
        SubmitRejectedException rewritten = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + adr + "> ; arkres:set [ arknet:name \"Use RDF 1.2\"@en ] .\n"));
        submit("[] a arkres:Update ; arkres:object <" + adr + "> ; arkres:set [ arknet:name \"RDF nutzen\"@de ;"
                + " arkarch:adrContext \"Ressourcen brauchen Identitaeten.\"@de ;"
                + " arkarch:adrDecision \"Jede Ressource ist RDF.\"@de ] .\n");

        // then
        assertTrue(rewritten.getMessage().contains("https://w3id.org/arknet/resource#fieldGroup"),
                rewritten.getMessage());
        assertEquals(Set.of("Use RDF", "RDF nutzen"), field(adr, "https://w3id.org/arknet/core#name"));
    }

    @Test
    void theDateOfADecidedRecordIsKept() {
        // given
        String adr = accepted();

        // when
        SubmitRejectedException redated = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + adr + "> ;"
                        + " arkres:set [ arkarch:decisionDate \"2026-01-01\"^^xsd:date ] .\n"));

        // then
        assertTrue(redated.getMessage().contains(DECISION_DATE), redated.getMessage());
        assertEquals(Set.of(TODAY), field(adr, DECISION_DATE));
    }

    @Test
    void theChildListsOfADecidedRecordKeepEveryChild() {
        // given
        String adr = proposed("arkarch:consequence " + CONSEQUENCE_OK + " ; arkarch:consideredOption " + OPTION_OK);
        setStatus(adr, "Accepted", "");

        // when
        SubmitRejectedException consequenceRemoved = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:RemoveFrom ; arkres:object <" + adr + "> ; arkres:field arkarch:consequence ;"
                        + " arkres:position 1 .\n"));
        SubmitRejectedException optionRemoved = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:RemoveFrom ; arkres:object <" + adr + "> ; arkres:field arkarch:consideredOption ;"
                        + " arkres:position 1 .\n"));

        // then
        assertTrue(consequenceRemoved.getMessage().contains("removes the child"), consequenceRemoved.getMessage());
        assertTrue(optionRemoved.getMessage().contains("removes the child"), optionRemoved.getMessage());
    }

    @Test
    void anAcceptedDecisionWithoutAChosenOptionMayGetRejectedOptionsAppended() {
        // given: accepted with no options at all, which the core allows
        String adr = accepted();

        // when
        SubmitResult appended = submit("[] a arkres:AddTo ; arkres:object <" + adr + "> ;"
                + " arkres:field arkarch:consideredOption ; arkres:value [ arknet:name \"JSON\"@en ;"
                + " arkarch:optionRationale \"No links.\"@en ; arkarch:optionOutcome arkarch:OptionRejected ] .\n");

        // then
        assertEquals(1, appended.changes().size());
    }

    private String proposed(final String more) {
        return BeforeAfterRulesRealStoreTest.created(submit(valid(more)));
    }

    private String accepted() {
        String adr = proposed("");
        setStatus(adr, "Accepted", "");
        return adr;
    }

    private void setStatus(final String adr, final String status, final String more) {
        submit("[] a arkres:Update ; arkres:object <" + adr + "> ; arkres:set [ arkarch:adrStatus arkarch:" + status
                + (more.isEmpty() ? "" : " ; " + more) + " ] .\n");
    }

    private SubmitResult submit(final String document) {
        return submit.submit(PROJECT, PREFIXES + document);
    }

    private Set<String> field(final String iri, final String property) {
        return reader.outgoing(PROJECT, iri).stream()
                .filter(triple -> triple.predicate().equals(property))
                .map(triple -> switch (triple.object()) {
                    case RdfNode.Resource resource -> resource.iri();
                    case RdfNode.Literal literal -> literal.lexicalForm();
                })
                .collect(Collectors.toSet());
    }
}
