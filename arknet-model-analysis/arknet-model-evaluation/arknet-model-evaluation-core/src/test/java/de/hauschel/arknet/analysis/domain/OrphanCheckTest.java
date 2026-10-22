// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.analysis.domain.OrphanCheck.MentionFinding;
import de.hauschel.arknet.analysis.domain.OrphanCheck.ResourceFinding;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreSnapshot;
import de.hauschel.arknet.persistence.Triple;

/**
 * Unit tests for {@link OrphanCheck} (kogn-io/arknet#473): the rule behind {@code store_check
 * ORPHAN}, folded in from the former {@code orphan_check} tool.
 *
 * <p>Fixtures mirror {@code TraceabilityRendererTest}'s, since this check runs over the same
 * {@link TraceabilityGraph} that renderer already builds - the point of this class is to call the
 * graph's traversal, not to duplicate it.</p>
 */
class OrphanCheckTest {

    private static final String ID = "https://w3id.org/arknet/id/";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String ARKDDD = "https://w3id.org/arknet/ddd#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String TITLE = "http://purl.org/dc/terms/title";
    private static final String DESCRIPTION = "http://purl.org/dc/terms/description";
    private static final String PREF_LABEL = SKOS + "prefLabel";
    private static final String IDENTIFIER = "http://purl.org/dc/terms/identifier";
    private static final String MAIN_STEP = ARKREQ + "mainStep";
    private static final String STEP_REALISES = ARKREQ + "stepRealises";
    private static final String SATISFIES = "http://open-services.net/ns/rm#satisfies";
    private static final String USE_CASE = ARKREQ + "UseCase";
    private static final String DOMAIN_VISION = ARKDDD + "domainVision";
    private static final String UBIQUITOUS_LANGUAGE_TERM = ARKDDD + "ubiquitousLanguageTerm";
    private static final String CONSTRAINED_BY = "http://open-services.net/ns/rm#constrainedBy";
    private static final String USES_TERM = ARKREQ + "usesTerm";
    private static final String RATIONALE = ARKREQ + "rationale";
    private static final String ACCEPTANCE_CRITERION = ARKREQ + "acceptanceCriterion";
    private static final String CRITERION_TEXT = ARKREQ + "criterionText";

