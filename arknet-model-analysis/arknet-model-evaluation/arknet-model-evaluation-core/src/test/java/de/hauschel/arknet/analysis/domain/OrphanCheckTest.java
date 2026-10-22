// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.analysis.domain.OrphanCheck.MentionFinding;
import de.hauschel.arknet.analysis.domain.OrphanCheck.ResourceFinding;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
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
    private static final String ACCEPTANCE_CRITERION = ARKREQ + "acceptanceCriterion";
    private static final String CRITERION_TEXT = ARKREQ + "criterionText";
    private static final String USES_TERM = ARKREQ + "usesTerm";
    private static final String CONSTRAINED_BY = "http://open-services.net/ns/rm#constrainedBy";

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

        assertThat(result.unlinkedMentions()).containsExactly(
                new MentionFinding(ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                        List.of("description"), List.of()));
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

        assertThat(result.unlinkedMentions()).containsExactly(
                new MentionFinding(ID + "bc-2", "BC-2", ID + "term-8", "TERM-8", "Bestellung", "ubiquitousLanguageTerm",
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

    private static List<Triple> kundeTerm() {
        return List.of(
                iri(ID + "term-9", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-9", PREF_LABEL, "Kunde"),
                lit(ID + "term-9", IDENTIFIER, "TERM-9"));
    }

    @Test
    void aLowercaseWordDoesNotMentionATermWhoseLabelIsCapitalised() {
        final List<Triple> term = List.of(
                iri(ID + "term-8", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-8", PREF_LABEL, "Soll"),
                lit(ID + "term-8", IDENTIFIER, "TERM-8"));

        assertThat(run(term, citingRequirement("Der Kunde soll zahlen.")).unlinkedMentions()).isEmpty();
        assertThat(run(term, citingRequirement("Soll und Haben stehen sich gegenueber.")).unlinkedMentions())
                .hasSize(1);
    }

    @Test
    void aHyphenatedCompoundDoesNotMentionTheTermItStartsWith() {
        assertThat(run(kundeTerm(), citingRequirement("Der Kunde-Import l\u00e4uft.")).unlinkedMentions()).isEmpty();
    }

    private static OrphanCheck.Result run(final List<Triple> first, final List<Triple> second) {
        final List<Triple> all = new java.util.ArrayList<>(first);
        all.addAll(second);
        return OrphanCheck.run(TraceabilityGraph.of(StoreSnapshot.of(all), DisplayLocale.DEFAULT));
    }

    @Test
    void aMentionOnlyInAnAcceptanceCriterionIsAHintAndDoesNotCount() {
        OrphanCheck.Result result = run(kundeTerm(), List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                lit(ID + "fr-3", DESCRIPTION, "Das System zeigt Bestandsdaten."),
                iri(ID + "fr-3", ACCEPTANCE_CRITERION, ID + "ac-1"),
                lit(ID + "ac-1", CRITERION_TEXT, "Der Kunde sieht die Daten.")));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.mentionHints()).containsExactly(
                new MentionFinding(ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                        List.of("acceptanceCriterion"),
                        List.of(new MentionExemption.NonNormativeField("acceptanceCriterion"))));
        assertThat(result.total()).isEqualTo(result.orphanRequirements().size() + result.orphanTerms().size());
    }

    @Test
    void aMentionInTheDescriptionStaysAFindingAndNamesTheFieldsEvenWithACriterionHit() {
        OrphanCheck.Result result = run(kundeTerm(), List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                lit(ID + "fr-3", DESCRIPTION, "Der Kunde sieht Daten."),
                iri(ID + "fr-3", ACCEPTANCE_CRITERION, ID + "ac-1"),
                lit(ID + "ac-1", CRITERION_TEXT, "Der Kunde sieht die Daten.")));

        assertThat(result.unlinkedMentions()).containsExactly(
                new MentionFinding(ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                        List.of("description"), List.of()));
        assertThat(result.mentionHints()).isEmpty();
    }

    private static List<Triple> referencedRequirement(final boolean linksTheTerm) {
        final List<Triple> triples = new java.util.ArrayList<>(List.of(
                iri(ID + "fr-7", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-7", IDENTIFIER, "FR-7")));
        if (linksTheTerm) {
            triples.add(iri(ID + "fr-7", USES_TERM, ID + "term-9"));
        }
        return triples;
    }

    private static List<Triple> citingRequirement(final String description) {
        return List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                lit(ID + "fr-3", DESCRIPTION, description));
    }

    @Test
    void aMentionNextToAReferenceToARequirementThatLinksTheTermIsAHint() {
        for (final String description : List.of(
                "Der Kontoauszug zeigt den Kunde (FR-7).", "FR-7: Kunde wird gezeigt.", "Kunde, FR-7 gilt.")) {
            final List<Triple> base = new java.util.ArrayList<>(kundeTerm());
            base.addAll(referencedRequirement(true));

            final OrphanCheck.Result result = run(base, citingRequirement(description));

            assertThat(result.unlinkedMentions()).as(description).isEmpty();
            assertThat(result.mentionHints()).as(description).singleElement().satisfies(hint -> {
                assertThat(hint.sourceHandle()).isEqualTo("FR-3");
                assertThat(hint.exemptions()).containsExactly(new MentionExemption.AdjacentReference("FR-7"));
            });
        }
    }

    @Test
    void theReferenceRuleDoesNotApplyWhenTheReferencedRequirementLacksTheEdge() {
        final List<Triple> base = new java.util.ArrayList<>(kundeTerm());
        base.addAll(referencedRequirement(false));

        final OrphanCheck.Result result = run(base, citingRequirement("Der Kunde (FR-7) sieht Daten."));

        assertThat(result.unlinkedMentions()).hasSize(1);
        assertThat(result.mentionHints()).isEmpty();
    }

    @Test
    void theReferenceRuleDoesNotApplyWhenWordsSeparateMentionAndReference() {
        final List<Triple> base = new java.util.ArrayList<>(kundeTerm());
        base.addAll(referencedRequirement(true));

        final OrphanCheck.Result result = run(base, citingRequirement("Laut FR-7 muss der Kunde zahlen."));

        assertThat(result.unlinkedMentions()).hasSize(1);
        assertThat(result.mentionHints()).isEmpty();
    }

    @Test
    void aSelfReferenceDoesNotExemptAMention() {
        final List<Triple> base = new java.util.ArrayList<>(kundeTerm());

        final OrphanCheck.Result result = run(base, citingRequirement("Der Kunde (FR-3) zahlt."));

        assertThat(result.unlinkedMentions()).hasSize(1);
    }

    @Test
    void aSecondUnexemptedHitInTheSamePairKeepsTheFinding() {
        final List<Triple> base = new java.util.ArrayList<>(kundeTerm());
        base.addAll(referencedRequirement(true));

        final OrphanCheck.Result result = run(base, citingRequirement("Kunde (FR-7). Der Kunde zahlt."));

        assertThat(result.unlinkedMentions()).hasSize(1);
        assertThat(result.mentionHints()).isEmpty();
    }

    @Test
    void aReferenceAfterASentenceEndDoesNotExemptTheMention() {
        for (final String description : List.of(
                "Kunde. FR-7 gilt.", "Der Kunde zahlt. FR-7 gilt.", "Kunde! FR-7 gilt.", "Kunde; FR-7 gilt.",
                "Kunde? FR-7 gilt.", "FR-7 gilt. Kunde zahlt.", "Siehe FR-7. Kunde zahlt.")) {
            assertMentionStaysAFinding(description);
        }
    }

    @Test
    void aReferenceAcrossALineBreakAloneDoesNotExemptTheMention() {
        for (final String description : List.of("Kunde\nFR-7", "FR-7\nKunde", "Kunde\n\nFR-7")) {
            assertMentionStaysAFinding(description);
        }
    }

    @Test
    void aReferenceMoreThanThreeSeparatorsAwayDoesNotExemptTheMention() {
        for (final String description : List.of("Kunde -- FR-7", "Kunde (-- FR-7", "Kunde, : FR-7", "FR-7 -- Kunde")) {
            assertMentionStaysAFinding(description);
        }
    }

    @Test
    void aReferenceExactlyThreeSeparatorsAwayExemptsTheMention() {
        for (final String description : List.of("Kunde - FR-7", "Kunde / FR-7", "Kunde \u2013 FR-7",
                "Kunde \u2014 FR-7", "Kunde\t-\tFR-7", "Kunde\tFR-7", "FR-7 - Kunde")) {
            final List<Triple> base = new java.util.ArrayList<>(kundeTerm());
            base.addAll(referencedRequirement(true));

            final OrphanCheck.Result result = run(base, citingRequirement(description));

            assertThat(result.unlinkedMentions()).as(description).isEmpty();
            assertThat(result.mentionHints()).as(description).hasSize(1);
        }
    }

    private void assertMentionStaysAFinding(final String description) {
        final List<Triple> base = new java.util.ArrayList<>(kundeTerm());
        base.addAll(referencedRequirement(true));

        final OrphanCheck.Result result = run(base, citingRequirement(description));

        assertThat(result.unlinkedMentions()).as(description).hasSize(1);
        assertThat(result.mentionHints()).as(description).isEmpty();
    }

    @Test
    void severalExemptionsOfOnePairAreListedInTheOrderTheMentionsAreFound() {
        final List<Triple> base = new java.util.ArrayList<>(kundeTerm());
        base.addAll(referencedRequirement(true));
        base.addAll(List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + "fr-3", IDENTIFIER, "FR-3"),
                lit(ID + "fr-3", DESCRIPTION, "Der Kunde (FR-7) zahlt."),
                iri(ID + "fr-3", ACCEPTANCE_CRITERION, ID + "ac-1"),
                lit(ID + "ac-1", CRITERION_TEXT, "Der Kunde sieht die Daten.")));

        final OrphanCheck.Result result = OrphanCheck.run(TraceabilityGraph.of(
                StoreSnapshot.of(base), DisplayLocale.DEFAULT));

        assertThat(result.mentionHints()).singleElement().satisfies(hint -> {
            assertThat(hint.fields()).containsExactly("description", "acceptanceCriterion");
            assertThat(hint.exemptions()).containsExactly(
                    new MentionExemption.AdjacentReference("FR-7"),
                    new MentionExemption.NonNormativeField("acceptanceCriterion"));
        });
    }

    @Test
    void aReferenceToAnyResourceTypeThatLinksTheTermExemptsTheMention() {
        final String related = "http://www.w3.org/2004/02/skos/core#related";
        final List<List<Triple>> referenced = List.of(
                List.of(iri(ID + "uc-7", RDF_TYPE, USE_CASE), lit(ID + "uc-7", IDENTIFIER, "UC-7"),
                        iri(ID + "uc-7", USES_TERM, ID + "term-9")),
                List.of(iri(ID + "adr-7", RDF_TYPE, ArkarchVocabulary.ADR_TYPE), lit(ID + "adr-7", IDENTIFIER, "ADR-7"),
                        iri(ID + "adr-7", ArkarchVocabulary.USES_TERM, ID + "term-9")),
                List.of(iri(ID + "term-5", RDF_TYPE, SKOS + "Concept"), lit(ID + "term-5", PREF_LABEL, "Konto"),
                        lit(ID + "term-5", IDENTIFIER, "TERM-5"), iri(ID + "term-5", related, ID + "term-9")),
                List.of(iri(ID + "bc-7", RDF_TYPE, ARKDDD + "BoundedContext"), lit(ID + "bc-7", IDENTIFIER, "BC-7"),
                        iri(ID + "bc-7", UBIQUITOUS_LANGUAGE_TERM, ID + "term-9")));
        final List<String> codes = List.of("UC-7", "ADR-7", "TERM-5", "BC-7");

        for (int i = 0; i < referenced.size(); i++) {
            final List<Triple> base = new java.util.ArrayList<>(kundeTerm());
            base.addAll(referenced.get(i));

            final OrphanCheck.Result result = run(base, citingRequirement("Der Kunde (" + codes.get(i) + ") zahlt."));

            assertThat(result.unlinkedMentions()).as(codes.get(i)).isEmpty();
            assertThat(result.mentionHints()).as(codes.get(i)).singleElement().satisfies(hint ->
                    assertThat(hint.sourceHandle()).isEqualTo("FR-3"));
        }
    }

    private static Triple iri(String subject, String predicate, String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }

    private static Triple lit(String subject, String predicate, String lexical) {
        return new Triple(subject, predicate,
                new RdfNode.Literal(lexical, "http://www.w3.org/2001/XMLSchema#string", null));
    }
}
