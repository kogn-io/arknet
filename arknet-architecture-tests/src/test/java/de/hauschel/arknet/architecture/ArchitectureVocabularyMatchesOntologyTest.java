// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.iri;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.persistence.ArkarchVocabulary;

/**
 * Nails down that arknet's architecture-decision vocabulary has one meaning, not two.
 *
 * <p>The same terms are written down twice, in two modules that do not depend on each other:
 * {@link ArkarchVocabulary} in {@code arknet-persistence-support} is what the ADR out-adapter
 * actually writes into the store (and what {@code arknet-mcp}'s traceability read path traverses
 * back out), {@code arknet-architecture.ttl} in {@code arknet-ontology} is what arknet ships as the
 * documented meaning of those statements. Nothing connects them.
 *
 * <p><strong>Why this cannot be caught elsewhere.</strong> Every other ADR test phrases its
 * assertions with the very constants the adapter writes, so an IRI typo is symmetric and invisible:
 * rename {@code arkarch:adrStatus} to {@code arkarch:AdrStatus} in the Java class and the whole
 * suite stays green while the store fills with a predicate no ontology defines and every SPARQL
 * query written against the shipped vocabulary returns nothing. The bidirectional check is the same
 * seam {@code ProvenanceVocabularyMatchesOntologyTest}/{@code ProjectVocabularyMatchesOntologyTest}
 * guard for their modules.</p>
 *
 * <p><strong>The five lifecycle individuals are no longer asymmetric (kogn-io/arknet#357).</strong>
 * The SHACL shape {@code ashapes:ADR-status} admits all five, and the Java {@code AdrStatus} enum
 * now implements all five too - {@code Superseded} joined {@code Proposed}/{@code Accepted}/
 * {@code Rejected}/{@code Deprecated} (#91) once the {@code supersededBy} edge moved onto the
 * superseded decision itself, making the status a real write rather than a derived-only
 * reverse-read. {@link ArkarchVocabulary} names all five and this test holds it against all five,
 * exactly as before - only the reason a tool writes every one of them changed.</p>
 */
class ArchitectureVocabularyMatchesOntologyTest {

    private static final String ONTOLOGY_RESOURCE = "/arknet-architecture.ttl";

    /** Derived, never re-typed: a typo here would defeat the point of the comparison. */
    private static final String ARKARCH_NAMESPACE =
            ArkarchVocabulary.ADR_TYPE.substring(0, ArkarchVocabulary.ADR_TYPE.indexOf('#') + 1);

    private final Model ontology = parse(ONTOLOGY_RESOURCE, ArchitectureVocabularyMatchesOntologyTest.class);

