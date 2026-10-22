// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.analysis.domain.OrphanCheck.MentionFinding;
import de.hauschel.arknet.analysis.domain.OrphanCheck.ResourceFinding;
import de.hauschel.arknet.analysis.domain.OrphanCheck.WeakMention;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph.WeakReason;
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
    private static final String ARKARCH = "https://w3id.org/arknet/architecture#";
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

    /** kogn-io/arknet#697: a term named only in an acceptance criterion is a hint, not a finding. */
    @Test
    void downgradesATermNamedOnlyInAnAcceptanceCriterionToAWeakMention() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Eine Bestandsabfrage."),
                criterion("fr-1", "ac-1", "Der Kunde sieht den Bestand."),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.weakMentions()).containsExactly(new WeakMention(
                ID + "fr-1", "FR-1", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                List.of("criterionText"), WeakReason.ONLY_IN_ACCEPTANCE_CRITERION, List.of()));
        assertThat(result.total()).isEqualTo(result.orphanRequirements().size() + result.orphanTerms().size());
    }

    @Test
    void keepsAMentionAFindingWhenTheDescriptionNamesTheTermToo() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Der Kunde fragt ab."),
                criterion("fr-1", "ac-1", "Der Kunde sieht den Bestand."),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).containsExactly(new MentionFinding(
                ID + "fr-1", "FR-1", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                List.of("description", "criterionText")));
        assertThat(result.weakMentions()).isEmpty();
    }

    @Test
    void downgradesAMentionNextToAReferenceWhoseTargetCarriesTheEdge() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Der Kunde (FR-2) wird informiert."),
                requirement("fr-2", "FR-2", "Anlage."),
                iri(ID + "fr-2", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.weakMentions()).containsExactly(new WeakMention(
                ID + "fr-1", "FR-1", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                List.of("description"), WeakReason.COVERED_BY_REFERENCE, List.of("FR-2")));
    }

    @Test
    void keepsAMentionAFindingWhenTheReferencedResourceLacksTheEdge() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Der Kunde (FR-2) wird informiert."),
                requirement("fr-2", "FR-2", "Anlage."),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::termHandle).containsExactly("TERM-9");
        assertThat(result.weakMentions()).isEmpty();
    }

    @Test
    void keepsAMentionAFindingWhenAnotherSentenceNamesTheTermWithoutAReference() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Der Kunde (FR-2) wird informiert. Danach prueft der Kunde den Status."),
                requirement("fr-2", "FR-2", "Anlage."),
                iri(ID + "fr-2", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::termHandle).containsExactly("TERM-9");
        assertThat(result.weakMentions()).isEmpty();
    }

    @Test
    void doesNotCountTheResourcesOwnCodeAsACoveringReference() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Der Kunde (FR-1) wird informiert."),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::termHandle).containsExactly("TERM-9");
        assertThat(result.weakMentions()).isEmpty();
    }

    @Test
    void keepsTheCriterionFieldOnAMentionCoveredByAReference() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Der Kunde (FR-2) zahlt."),
                criterion("fr-1", "ac-1", "Der Kunde sieht den Bestand."),
                requirement("fr-2", "FR-2", "Anlage."),
                iri(ID + "fr-2", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.weakMentions()).containsExactly(new WeakMention(
                ID + "fr-1", "FR-1", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                List.of("description", "criterionText"), WeakReason.COVERED_BY_REFERENCE, List.of("FR-2")));
    }

    @Test
    void keepsAMentionAFindingWhenOnlyOneOfTwoNormativeFieldsIsCovered() {
        OrphanCheck.Result result = run(
                useCase("uc-1", "UC-1", "Der Kunde meldet sich an.", "Der Kunde (FR-2) wird erfasst."),
                requirement("fr-2", "FR-2", "Anlage."),
                iri(ID + "fr-2", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).containsExactly(new MentionFinding(
                ID + "uc-1", "UC-1", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                List.of("useCaseGoal", "stepText")));
        assertThat(result.weakMentions()).isEmpty();
    }

    @Test
    void exemptsTheTermDefinitionSweepFromTheReferenceRule() {
        OrphanCheck.Result result = run(
                requirement("fr-2", "FR-2", "Anlage."),
                iri(ID + "fr-2", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"),
                term("term-8", "TERM-8", "Bestellung"),
                lit(ID + "term-8", SKOS + "definition", "Eine Bestellung gehoert zum Kunde (FR-2)."));

        assertThat(result.unlinkedMentions()).extracting(MentionFinding::termHandle, MentionFinding::edgeLocalName)
                .containsExactly(tuple("TERM-9", "broader/narrower/related"));
        assertThat(result.weakMentions()).isEmpty();
    }

    @Test
    void appliesTheReferenceRuleToAUseCaseStepText() {
        OrphanCheck.Result result = run(
                useCase("uc-1", "UC-1", null, "Der Kunde (FR-2) wird erfasst."),
                requirement("fr-2", "FR-2", "Anlage."),
                iri(ID + "fr-2", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.weakMentions()).containsExactly(new WeakMention(
                ID + "uc-1", "UC-1", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                List.of("stepText"), WeakReason.COVERED_BY_REFERENCE, List.of("FR-2")));
    }

    @Test
    void appliesTheReferenceRuleToAnAdrDecisionReferencingAUseCase() {
        OrphanCheck.Result result = run(
                adr("adr-1", "ADR-1", "Siehe UC-1 fuer den Kunde."),
                useCase("uc-1", "UC-1", null, null),
                iri(ID + "uc-1", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.weakMentions()).containsExactly(new WeakMention(
                ID + "adr-1", "ADR-1", ID + "term-9", "TERM-9", "Kunde", "usesTerm",
                List.of("adrDecision"), WeakReason.COVERED_BY_REFERENCE, List.of("UC-1")));
    }

    @Test
    void countsABoundedContextReferenceCarryingTheTermViaUbiquitousLanguage() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Der Kunde (BC-1) zahlt."),
                List.of(iri(ID + "bc-1", RDF_TYPE, ARKDDD + "BoundedContext"),
                        lit(ID + "bc-1", IDENTIFIER, "BC-1"),
                        iri(ID + "bc-1", UBIQUITOUS_LANGUAGE_TERM, ID + "term-9")),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.weakMentions()).extracting(WeakMention::coveredBy).containsExactly(List.of("BC-1"));
    }

    @Test
    void countsAnAdrReferenceCarryingTheTermViaArkarchUsesTerm() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Der Kunde (ADR-1) zahlt."),
                adr("adr-1", "ADR-1", "Entscheidung."),
                iri(ID + "adr-1", ARKARCH + "usesTerm", ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.weakMentions()).extracting(WeakMention::coveredBy).containsExactly(List.of("ADR-1"));
    }

    @Test
    void countsAReferenceBeforeTheTermInTheSameSentence() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Laut FR-2 muss der Kunde zahlen."),
                requirement("fr-2", "FR-2", "Anlage."),
                iri(ID + "fr-2", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.weakMentions()).extracting(WeakMention::coveredBy).containsExactly(List.of("FR-2"));
    }

    @Test
    void aColonBetweenReferenceAndTermKeepsTheMentionAFinding() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Laut FR-2: der Kunde zahlt."),
                requirement("fr-2", "FR-2", "Anlage."),
                iri(ID + "fr-2", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).hasSize(1);
        assertThat(result.weakMentions()).isEmpty();
    }

    @Test
    void aLineBreakBetweenReferenceAndTermKeepsTheMentionAFinding() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Laut FR-2\nder Kunde zahlt."),
                requirement("fr-2", "FR-2", "Anlage."),
                iri(ID + "fr-2", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).hasSize(1);
        assertThat(result.weakMentions()).isEmpty();
    }

    @Test
    void namesOnlyTheReferencesThatCarryTheTerm() {
        OrphanCheck.Result result = run(
                requirement("fr-1", "FR-1", "Der Kunde (FR-2, FR-3) zahlt."),
                requirement("fr-2", "FR-2", "Anlage."),
                requirement("fr-3", "FR-3", "Anlage."),
                iri(ID + "fr-3", USES_TERM, ID + "term-9"),
                term("term-9", "TERM-9", "Kunde"));

        assertThat(result.unlinkedMentions()).isEmpty();
        assertThat(result.weakMentions()).extracting(WeakMention::coveredBy).containsExactly(List.of("FR-3"));
    }

    private static List<Triple> useCase(String local, String code, String goal, String stepText) {
        List<Triple> triples = new ArrayList<>(List.of(
                iri(ID + local, RDF_TYPE, USE_CASE),
                lit(ID + local, IDENTIFIER, code)));
        if (goal != null) {
            triples.add(lit(ID + local, ARKREQ + "useCaseGoal", goal));
        }
        if (stepText != null) {
            triples.add(iri(ID + local, MAIN_STEP, ID + local + "-step"));
            triples.add(lit(ID + local + "-step", ARKREQ + "stepText", stepText));
        }
        return triples;
    }

    private static List<Triple> adr(String local, String code, String decision) {
        return List.of(
                iri(ID + local, RDF_TYPE, ARKARCH + "ArchitectureDecisionRecord"),
                lit(ID + local, IDENTIFIER, code),
                lit(ID + local, ARKARCH + "adrDecision", decision));
    }

    @SuppressWarnings("unchecked")
    private static OrphanCheck.Result run(Object... parts) {
        List<Triple> triples = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Triple triple) {
                triples.add(triple);
            } else {
                triples.addAll((List<Triple>) part);
            }
        }
        return OrphanCheck.run(TraceabilityGraph.of(StoreSnapshot.of(triples), DisplayLocale.DEFAULT));
    }

    private static List<Triple> requirement(String local, String code, String description) {
        return List.of(
                iri(ID + local, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(ID + local, IDENTIFIER, code),
                lit(ID + local, DESCRIPTION, description));
    }

    private static List<Triple> criterion(String owner, String local, String text) {
        return List.of(
                iri(ID + owner, ACCEPTANCE_CRITERION, ID + local),
                iri(ID + local, RDF_TYPE, ARKREQ + "AcceptanceCriterion"),
                lit(ID + local, CRITERION_TEXT, text));
    }

    private static List<Triple> term(String local, String code, String label) {
        return List.of(
                iri(ID + local, RDF_TYPE, SKOS + "Concept"),
                lit(ID + local, PREF_LABEL, label),
                lit(ID + local, IDENTIFIER, code));
    }

    private static Triple iri(String subject, String predicate, String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }

    private static Triple lit(String subject, String predicate, String lexical) {
        return new Triple(subject, predicate,
                new RdfNode.Literal(lexical, "http://www.w3.org/2001/XMLSchema#string", null));
    }
}
