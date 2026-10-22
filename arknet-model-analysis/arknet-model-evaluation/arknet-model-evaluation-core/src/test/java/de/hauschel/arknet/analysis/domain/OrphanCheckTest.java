// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.analysis.domain.OrphanCheck.DowngradedMention;
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
                        List.of("description")));
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
                        List.of("domainVision")));
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

    // ---- kogn-io/arknet#697: field-aware classification of text mentions ----

    private static final String RATIONALE = ARKREQ + "rationale";
    private static final String ACCEPTANCE_CRITERION = ARKREQ + "acceptanceCriterion";
    private static final String CRITERION_TEXT = ARKREQ + "criterionText";
    private static final String POSITION = ARKREQ + "position";
    private static final String USES_TERM = ARKREQ + "usesTerm";
    private static final String STEP_TEXT = ARKREQ + "stepText";

    private static List<Triple> kundeTerm() {
        return List.of(
                iri(ID + "term-9", RDF_TYPE, SKOS + "Concept"),
                lit(ID + "term-9", PREF_LABEL, "Kunde"),
                lit(ID + "term-9", IDENTIFIER, "TERM-9"));
    }

    private static OrphanCheck.Result run(Triple... own) {
        ArrayList<Triple> all = new ArrayList<>(kundeTerm());
        all.addAll(List.of(own));
        return OrphanCheck.run(TraceabilityGraph.of(StoreSnapshot.of(all), DisplayLocale.DEFAULT));
    }

    private static Triple[] requirement(String name, String code, Triple... more) {
        ArrayList<Triple> all = new ArrayList<>(List.of(
                iri(ID + name, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + name, IDENTIFIER, code)));
        all.addAll(List.of(more));
        return all.toArray(new Triple[0]);
    }

    private static Triple[] concat(Triple[]... parts) {
        return Arrays.stream(parts).flatMap(Arrays::stream).toArray(Triple[]::new);
    }

    @Test
    void downgradesATermMentionedOnlyInAnAcceptanceCriterion() {
        OrphanCheck.Result result = run(requirement("fr-3", "FR-3",
                iri(ID + "fr-3", ACCEPTANCE_CRITERION, ID + "ac-1"),
                lit(ID + "ac-1", CRITERION_TEXT, "Fertig, wenn der Kunde bestaetigt."),
                lit(ID + "ac-1", POSITION, "2")));

        assertThat(result.unlinkedMentions()).isEmpty();
        // total() counts the finding lists only; the orphan requirement/term of this fixture are the 2.
        assertThat(result.total()).isEqualTo(result.orphanRequirements().size() + result.orphanTerms().size());
        assertThat(result.downgradedMentions()).containsExactly(new DowngradedMention(
                ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                List.of("acceptanceCriterion#2"), TraceabilityGraph.MentionClassification.ONLY_NON_NORMATIVE_FIELDS,
                List.of()));
    }

    @Test
    void downgradesATermMentionedOnlyInTheRationale() {
        OrphanCheck.Result result = run(requirement("fr-3", "FR-3",
                lit(ID + "fr-3", RATIONALE, "Weil der Kunde es will.")));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.downgradedMentions()).extracting(DowngradedMention::fields)
                .containsExactly(List.of("rationale"));
        assertThat(result.downgradedMentions().get(0).reason())
                .isEqualTo(TraceabilityGraph.MentionClassification.ONLY_NON_NORMATIVE_FIELDS);
    }

    @Test
    void keepsAFindingListingEveryFieldWhenTheDescriptionMentionsTheTermToo() {
        OrphanCheck.Result result = run(requirement("fr-3", "FR-3",
                lit(ID + "fr-3", DESCRIPTION, "Der Kunde sieht Daten."),
                lit(ID + "fr-3", RATIONALE, "Der Kunde will es."),
                iri(ID + "fr-3", ACCEPTANCE_CRITERION, ID + "ac-1"),
                lit(ID + "ac-1", CRITERION_TEXT, "Der Kunde sieht es."),
                lit(ID + "ac-1", POSITION, "1")));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::fields)
                .containsExactly(List.of("description", "rationale", "acceptanceCriterion#1"));
        assertThat(result.downgradedMentions()).isEmpty();
        assertThat(result.total()).isEqualTo(
                1 + result.orphanRequirements().size() + result.orphanTerms().size());
    }

    @Test
    void downgradesAMentionNextToAReferenceWhoseTargetCarriesTheEdge() {
        OrphanCheck.Result result = run(concat(
                requirement("fr-2", "FR-2", iri(ID + "fr-2", USES_TERM, ID + "term-9")),
                requirement("fr-3", "FR-3",
                        lit(ID + "fr-3", DESCRIPTION, "Der Kunde (FR-2) wird angezeigt."))));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.downgradedMentions()).hasSize(1);
        DowngradedMention hint = result.downgradedMentions().get(0);
        assertThat(hint.reason()).isEqualTo(TraceabilityGraph.MentionClassification.NEXT_TO_REFERENCE);
        assertThat(hint.referencedCodes()).containsExactly("FR-2");
        assertThat(hint.fields()).containsExactly("description");
    }

    @Test
    void downgradesAMentionPrecededByLautAndAReference() {
        OrphanCheck.Result result = run(concat(
                requirement("fr-2", "FR-2", iri(ID + "fr-2", USES_TERM, ID + "term-9")),
                requirement("fr-3", "FR-3", lit(ID + "fr-3", DESCRIPTION, "Laut FR-2 gilt: der Kunde zahlt."))));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.downgradedMentions()).hasSize(1);
    }

    @Test
    void keepsAFindingWhenTheReferencedRequirementDoesNotCarryTheEdge() {
        OrphanCheck.Result result = run(concat(
                requirement("fr-2", "FR-2"),
                requirement("fr-3", "FR-3", lit(ID + "fr-3", DESCRIPTION, "Der Kunde (FR-2) wird angezeigt."))));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::sourceHandle).containsExactly("FR-3");
        assertThat(result.downgradedMentions()).isEmpty();
    }

    @Test
    void keepsAFindingWhenTheReferenceIsOutsideTheWindowOrAcrossASentenceBoundary() {
        OrphanCheck.Result far = run(concat(
                requirement("fr-2", "FR-2", iri(ID + "fr-2", USES_TERM, ID + "term-9")),
                requirement("fr-3", "FR-3", lit(ID + "fr-3", DESCRIPTION,
                        "Der Kunde sieht dabei eine ganze Menge an sehr vielen Daten laut FR-2."))));
        OrphanCheck.Result sentence = run(concat(
                requirement("fr-2", "FR-2", iri(ID + "fr-2", USES_TERM, ID + "term-9")),
                requirement("fr-3", "FR-3", lit(ID + "fr-3", DESCRIPTION, "Der Kunde. Siehe FR-2."))));

        assertThat(far.unlinkedMentions()).hasSize(1);
        assertThat(far.downgradedMentions()).isEmpty();
        assertThat(sentence.unlinkedMentions()).hasSize(1);
        assertThat(sentence.downgradedMentions()).isEmpty();
    }

    @Test
    void keepsAUseCaseStepMentionAFindingNamingTheStepField() {
        OrphanCheck.Result result = run(
                iri(ID + "uc-1", RDF_TYPE, USE_CASE),
                lit(ID + "uc-1", IDENTIFIER, "UC-1"),
                iri(ID + "uc-1", MAIN_STEP, ID + "step-1"),
                lit(ID + "step-1", STEP_TEXT, "Der Kunde meldet sich an."),
                lit(ID + "step-1", POSITION, "3"));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::fields)
                .containsExactly(List.of("mainStep#3"));
        assertThat(result.downgradedMentions()).isEmpty();
    }

    private static final String ARKARCH = "https://w3id.org/arknet/architecture#";
    private static final String USE_CASE_GOAL = ARKREQ + "useCaseGoal";
    private static final String EXTENSION_STEP = ARKREQ + "extensionStep";

    @Test
    void ordersFieldsByPositionRegardlessOfTripleOrder() {
        OrphanCheck.Result criteria = run(requirement("fr-3", "FR-3",
                iri(ID + "fr-3", ACCEPTANCE_CRITERION, ID + "ac-2"),
                iri(ID + "fr-3", ACCEPTANCE_CRITERION, ID + "ac-1"),
                lit(ID + "ac-2", CRITERION_TEXT, "Der Kunde b."), lit(ID + "ac-2", POSITION, "2"),
                lit(ID + "ac-1", CRITERION_TEXT, "Der Kunde a."), lit(ID + "ac-1", POSITION, "1")));
        OrphanCheck.Result steps = run(
                iri(ID + "uc-1", RDF_TYPE, USE_CASE),
                lit(ID + "uc-1", IDENTIFIER, "UC-1"),
                iri(ID + "uc-1", MAIN_STEP, ID + "step-2"),
                iri(ID + "uc-1", MAIN_STEP, ID + "step-1"),
                lit(ID + "step-2", STEP_TEXT, "Der Kunde b."), lit(ID + "step-2", POSITION, "2"),
                lit(ID + "step-1", STEP_TEXT, "Der Kunde a."), lit(ID + "step-1", POSITION, "1"));

        assertThat(criteria.downgradedMentions()).extracting(DowngradedMention::fields)
                .containsExactly(List.of("acceptanceCriterion#1", "acceptanceCriterion#2"));
        assertThat(steps.unlinkedMentions()).extracting(MentionFinding::fields)
                .containsExactly(List.of("mainStep#1", "mainStep#2"));
    }

    @Test
    void namesExtensionStepsByPosition() {
        OrphanCheck.Result result = run(
                iri(ID + "uc-1", RDF_TYPE, USE_CASE),
                lit(ID + "uc-1", IDENTIFIER, "UC-1"),
                iri(ID + "uc-1", EXTENSION_STEP, ID + "ext-1"),
                lit(ID + "ext-1", STEP_TEXT, "Der Kunde bricht ab."),
                lit(ID + "ext-1", POSITION, "4"));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::fields)
                .containsExactly(List.of("extensionStep#4"));
    }

    @Test
    void doesNotMistakeFr1ForTheCodeFr12() {
        OrphanCheck.Result result = run(concat(
                requirement("fr-1", "FR-1", iri(ID + "fr-1", USES_TERM, ID + "term-9")),
                requirement("fr-3", "FR-3", lit(ID + "fr-3", DESCRIPTION, "Der Kunde (FR-12) wird angezeigt."))));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::sourceHandle).containsExactly("FR-3");
        assertThat(result.downgradedMentions()).isEmpty();
    }

    @Test
    void appliesTheReferenceWindowInclusivelyAtFortyCharacters() {
        Triple[] fr2 = requirement("fr-2", "FR-2", iri(ID + "fr-2", USES_TERM, ID + "term-9"));
        OrphanCheck.Result exactly = run(concat(fr2,
                requirement("fr-3", "FR-3", lit(ID + "fr-3", DESCRIPTION, "Kunde" + " ".repeat(1)
                        + "x".repeat(38) + " " + "FR-2"))));
        OrphanCheck.Result tooFar = run(concat(fr2,
                requirement("fr-3", "FR-3", lit(ID + "fr-3", DESCRIPTION, "Kunde" + " "
                        + "x".repeat(39) + " " + "FR-2"))));

        assertThat(exactly.unlinkedMentions()).isEmpty();
        assertThat(exactly.downgradedMentions()).hasSize(1);
        assertThat(tooFar.unlinkedMentions()).hasSize(1);
        assertThat(tooFar.downgradedMentions()).isEmpty();
    }

    @Test
    void aNewlineBetweenMentionAndReferenceKeepsTheFinding() {
        OrphanCheck.Result result = run(concat(
                requirement("fr-2", "FR-2", iri(ID + "fr-2", USES_TERM, ID + "term-9")),
                requirement("fr-3", "FR-3", lit(ID + "fr-3", DESCRIPTION, "- Kunde\n- FR-2"))));

        assertThat(result.unlinkedMentions()).hasSize(1);
        assertThat(result.downgradedMentions()).isEmpty();
    }

    @Test
    void aReferencedDecisionCarryingTheEdgeExplainsAUseCaseGoalMention() {
        OrphanCheck.Result result = run(
                iri(ID + "adr-1", RDF_TYPE, ARKARCH + "ArchitectureDecisionRecord"),
                lit(ID + "adr-1", IDENTIFIER, "ADR-1"),
                iri(ID + "adr-1", ARKARCH + "usesTerm", ID + "term-9"),
                iri(ID + "uc-1", RDF_TYPE, USE_CASE),
                lit(ID + "uc-1", IDENTIFIER, "UC-1"),
                lit(ID + "uc-1", USE_CASE_GOAL, "Der Kunde (ADR-1) bestellt."));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.downgradedMentions()).extracting(DowngradedMention::referencedCodes)
                .containsExactly(List.of("ADR-1"));
    }

    @Test
    void aReferenceExplainedNormativeHitPlusARationaleHitIsNextToReferenceListingBothFields() {
        OrphanCheck.Result result = run(concat(
                requirement("fr-2", "FR-2", iri(ID + "fr-2", USES_TERM, ID + "term-9")),
                requirement("fr-3", "FR-3",
                        lit(ID + "fr-3", DESCRIPTION, "Der Kunde (FR-2) wird angezeigt."),
                        lit(ID + "fr-3", RATIONALE, "Der Kunde will es."))));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.downgradedMentions()).hasSize(1);
        DowngradedMention hint = result.downgradedMentions().get(0);
        assertThat(hint.reason()).isEqualTo(TraceabilityGraph.MentionClassification.NEXT_TO_REFERENCE);
        assertThat(hint.fields()).containsExactly("description", "rationale");
    }

    @Test
    void theTermDefinitionSweepNeverAppliesTheReferenceRule() {
        OrphanCheck.Result result = run(concat(
                requirement("fr-2", "FR-2", iri(ID + "fr-2", USES_TERM, ID + "term-9")),
                new Triple[] {
                    iri(ID + "term-8", RDF_TYPE, SKOS + "Concept"),
                    lit(ID + "term-8", PREF_LABEL, "Konto"),
                    lit(ID + "term-8", IDENTIFIER, "TERM-8"),
                    lit(ID + "term-8", SKOS + "definition", "Ein Konto gehoert dem Kunde (FR-2).")}));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::sourceHandle).containsExactly("TERM-8");
        assertThat(result.downgradedMentions()).isEmpty();
    }

    private static Triple iri(String subject, String predicate, String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }

    private static Triple lit(String subject, String predicate, String lexical) {
        return new Triple(subject, predicate,
                new RdfNode.Literal(lexical, "http://www.w3.org/2001/XMLSchema#string", null));
    }
}
