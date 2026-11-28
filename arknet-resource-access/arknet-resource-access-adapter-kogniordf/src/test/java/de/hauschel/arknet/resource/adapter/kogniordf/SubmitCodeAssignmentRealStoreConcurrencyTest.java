// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.iri;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.integer;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;

/**
 * Two creates of the same type racing for the next code: the counterpart of the six
 * {@code concurrentAddCallsUnderGenuinelyOverlappingTransactions_bothGetDistinctCodes} cases of the
 * actor, role, bounded context, term, requirement and use case {@code *RealStoreConcurrencyTest}. The
 * racer reads the highest running number of the prefix, the concurrent create commits the next
 * code, and the racer's commit must not land a second resource under that code: it loses the
 * commit, runs its document again and takes the code after.
 */
class SubmitCodeAssignmentRealStoreConcurrencyTest {

    private static final String EN = "en";
    private static final String DCT_TITLE = "http://purl.org/dc/terms/title";
    private static final String DCT_DESCRIPTION = "http://purl.org/dc/terms/description";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    @TempDir
    Path storageRoot;

    private SubmitRace race;

    @BeforeEach
    void setUp() {
        race = new SubmitRace(storageRoot);
    }

    @AfterEach
    void tearDown() {
        race.close();
    }

    static Stream<CodedType> codedTypes() {
        return Stream.of(
                new CodedType("ACTOR-", fixture -> null, (neighbour, label) -> actor(label)),
                new CodedType("ROLE-", fixture -> fixture.created(actor("Architect")),
                        (actor, label) -> role(actor, label)),
                new CodedType("BC-", fixture -> null, (neighbour, label) -> document().create(
                        Node.typed(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE)
                                .with(ArkarchVocabulary.NAME, text(label, EN))
                                .with(ArkdddVocabulary.DOMAIN_VISION, text("Keeps " + label + ".", EN)))),
                new CodedType("TERM-", fixture -> null, (neighbour, label) -> document().create(
                        Node.typed(ArkreqVocabulary.CONCEPT_TYPE)
                                .with(SKOS_PREF_LABEL, text(label, EN))
                                .with(ArkreqVocabulary.DEFINITION, text("Means " + label + ".", EN)))),
                new CodedType("FR-", fixture -> null, (neighbour, label) -> document().create(
                        Node.typed(ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE)
                                .with(DCT_TITLE, text(label, EN))
                                .with(DCT_DESCRIPTION, text("Does " + label + ".", EN))
                                .with(ArkreqVocabulary.ACCEPTANCE_CRITERION, Node.fields()
                                        .with(ArkreqVocabulary.POSITION, integer(1))
                                        .with(ArkreqVocabulary.CRITERION_TEXT, text("Works.", EN)).value()))),
                new CodedType("UC", fixture -> fixture.created(role(fixture.created(actor("Architect")), "Modeller")),
                        (role, label) -> document().create(Node.typed(ArkreqVocabulary.USE_CASE_TYPE)
                                .with(DCT_TITLE, text(label, EN))
                                .with(ArkreqVocabulary.USE_CASE_GOAL, text(label + " is done.", EN))
                                .with(ArkreqVocabulary.PRIMARY_ROLE, iri(role))
                                .with(ArkreqVocabulary.MAIN_STEP, Node.fields()
                                        .with(ArkreqVocabulary.POSITION, integer(1))
                                        .with(ArkreqVocabulary.STEP_TEXT, text("Does it.", EN)).value()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codedTypes")
    void twoCreatesRacingForTheNextCodeGetDistinctCodes(final CodedType type) throws Exception {
        // given
        String neighbour = type.given().apply(race);

        // when the racer read the highest running number and the concurrent create commits first
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.RUNNING_NUMBER, type.prefix(),
                type.create().apply(neighbour, "Racer"), type.create().apply(neighbour, "Winner"));
        SubmitResult racer = outcome.racerResult();

        // then
        String winnerCode = code(outcome.winner());
        String racerCode = code(racer);
        assertEquals(Set.of(type.prefix() + 1, type.prefix() + 2), Set.of(winnerCode, racerCode));
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
        assertFalse(race.reader().findByIdentifier(SubmitRealStoreTest.PROJECT, winnerCode).isEmpty());
        assertFalse(race.reader().findByIdentifier(SubmitRealStoreTest.PROJECT, racerCode).isEmpty());
    }

    private static Document actor(final String name) {
        return document().create(Node.typed(ArkprocVocabulary.HUMAN_ACTOR_TYPE)
                .with(ArkarchVocabulary.NAME, text(name, EN)));
    }

    private static Document role(final String actor, final String name) {
        return document().create(Node.typed(ArkprocVocabulary.ROLE_TYPE)
                .with(ArkarchVocabulary.NAME, text(name, EN))
                .with(ArkprocVocabulary.FILLED_BY, iri(actor)));
    }

    private static String code(final SubmitResult result) {
        return result.changes().stream().filter(change -> change.code() != null).findFirst().orElseThrow().code();
    }

    /**
     * A type that draws its code from a running number.
     *
     * @param prefix the code prefix
     * @param given  the resource a create of the type needs, written before the race, or {@code null}
     * @param create the document creating one resource of the type, from that resource and a label
     */
    record CodedType(String prefix, Function<SubmitRace, String> given,
            BiFunction<String, String, Document> create) {

        @Override
        public String toString() {
            return prefix;
        }
    }
}
