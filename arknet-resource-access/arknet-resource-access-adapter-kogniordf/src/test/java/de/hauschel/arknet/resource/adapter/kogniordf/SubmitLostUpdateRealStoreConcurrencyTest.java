// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.integer;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.iri;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.Triple;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;

/**
 * Two updates of one resource through {@code submit}, the racer paused after it read the slice
 * until the concurrent update committed: neither change may be lost. The counterpart of the
 * lost-update cases of the actor, role, use case and bounded context {@code *RealStoreConcurrencyTest}
 * - there a typed repository's compare-and-set retries, here the engine loses its commit and runs the
 * document once more against the new slice.
 */
class SubmitLostUpdateRealStoreConcurrencyTest {

    private static final String EN = "en";
    private static final String DE = "de";
    private static final String DCT_TITLE = "http://purl.org/dc/terms/title";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    /** No vocabulary constant names {@code arknet:description}; the actor and role field it is. */
    private static final String DESCRIPTION = ArkarchVocabulary.NAME.replace("#name", "#description");

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

    /** Counterpart of {@code ActorServiceRealStoreConcurrencyTest#updateRetriesAndKeepsBothCorrections...}. */
    @Test
    void anActorNameAndDescriptionUpdatedByTwoRacersKeepBoth() throws Exception {
        // given
        String actor = race.created(actor("Architect"));

        // when
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.SLICE, actor,
                document().update(actor, Node.fields().with(ArkarchVocabulary.NAME, text("Lead architect", EN))),
                document().update(actor, Node.fields().with(DESCRIPTION, text("Designs the system.", EN))));
        outcome.racerResult();