    /**
     * The set of {@code arkarch:} terms is the same on both sides - this is the assertion that turns
     * red on any rename, on either side, in either direction.
     */
    @Test
    void theOntologyDeclaresExactlyTheArkarchTermsTheAdapterWrites() {
        Set<String> declared = ontology.subjects().stream()
                .filter(IRI.class::isInstance)
                .map(Resource::stringValue)
                .filter(iri -> iri.startsWith(ARKARCH_NAMESPACE))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkarchVocabulary.ADR_TYPE,
                ArkarchVocabulary.ADR_CONTEXT,
                ArkarchVocabulary.ADR_DECISION,
                ArkarchVocabulary.ADR_CONSEQUENCES,
                ArkarchVocabulary.ADR_ALTERNATIVES,
                ArkarchVocabulary.DECISION_DATE,
                ArkarchVocabulary.SUPERSEDES,
                ArkarchVocabulary.SUPERSEDED_BY,
                ArkarchVocabulary.RELATED_TO,
                ArkarchVocabulary.ADDRESSES_REQUIREMENT,
                ArkarchVocabulary.AFFECTS_CONTEXT,
                ArkarchVocabulary.ADR_STATUS_TYPE,
                ArkarchVocabulary.ADR_STATUS,
                ArkarchVocabulary.PROPOSED,
                ArkarchVocabulary.ACCEPTED,
                ArkarchVocabulary.REJECTED,
                ArkarchVocabulary.DEPRECATED,
                ArkarchVocabulary.SUPERSEDED,
                ArkarchVocabulary.CONSEQUENCE_TYPE_CLASS,
                ArkarchVocabulary.CONSEQUENCE,
                ArkarchVocabulary.CONSEQUENCE_STATEMENT,
                ArkarchVocabulary.CONSEQUENCE_TYPE_PROPERTY,
                ArkarchVocabulary.CONSEQUENCE_TYPE,
                ArkarchVocabulary.POSITIVE,
                ArkarchVocabulary.NEGATIVE,
                ArkarchVocabulary.NEUTRAL,
                ArkarchVocabulary.CONSIDERED_OPTION_TYPE_CLASS,
                ArkarchVocabulary.CONSIDERED_OPTION,
                ArkarchVocabulary.OPTION_RATIONALE,
                ArkarchVocabulary.OPTION_OUTCOME_PROPERTY,
                ArkarchVocabulary.OPTION_OUTCOME,
                ArkarchVocabulary.CHOSEN,
                ArkarchVocabulary.OPTION_REJECTED), declared,
                "arknet-architecture.ttl and ArkarchVocabulary must describe the same vocabulary");
    }

    /**
     * The out-adapter types every decision {@code arkarch:ArchitectureDecisionRecord} and targets the
     * SHACL shape at that very class - so it must be a class, not a stray IRI.
     */
    @Test
    void theOntologyDeclaresTheAdrClassTheAdapterTypesEveryDecisionWith() {
        assertTrue(ontology.contains(iri(ArkarchVocabulary.ADR_TYPE), RDF.TYPE, OWL.CLASS),
                "arkarch:ArchitectureDecisionRecord must be declared an owl:Class");
    }

    /**
     * The status is written as an object property pointing at one of the lifecycle individuals, not
     * as a literal - the shape's {@code sh:in} list only means what it says if the ontology agrees.
     */
    @Test
    void theOntologyDeclaresTheStatusPropertyAsWrittenByTheAdapter() {
        assertTrue(ontology.contains(iri(ArkarchVocabulary.ADR_STATUS), RDF.TYPE, OWL.OBJECTPROPERTY),
                "arkarch:adrStatus must be declared an owl:ObjectProperty");
        assertTrue(ontology.contains(iri(ArkarchVocabulary.ADR_STATUS), RDFS.RANGE,
                iri(ArkarchVocabulary.ADR_STATUS_TYPE)),
                "the adapter only ever points a status at an ADRStatus individual - the range must say so");
    }

    /**
     * Exactly five lifecycle individuals, no more: a sixth in the ontology would be a status the
     * shipped SHACL shape's {@code sh:in} list does not admit and no adapter can serialize. This is
     * the same bracket {@code ProjectVocabularyMatchesOntologyTest} puts around the three anchor
     * types.
     */
    @Test
    void theOntologyDeclaresExactlyTheFiveLifecycleIndividuals() {
        Set<String> individuals = ontology.filter(null, RDF.TYPE, iri(ArkarchVocabulary.ADR_STATUS_TYPE))
                .subjects().stream()
                .map(Resource::stringValue)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkarchVocabulary.PROPOSED,
                ArkarchVocabulary.ACCEPTED,
                ArkarchVocabulary.REJECTED,
                ArkarchVocabulary.DEPRECATED,
                ArkarchVocabulary.SUPERSEDED), individuals,
                "arkarch:ADRStatus must have exactly the five individuals the ADR shape's sh:in admits");
    }

    /**
     * The two cross-context edges the ADR context owns must point where the lookups resolve to: a
     * requirement and a bounded context respectively. Without this, a range typo would let the ADR
     * context ship edges that no traceability traversal on the other side can interpret.
     */
    @Test
    void theOntologyDeclaresTheCrossContextEdgeRangesTheLookupsResolveTo() {
        assertTrue(ontology.contains(iri(ArkarchVocabulary.ADDRESSES_REQUIREMENT), RDFS.RANGE,
                iri("https://w3id.org/arknet/requirements#Requirement")),
                "arkarch:addressesRequirement must range over arkreq:Requirement");
        assertTrue(ontology.contains(iri(ArkarchVocabulary.AFFECTS_CONTEXT), RDFS.RANGE,
                iri("https://w3id.org/arknet/ddd#BoundedContext")),
                "arkarch:affectsContext must range over arkddd:BoundedContext");
    }

    /**
     * {@code supersededBy} is declared the inverse of {@code supersedes} - unchanged by
     * kogn-io/arknet#357, even though which direction is actually <em>written</em> flipped: the
     * adapter now asserts {@code supersededBy} itself (on the superseded decision) and reads a
     * store-first record's pre-#357 {@code supersedes} triple back through this very
     * {@code owl:inverseOf} relationship, rather than the other way around as before. Either
     * direction, the declaration is what makes deriving one from the other a reading of the shipped
     * vocabulary instead of an invention.
     */
    @Test
    void theOntologyDeclaresSupersededByAsTheInverseOfSupersedes() {
        assertTrue(ontology.contains(iri(ArkarchVocabulary.SUPERSEDED_BY), OWL.INVERSEOF,
                iri(ArkarchVocabulary.SUPERSEDES)),
                "arkarch:supersededBy must be owl:inverseOf arkarch:supersedes");
    }

    /**
     * Exactly three consequence-type individuals, no more (kogn-io/arknet#357) - the same bracket
     * {@link #theOntologyDeclaresExactlyTheFiveLifecycleIndividuals} puts around {@code ADRStatus}
     * and {@code ProjectVocabularyMatchesOntologyTest} puts around its three anchor types. A
     * dedicated test rather than folding this into that one: {@code arkarch:ConsequenceType} and
     * {@code arkarch:OptionOutcome} (see below) are two more individual-bearing classes this module
     * shipped alongside {@code ADRStatus}, each with its own closed set the SHACL {@code sh:in} list
     * and the Java {@link de.hauschel.arknet.adr.domain.ConsequenceType}/
     * {@link de.hauschel.arknet.adr.domain.OptionOutcome} enums must not silently drift from - one
     * combined assertion across three unrelated classes would name the wrong class on failure.
     */
    @Test
    void theOntologyDeclaresExactlyTheThreeConsequenceTypeIndividuals() {
        Set<String> individuals = ontology.filter(null, RDF.TYPE, iri(ArkarchVocabulary.CONSEQUENCE_TYPE))
                .subjects().stream()
                .map(Resource::stringValue)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkarchVocabulary.POSITIVE,
                ArkarchVocabulary.NEGATIVE,
                ArkarchVocabulary.NEUTRAL), individuals,
                "arkarch:ConsequenceType must have exactly the three individuals the Consequence "
                        + "shape's sh:in admits");
    }

    /** {@link #theOntologyDeclaresExactlyTheThreeConsequenceTypeIndividuals} for {@code OptionOutcome}. */
    @Test
    void theOntologyDeclaresExactlyTheTwoOptionOutcomeIndividuals() {
        Set<String> individuals = ontology.filter(null, RDF.TYPE, iri(ArkarchVocabulary.OPTION_OUTCOME))
                .subjects().stream()
                .map(Resource::stringValue)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkarchVocabulary.CHOSEN,
                ArkarchVocabulary.OPTION_REJECTED), individuals,
                "arkarch:OptionOutcome must have exactly the two individuals the ConsideredOption "
                        + "shape's sh:in admits");
    }
}
