// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The end-state rules of the architecture-decision shapes, each shown by a document that breaks
 * it and is rejected with the rule's message, and by the legal shapes of the real corpus (a
 * supersession chain, appended options) that must keep passing. The two transition rules
 * (successor accepted, exactly one chosen option) are deliberately not shapes here: the cores
 * check them only at the moment of the transition.
 */
class ArchitectureDecisionsRulesRealStoreTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final String PREFIXES = SubmitRealStoreTest.PREFIXES + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n";
    private static final String CONSEQUENCE_OK = "[ arknet:position 1 ; arkarch:consequenceStatement \"Links are first-class.\"@en ;"
            + " arkarch:consequenceType arkarch:Positive ]";
    private static final String OPTION_OK = "[ arknet:position 1 ; arknet:name \"RDF\"@en ;"
            + " arkarch:optionRationale \"Links are first-class.\"@en ; arkarch:optionOutcome arkarch:Chosen ]";

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private SubmitService submit;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        submit = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                Clock.systemDefaultZone());
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static String adr(final String name, final String context, final String decision, final String more) {
        return "[] a arkres:Create ; arkres:object _:adr .\n_:adr a arkarch:ArchitectureDecisionRecord ;"
                + " arknet:name " + name + " ; arkarch:adrStatus arkarch:Proposed ;"
                + " arkarch:adrContext " + context + " ; arkarch:adrDecision " + decision + " ; " + more + " .\n";
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
                Arguments.of("self supersededBy", valid("arkarch:adrStatus arkarch:Superseded ; arkarch:supersededBy _:adr")
                        .replace("arkarch:adrStatus arkarch:Proposed ; ", ""), "itself"),
                Arguments.of("decision date not a date", valid("arkarch:decisionDate \"yesterday\""),
                        "arkarch:decisionDate"),
                Arguments.of("two decision dates", valid("arkarch:decisionDate \"2026-01-01\"^^xsd:date ,"
                        + " \"2026-01-02\"^^xsd:date"), "arkarch:decisionDate"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void aDocumentBreakingAnEndStateRuleIsRejectedWithTheRule(final String rule, final String document,
            final String message) {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> submit.submit(PROJECT, PREFIXES + document));

        // then
        assertTrue(rejected.getMessage().contains(message), rejected.getMessage());
    }

    static Stream<Arguments> blankTexts() {
        final String date = "arkarch:decisionDate \"2026-01-01\"^^xsd:date";
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String escape : new String[] {"\\u2003", "\\u3000", "\\u00A0", "\\u001F", "\\t", "\\n", " "}) {
            String blank = "\"" + escape.repeat(6) + "\"@en";
            cases.add(Arguments.of("name " + escape, adr(blank, "\"Needs links.\"@en", "\"Use RDF.\"@en", date)));
            cases.add(Arguments.of("context " + escape, adr("\"Use RDF\"@en", blank, "\"Use RDF.\"@en", date)));
            cases.add(Arguments.of("decision " + escape, adr("\"Use RDF\"@en", "\"Needs links.\"@en", blank, date)));
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
        assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT, PREFIXES + document));
    }

    @Test
    void theSupersessionChainOfTheRealCorpusIsLegal() {
        // given: ADR-7 -> ADR-51 -> ADR-55 -> ADR-70, the middle ones superseded by superseded successors
        SubmitResult result = submit.submit(PROJECT, PREFIXES
                + chainLink("last", "Accepted", "")
                + chainLink("third", "Superseded", "arkarch:supersededBy _:last ;")
                + chainLink("second", "Superseded", "arkarch:supersededBy _:third ;")
                + chainLink("first", "Superseded", "arkarch:supersededBy _:second ; arkarch:relatedTo _:last ;"));

        // then
        assertEquals(4, result.changes().size());
    }

    private static String chainLink(final String id, final String status, final String more) {
        return "[] a arkres:Create ; arkres:object _:" + id + " .\n_:" + id
                + " a arkarch:ArchitectureDecisionRecord ; arknet:name \"Decision " + id + "\"@en ;"
                + " arkarch:adrStatus arkarch:" + status + " ; arkarch:adrContext \"Why " + id + " was needed.\"@en ;"
                + " arkarch:adrDecision \"What " + id + " decided.\"@en ; " + more + " arkarch:decisionDate"
                + " \"2026-01-01\"^^xsd:date .\n";
    }

    @Test
    void anAcceptedDecisionWithoutAChosenOptionMayGetRejectedOptionsAppended() {
        // given: accepted with no options at all, which the core allows
        SubmitResult created = submit.submit(PROJECT, PREFIXES
                + adr("\"Use RDF\"@en", "\"Needs links.\"@en", "\"Use RDF.\"@en", "arkarch:decisionDate \"2026-01-01\"^^xsd:date")
                        .replace("arkarch:Proposed", "arkarch:Accepted"));
        String iri = SubmitRealStoreTest.iriOf(created, "ADR-1");

        // when
        SubmitResult appended = submit.submit(PROJECT, PREFIXES + "[] a arkres:AddTo ; arkres:object <" + iri + "> ;"
                + " arkres:field arkarch:consideredOption ; arkres:value [ arknet:position 1 ; arknet:name \"JSON\"@en ;"
                + " arkarch:optionRationale \"No links.\"@en ; arkarch:optionOutcome arkarch:OptionRejected ] .\n");

        // then
        assertEquals(1, appended.changes().size());
    }
}