    @Test
    void reportsNothingWhenEveryRequirementIsRealisedAndEveryTermIsReferenced() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-1", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-1", IDENTIFIER, "FR-1"),
                iri(ID + "fr-1", MAIN_STEP, ID + "step-1"),
                iri(ID + "step-1", STEP_REALISES, ID + "fr-1")));
        // A requirement realising itself through its own step is enough to exercise "realised", the
        // orphan-freedom of the model is what this test checks, not a specific use-case shape.

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.total()).isZero();
        assertThat(result.orphanRequirements()).isEmpty();
        assertThat(result.orphanTerms()).isEmpty();
        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.demotedMentions()).isEmpty();
        assertThat(result.orphanConstraints()).isEmpty();
    }

    @Test
    void reportsARequirementNoUseCaseRealisesWithItsHandleTypeAndLabel() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-2", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-2", IDENTIFIER, "FR-2"),
                lit(ID + "fr-2", TITLE, "Logout")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.orphanRequirements()).containsExactly(
                new ResourceFinding(ID + "fr-2", "FR-2", "FunctionalRequirement", "Logout"));
    }

    /** A use case binding a requirement via {@code oslc_rm:satisfies} carries it, step or no step. */
    @Test
    void doesNotReportARequirementOnlyASatisfiesEdgeBinds() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-4", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-4", IDENTIFIER, "FR-4"),
                iri(ID + "uc-4", RDF_TYPE, USE_CASE),
                iri(ID + "uc-4", SATISFIES, ID + "fr-4")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.orphanRequirements()).isEmpty();
    }

    @Test
    void reportsATermNoOneReferences() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "term-1", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-1", PREF_LABEL, "Passwort"),
                lit(ID + "term-1", IDENTIFIER, "TERM-1")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.orphanTerms()).containsExactly(
                new ResourceFinding(ID + "term-1", "TERM-1", "Concept", "Passwort"));
    }

    /** A term linked only via a bounded context's ubiquitous language must not be reported. */
    @Test
    void doesNotReportATermLinkedOnlyViaTheBoundedContextsUbiquitousLanguage() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "term-10", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-10", PREF_LABEL, "Vertrag"),
                lit(ID + "term-10", IDENTIFIER, "TERM-10"),

                iri(ID + "bc-2", RDF_TYPE, ARKDDD + "BoundedContext"),
                lit(ID + "bc-2", IDENTIFIER, "BC-2"),
                iri(ID + "bc-2", UBIQUITOUS_LANGUAGE_TERM, ID + "term-10")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.orphanTerms()).isEmpty();
    }

    @Test
    void reportsATextMentionOfATermWithoutTheBackingEdge() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", TITLE, "Bestandsdaten"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                lit(ID + "fr-3", DESCRIPTION, "Der Kunde sieht seine Bestandsdaten ein."),

                iri(ID + "term-9", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-9", PREF_LABEL, "Kunde"),
                lit(ID + "term-9", IDENTIFIER, "TERM-9")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.unlinkedMentions()).containsExactly(new MentionFinding(
                ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm", List.of("description"), List.of()));
        assertThat(result.demotedMentions()).isEmpty();
    }

    /**
     * kogn-io/arknet#697: a term named only in a requirement's rationale or acceptance criterion is
     * demoted - returned in its own list with the field it was found in, outside the count.
     */
    @Test
    void demotesAMentionFoundOnlyInARationaleOrAnAcceptanceCriterion() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                lit(ID + "fr-3", DESCRIPTION, "Das System zeigt die Bestandsdaten an."),
                lit(ID + "fr-3", RATIONALE, "Der Kunde fragt danach."),
                iri(ID + "fr-3", ACCEPTANCE_CRITERION, ID + "ac-1"),
                lit(ID + "ac-1", CRITERION_TEXT, "Beispiel: Eine Bestellung wird angezeigt."),

                iri(ID + "term-9", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-9", PREF_LABEL, "Kunde"),
                lit(ID + "term-9", IDENTIFIER, "TERM-9"),
                iri(ID + "term-8", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-8", PREF_LABEL, "Bestellung"),
                lit(ID + "term-8", IDENTIFIER, "TERM-8")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.demotedMentions()).containsExactly(
                new MentionFinding(ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                        List.of("rationale"), List.of()),
                new MentionFinding(ID + "fr-3", "FR-3", ID + "term-8", "TERM-8", "Bestellung", "usesTerm",
                        List.of("acceptanceCriterion"), List.of()));
    }

    /** One hit in the description is enough to keep the pair a finding; the criterion field is still listed. */
    @Test
    void keepsAMentionAFindingWhenTheDescriptionNamesTheTermToo() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                lit(ID + "fr-3", DESCRIPTION, "Der Kunde sieht seine Bestandsdaten ein."),
                iri(ID + "fr-3", ACCEPTANCE_CRITERION, ID + "ac-1"),
                lit(ID + "ac-1", CRITERION_TEXT, "Ein Kunde ohne Daten sieht eine leere Liste."),

                iri(ID + "term-9", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-9", PREF_LABEL, "Kunde"),
                lit(ID + "term-9", IDENTIFIER, "TERM-9")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.unlinkedMentions()).containsExactly(new MentionFinding(
                ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                List.of("description", "acceptanceCriterion"), List.of()));
        assertThat(result.demotedMentions()).isEmpty();
    }

    /**
     * kogn-io/arknet#697, the reference rule: "Kontoauszug (FR-1)" quotes FR-1, and FR-1 already
     * carries the edge to the term - the term belongs to FR-1, so FR-3's mention is demoted and
     * names FR-1 as the reason. The same sentence with a reference to a requirement that does
     * <em>not</em> link the term stays a finding.
     */
    @Test
    void demotesAMentionStandingNextToAReferenceToAResourceThatLinksTheTerm() {
        List<Triple> common = List.of(
                iri(ID + "fr-1", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-1", IDENTIFIER, "FR-1"),
                lit(ID + "fr-1", DESCRIPTION, "Das System importiert den Kontoauszug."),
                iri(ID + "fr-1", USES_TERM, ID + "term-7"),
                iri(ID + "fr-2", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-2", IDENTIFIER, "FR-2"),
                lit(ID + "fr-2", DESCRIPTION, "Das System archiviert Belege."),
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                iri(ID + "term-7", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-7", PREF_LABEL, "Kontoauszug"),
                lit(ID + "term-7", IDENTIFIER, "TERM-7"));

        OrphanCheck.Result quotingLinker = OrphanCheck.run(TraceabilityGraph.of(StoreSnapshot.of(with(common,
                lit(ID + "fr-3", DESCRIPTION, "Nach dem Kontoauszug (FR-1) folgt die Zuordnung."))),
                DisplayLocale.DEFAULT));
        OrphanCheck.Result quotingOther = OrphanCheck.run(TraceabilityGraph.of(StoreSnapshot.of(with(common,
                lit(ID + "fr-3", DESCRIPTION, "Nach dem Kontoauszug (FR-2) folgt die Zuordnung."))),
                DisplayLocale.DEFAULT));
        OrphanCheck.Result referenceAhead = OrphanCheck.run(TraceabilityGraph.of(StoreSnapshot.of(with(common,
                lit(ID + "fr-3", DESCRIPTION, "Laut FR-1 muss der Kontoauszug zugeordnet werden."))),
                DisplayLocale.DEFAULT));
        OrphanCheck.Result referenceFarAway = OrphanCheck.run(TraceabilityGraph.of(StoreSnapshot.of(with(common,
                lit(ID + "fr-3", DESCRIPTION, "Der Kontoauszug wird jeder Buchung zugeordnet, siehe FR-1."))),
                DisplayLocale.DEFAULT));

        assertThat(quotingLinker.unlinkedMentions()).isEmpty();
        assertThat(quotingLinker.demotedMentions()).containsExactly(new MentionFinding(
                ID + "fr-3", "FR-3", ID + "term-7", "TERM-7", "Kontoauszug", "usesTerm",
                List.of("description"), List.of("FR-1")));
        assertThat(quotingOther.demotedMentions()).isEmpty();
        assertThat(quotingOther.unlinkedMentions()).extracting(MentionFinding::sourceHandle).containsExactly("FR-3");
        assertThat(referenceAhead.demotedMentions()).extracting(MentionFinding::referencedHandles)
                .containsExactly(List.of("FR-1"));
        assertThat(referenceFarAway.demotedMentions()).isEmpty();
        assertThat(referenceFarAway.unlinkedMentions()).extracting(MentionFinding::sourceHandle).containsExactly("FR-3");
    }

    /**
     * kogn-io/arknet#696: under a German display language a capitalised label keeps its capital -
     * the modal verb "soll" is not the term "Soll" - while an English display language, the
     * default, still matches regardless of case.
     */
    @Test
    void matchesALabelsInitialCapitalOnlyUnderAGermanDisplayLanguage() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                lit(ID + "fr-3", DESCRIPTION, "Das System soll die Bestandsdaten anzeigen."),
                iri(ID + "fr-4", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-4", IDENTIFIER, "FR-4"),
                lit(ID + "fr-4", DESCRIPTION, "Das Soll wird dem Ist gegenuebergestellt."),

                iri(ID + "term-6", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-6", PREF_LABEL, "Soll"),
                lit(ID + "term-6", IDENTIFIER, "TERM-6")));

        OrphanCheck.Result german = OrphanCheck.run(TraceabilityGraph.of(
                snapshot, new DisplayLocale(Locale.GERMAN, Locale.ENGLISH)));
        OrphanCheck.Result english = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(german.unlinkedMentions()).extracting(MentionFinding::sourceHandle).containsExactly("FR-4");
        assertThat(english.unlinkedMentions()).extracting(MentionFinding::sourceHandle).containsExactly("FR-3", "FR-4");
    }

    /** kogn-io/arknet#696: a hyphenated compound does not mention its part. */
    @Test
    void doesNotReportATermInsideAHyphenatedCompound() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                lit(ID + "fr-3", DESCRIPTION, "Der Kontoauszug-Import laeuft naechtlich."),

                iri(ID + "term-7", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-7", PREF_LABEL, "Kontoauszug"),
                lit(ID + "term-7", IDENTIFIER, "TERM-7")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.demotedMentions()).isEmpty();
    }

    private static List<Triple> with(List<Triple> base, Triple extra) {
        List<Triple> all = new java.util.ArrayList<>(base);
        all.add(extra);
        return all;
    }

    /**
     * Mirrors {@link #reportsATextMentionOfATermWithoutTheBackingEdge()} for the second mention
     * source a bounded context's {@code arkddd:domainVision} - the only fixture, before
     * kogn-io/arknet#473, that pinned {@code edgeLocalName = "ubiquitousLanguageTerm"}.
     */
    @Test
    void reportsABoundedContextDomainVisionMentionOfATermWithoutTheBackingEdge() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "bc-2", RDF_TYPE, ARKDDD + "BoundedContext"),
                lit(ID + "bc-2", IDENTIFIER, "BC-2"),
                lit(ID + "bc-2", DOMAIN_VISION, "Wir verwalten die Bestellung."),

                iri(ID + "term-8", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-8", PREF_LABEL, "Bestellung"),
                lit(ID + "term-8", IDENTIFIER, "TERM-8")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.unlinkedMentions()).containsExactly(new MentionFinding(
                ID + "bc-2", "BC-2", ID + "term-8", "TERM-8", "Bestellung", "ubiquitousLanguageTerm",
                List.of("domainVision"), List.of()));
    }

    /**
     * CON-2 is a constraint no requirement or use case is bound by via {@code
     * oslc_rm:constrainedBy}; CON-1 is bound by FR-1 and must not appear.
     */
    @Test
    void reportsAConstraintNoRequirementOrUseCaseIsBoundBy() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-1", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-1", IDENTIFIER, "FR-1"),
                iri(ID + "fr-1", CONSTRAINED_BY, ID + "con-1"),

                iri(ID + "con-1", RDF_TYPE, ARKREQ + "TechnicalConstraint"),
                lit(ID + "con-1", IDENTIFIER, "CON-1"),

                iri(ID + "con-2", RDF_TYPE, ARKREQ + "TechnicalConstraint"),
                lit(ID + "con-2", TITLE, "PostgreSQL only"),
                lit(ID + "con-2", IDENTIFIER, "CON-2")));

        OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT));

        assertThat(result.orphanConstraints()).containsExactly(
                new ResourceFinding(ID + "con-2", "CON-2", "TechnicalConstraint", "PostgreSQL only"));
    }

    private static Triple iri(String subject, String predicate, String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }

    private static Triple lit(String subject, String predicate, String lexical) {
        return new Triple(subject, predicate,
                new RdfNode.Literal(lexical, "http://www.w3.org/2001/XMLSchema#string", null));
    }
}
