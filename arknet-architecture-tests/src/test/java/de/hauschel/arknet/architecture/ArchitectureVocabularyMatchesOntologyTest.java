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
 * <p><strong>One asymmetry the shapes file rather than this test guards.</strong> The SHACL shape
 * {@code ashapes:ADR-status} admits all five lifecycle individuals while the Java {@code AdrStatus}
 * enum implements only {@code Proposed}/{@code Accepted}/{@code Rejected}/{@code Deprecated} (#91).
 * {@code Superseded} is deliberately left out - it stays derived-only from the
 * {@code supersedes}/{@code supersededBy} reverse-read rather than a fifth status value, so
 * {@link ArkarchVocabulary} names all five and this test holds it against all five - a vocabulary
 * constant is a serialization fact, not a claim that a tool writes it.</p>
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
                ArkarchVocabulary.SUPERSEDED), declared,
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
     * {@code supersededBy} is declared the inverse of {@code supersedes} - which is precisely why the
     * ADR context asserts only the forward triple and derives the backward direction by a reverse
     * read. If the ontology stopped saying so, that derivation would be an invention rather than a
     * reading of the shipped vocabulary.
     */
    @Test
    void theOntologyDeclaresSupersededByAsTheInverseTheAdapterNeverWrites() {
        assertTrue(ontology.contains(iri(ArkarchVocabulary.SUPERSEDED_BY), OWL.INVERSEOF,
                iri(ArkarchVocabulary.SUPERSEDES)),
                "arkarch:supersededBy must be owl:inverseOf arkarch:supersedes");
    }
}