        // then
        assertEquals(Set.of("Lead architect@en"), race.values(actor, ArkarchVocabulary.NAME));
        assertEquals(Set.of("Designs the system.@en"), race.values(actor, DESCRIPTION));
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
    }

    /** Counterpart of {@code ActorServiceRealStoreConcurrencyTest#updateOfNameInGerman...DoesNotLoseEitherLanguage}. */
    @Test
    void aGermanNameAndAnEnglishDescriptionWrittenByTwoRacersLoseNoLanguage() throws Exception {
        // given
        String actor = race.created(document().create(Node.typed(ArkprocVocabulary.HUMAN_ACTOR_TYPE)
                .with(ArkarchVocabulary.NAME, text("Case worker", EN))
                .with(DESCRIPTION, text("Handles applications.", EN))));

        // when
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.SLICE, actor,
                document().update(actor, Node.fields().with(ArkarchVocabulary.NAME, text("Antragsbearbeiter", DE))),
                document().update(actor, Node.fields()
                        .with(DESCRIPTION, text("Handles incoming applications in the back office.", EN))));
        outcome.racerResult();

        // then
        assertEquals(Set.of("Antragsbearbeiter@de", "Case worker@en"), race.values(actor, ArkarchVocabulary.NAME));
        assertEquals(Set.of("Handles incoming applications in the back office.@en"), race.values(actor, DESCRIPTION));
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
    }

    /** Counterpart of {@code RoleServiceRealStoreConcurrencyTest#updateRetriesAndKeepsBothCorrections...}. */
    @Test
    void aRoleNameAndDescriptionUpdatedByTwoRacersKeepBoth() throws Exception {
        // given
        String role = race.created(role(race.created(actor("Architect")), "Requirements Engineer"));

        // when
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.SLICE, role,
                document().update(role, Node.fields()
                        .with(ArkarchVocabulary.NAME, text("Senior Requirements Engineer", EN))),
                document().update(role, Node.fields().with(DESCRIPTION, text("Elicits requirements.", EN))));
        outcome.racerResult();

        // then
        assertEquals(Set.of("Senior Requirements Engineer@en"), race.values(role, ArkarchVocabulary.NAME));
        assertEquals(Set.of("Elicits requirements.@en"), race.values(role, DESCRIPTION));
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
    }

    /** Counterpart of {@code UseCaseServiceRealStoreConcurrencyTest#updateRetriesAndKeepsBothChanges...}. */
    @Test
    void aUseCaseTitleAndGoalUpdatedByTwoRacersKeepBoth() throws Exception {
        // given
        String role = race.created(role(race.created(actor("Architect")), "Modeller"));
        String useCase = race.created(document().create(Node.typed(ArkreqVocabulary.USE_CASE_TYPE)
                .with(DCT_TITLE, text("Model a decision", EN))
                .with(ArkreqVocabulary.USE_CASE_GOAL, text("A decision is recorded.", EN))
                .with(ArkreqVocabulary.PRIMARY_ROLE, iri(role))
                .with(ArkreqVocabulary.MAIN_STEP, Node.fields()
                        .with(ArkreqVocabulary.POSITION, integer(1))
                        .with(ArkreqVocabulary.STEP_TEXT, text("Writes the decision.", EN)).value())));

        // when
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.SLICE, useCase,
                document().update(useCase, Node.fields().with(DCT_TITLE, text("Record a decision", EN))),
                document().update(useCase, Node.fields()
                        .with(ArkreqVocabulary.USE_CASE_GOAL, text("The decision is stored.", EN))));
        outcome.racerResult();

        // then
        assertEquals(Set.of("Record a decision@en"), race.values(useCase, DCT_TITLE));
        assertEquals(Set.of("The decision is stored.@en"), race.values(useCase, ArkreqVocabulary.USE_CASE_GOAL));
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
    }

    /** Counterpart of {@code BoundedContextServiceRealStoreConcurrencyTest#linkTermRetriesAndKeepsBothEdges...}. */
    @Test
    void twoTermsLinkedToABoundedContextByTwoRacersKeepBothEdges() throws Exception {
        // given
        String context = race.created(boundedContext("Billing"));
        String invoice = race.created(term("Invoice"));
        String payment = race.created(term("Payment"));

        // when
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.SLICE, context,
                document().addTo(context, ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM, iri(invoice)),
                document().addTo(context, ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM, iri(payment)));
        outcome.racerResult();

        // then
        assertEquals(Set.of(invoice, payment), race.values(context, ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM));
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
    }

    /** Counterpart of {@code BoundedContextServiceRealStoreConcurrencyTest#updateOfNameAndUpdateOfDomainVision...}. */
    @Test
    void aBoundedContextNameAndDomainVisionUpdatedByTwoRacersKeepBoth() throws Exception {
        // given
        String context = race.created(boundedContext("Billing"));

        // when
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.SLICE, context,
                document().update(context, Node.fields().with(ArkarchVocabulary.NAME, text("Invoicing", EN))),
                document().update(context, Node.fields()
                        .with(ArkdddVocabulary.DOMAIN_VISION, text("Bills every order once.", EN))));
        outcome.racerResult();

        // then
        assertEquals(Set.of("Invoicing@en"), race.values(context, ArkarchVocabulary.NAME));
        assertEquals(Set.of("Bills every order once.@en"), race.values(context, ArkdddVocabulary.DOMAIN_VISION));
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
    }

    /**
     * Counterpart of {@code BoundedContextServiceRealStoreConcurrencyTest#concurrentLinkContextCalls
     * WithTheSameTriple..._loserGetsWinnersRelationship}, which leaves one relationship. Through
     * {@code submit} the same triple is not limited to one relationship at all - finding F-bc-1
     * (pinned without a race in {@code ContextRelationshipComparisonTest
     * #linkingTheSameTripleTwiceLeavesOneRelationshipInTheCoreAndTwoThroughSubmit}). This case states
     * the difference under a race: both racers commit and two relationships stand. It must flip when
     * a shape closes the gap - then to "the racer is rejected, one relationship" - and it is the case
     * that shows whether that shape also holds under a race, not only one write after the other.
     */
    @Test
    void theSameTripleLinkedByTwoRacersLeavesTwoRelationshipsFindingFbc1() throws Exception {
        // given
        String upstream = race.created(boundedContext("Ordering"));
        String downstream = race.created(boundedContext("Billing"));

        // when
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.SLICE, upstream,
                relationship(upstream, downstream), relationship(upstream, downstream));
        outcome.racerResult();

        // then
        Set<String> relationships = new TreeSet<>();
        for (Triple triple : race.reader().incoming(SubmitRealStoreTest.PROJECT, upstream)) {
            if (triple.predicate().equals(ArkdddVocabulary.UPSTREAM)) {
                relationships.add(triple.subject());
            }
        }
        assertEquals(2, relationships.size(), "F-bc-1: no shape limits a triple to one relationship");
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
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

    private static Document boundedContext(final String name) {
        return document().create(Node.typed(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE)
                .with(ArkarchVocabulary.NAME, text(name, EN))
                .with(ArkdddVocabulary.DOMAIN_VISION, text("Keeps " + name + " consistent.", EN)));
    }

    private static Document term(final String label) {
        return document().create(Node.typed(ArkreqVocabulary.CONCEPT_TYPE)
                .with(SKOS_PREF_LABEL, text(label, EN))
                .with(ArkreqVocabulary.DEFINITION, text("Means " + label + ".", EN)));
    }

    private static Document relationship(final String upstream, final String downstream) {
        return document().create(Node.typed(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE)
                .with(ArkdddVocabulary.UPSTREAM, iri(upstream))
                .with(ArkdddVocabulary.DOWNSTREAM, iri(downstream))
                .with(ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY, iri(ArkdddVocabulary.PARTNERSHIP)));
    }
}
