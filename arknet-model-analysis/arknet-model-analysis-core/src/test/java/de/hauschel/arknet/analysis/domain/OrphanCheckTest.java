// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
                new MentionFinding(ID + "fr-3", "FR-3", ID + "term-9", "TERM-9", "Kunde", "usesTerm"));
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
