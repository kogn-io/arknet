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

import de.hauschel.arknet.persistence.ArkdddVocabulary;

/**
 * Nails down that arknet's strategic-DDD vocabulary has one meaning, not two.
 *
 * <p>The same terms are written down twice, in two modules that do not depend on each other:
 * {@link ArkdddVocabulary} in {@code arknet-persistence-support} is what the bounded-context
 * out-adapters actually write into the store (and what {@code arknet-mcp}'s traceability read
 * path traverses back out), {@code arknet-ddd.ttl} in {@code arknet-ontology} is what arknet ships
 * as the documented meaning of those statements. Nothing connects them.
 *
 * <p><strong>Why this cannot be caught elsewhere (kogn-io/arknet#148).</strong> Every other DDD
 * test phrases its assertions with the very constants the adapter writes, so an IRI typo is
 * symmetric and invisible - and worse, before this class existed the same IRI literal was
 * declared as a private copy in up to four different places
 * ({@code KognioRdfBoundedContextRepository}, {@code KognioRdfContextRelationshipRepository} and
 * its factory, {@code KognioRdfAdrRepository}, {@code KognioRdfBoundedContextLookup},
 * {@code TraceabilityGraph}) with nothing checking they agreed. {@link ArkdddVocabulary}'s own
 * javadoc used to claim {@code arkddd:BoundedContext}/{@code ubiquitousLanguageTerm} "stay local"
 * - a claim this very drift risk proved false. The bidirectional check below is the same seam
 * {@code ProvenanceVocabularyMatchesOntologyTest}/{@code ProjectVocabularyMatchesOntologyTest}/
 * {@code ArchitectureVocabularyMatchesOntologyTest} guard for their modules.
 *
 * <p>Deliberately bidirectional. A constant without an ontology term ships a statement arknet
 * never explains; an ontology term without a constant means the documented vocabulary has a
 * second, unimplemented half. Both are drift, and neither should be discovered by a user.</p>
 */
class DddVocabularyMatchesOntologyTest {

    private static final String ONTOLOGY_RESOURCE = "/arknet-ddd.ttl";

    /** Derived, never re-typed: a typo here would defeat the point of the comparison. */
    private static final String ARKDDD_NAMESPACE =
            ArkdddVocabulary.BOUNDED_CONTEXT_TYPE.substring(0, ArkdddVocabulary.BOUNDED_CONTEXT_TYPE.indexOf('#') + 1);

    private final Model ontology = parse(ONTOLOGY_RESOURCE, DddVocabularyMatchesOntologyTest.class);

    /**
     * The set of {@code arkddd:} terms is the same on both sides - this is the assertion that
     * turns red on any rename, on either side, in either direction.
     */
    @Test
    void theOntologyDeclaresExactlyTheArkdddTermsTheAdaptersWrite() {
        Set<String> declared = ontology.subjects().stream()
                .filter(IRI.class::isInstance)
                .map(Resource::stringValue)
                .filter(iri -> iri.startsWith(ARKDDD_NAMESPACE))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkdddVocabulary.DOMAIN_TYPE,
                ArkdddVocabulary.SUBDOMAIN_TYPE_CLASS,
                ArkdddVocabulary.CORE_DOMAIN,
                ArkdddVocabulary.SUPPORTING_DOMAIN,
                ArkdddVocabulary.GENERIC_DOMAIN,
                ArkdddVocabulary.SUBDOMAIN_CLASS,
                ArkdddVocabulary.SUBDOMAIN_TYPE_PROPERTY,
                ArkdddVocabulary.HAS_SUBDOMAIN,
                ArkdddVocabulary.BOUNDED_CONTEXT_TYPE,
                ArkdddVocabulary.PART_OF_PROPERTY,
                ArkdddVocabulary.HAS_CONTEXT,
                ArkdddVocabulary.DOMAIN_VISION,
                ArkdddVocabulary.OWNED_BY_PROPERTY,
                ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM,
                ArkdddVocabulary.HAS_AGGREGATE_PROPERTY,
                ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE,
                ArkdddVocabulary.RELATIONSHIP_TYPE_CLASS,
                ArkdddVocabulary.PARTNERSHIP,
                ArkdddVocabulary.SHARED_KERNEL,
                ArkdddVocabulary.CUSTOMER_SUPPLIER,
                ArkdddVocabulary.CONFORMIST,
                ArkdddVocabulary.ANTICORRUPTION_LAYER,
                ArkdddVocabulary.OPEN_HOST_SERVICE,
                ArkdddVocabulary.PUBLISHED_LANGUAGE,
                ArkdddVocabulary.SEPARATE_WAYS,
                ArkdddVocabulary.UPSTREAM,
                ArkdddVocabulary.DOWNSTREAM,
                ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY), declared,
                "arknet-ddd.ttl and ArkdddVocabulary must describe the same vocabulary");
    }

    /** The out-adapter types every bounded context and every context relationship with these classes. */
    @Test
    void theOntologyDeclaresBoundedContextAndContextRelationshipAsClasses() {
        assertTrue(ontology.contains(iri(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE), RDF.TYPE, OWL.CLASS),
                "arkddd:BoundedContext must be declared an owl:Class");
        assertTrue(ontology.contains(iri(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE), RDF.TYPE, OWL.CLASS),
                "arkddd:ContextRelationship must be declared an owl:Class");
    }

    /** The bounded-context out-adapter writes {@code ubiquitousLanguageTerm} as an edge into the glossary. */
    @Test
    void theOntologyDeclaresUbiquitousLanguageTermAsWrittenByTheAdapter() {
        assertTrue(ontology.contains(iri(ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM), RDF.TYPE, OWL.OBJECTPROPERTY),
                "arkddd:ubiquitousLanguageTerm must be declared an owl:ObjectProperty");
        assertTrue(ontology.contains(iri(ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM), RDFS.RANGE,
                iri("http://www.w3.org/2004/02/skos/core#Concept")),
                "arkddd:ubiquitousLanguageTerm must range over skos:Concept");
    }

    /**
     * The two edges every context relationship carries must point at a bounded context, and its
     * classification edge must point at one of the eight relationship-type individuals - without
     * this, a range typo would let the bc context ship a relationship no traceability traversal on
     * the other side can interpret.
     */
    @Test
    void theOntologyDeclaresTheContextRelationshipEdgesTheAdapterWrites() {
        assertTrue(ontology.contains(iri(ArkdddVocabulary.UPSTREAM), RDFS.RANGE,
                iri(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE)),
                "arkddd:upstream must range over arkddd:BoundedContext");
        assertTrue(ontology.contains(iri(ArkdddVocabulary.DOWNSTREAM), RDFS.RANGE,
                iri(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE)),
                "arkddd:downstream must range over arkddd:BoundedContext");
        assertTrue(ontology.contains(iri(ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY), RDF.TYPE, OWL.OBJECTPROPERTY),
                "arkddd:relationshipType must be declared an owl:ObjectProperty");
        assertTrue(ontology.contains(iri(ArkdddVocabulary.RELATIONSHIP_TYPE_PROPERTY), RDFS.RANGE,
                iri(ArkdddVocabulary.RELATIONSHIP_TYPE_CLASS)),
                "arkddd:relationshipType must range over arkddd:RelationshipType");
    }

    /**
     * Exactly eight relationship-type individuals, no more: a ninth in the ontology would be a
     * relationship type the {@code RelationshipType} Java enum has no constant to serialise, and a
     * missing one would mean the enum offers a value the ontology never declared. The same bracket
     * {@code ArchitectureVocabularyMatchesOntologyTest} puts around {@code ADRStatus}.
     */
    @Test
    void theOntologyDeclaresExactlyTheEightRelationshipTypeIndividuals() {
        Set<String> individuals = ontology.filter(null, RDF.TYPE, iri(ArkdddVocabulary.RELATIONSHIP_TYPE_CLASS))
                .subjects().stream()
                .map(Resource::stringValue)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkdddVocabulary.PARTNERSHIP,
                ArkdddVocabulary.SHARED_KERNEL,
                ArkdddVocabulary.CUSTOMER_SUPPLIER,
                ArkdddVocabulary.CONFORMIST,
                ArkdddVocabulary.ANTICORRUPTION_LAYER,
                ArkdddVocabulary.OPEN_HOST_SERVICE,
                ArkdddVocabulary.PUBLISHED_LANGUAGE,
                ArkdddVocabulary.SEPARATE_WAYS), individuals,
                "arkddd:RelationshipType must have exactly the eight individuals RelationshipType "
                        + "(bc-core) enumerates");
    }

    /**
     * Exactly three subdomain-classification individuals, no more - the {@link #theOntologyDeclaresExactlyTheEightRelationshipTypeIndividuals}
     * bracket for the second individual-bearing class this module ships.
     */
    @Test
    void theOntologyDeclaresExactlyTheThreeSubdomainTypeIndividuals() {
        Set<String> individuals = ontology.filter(null, RDF.TYPE, iri(ArkdddVocabulary.SUBDOMAIN_TYPE_CLASS))
                .subjects().stream()
                .map(Resource::stringValue)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkdddVocabulary.CORE_DOMAIN,
                ArkdddVocabulary.SUPPORTING_DOMAIN,
                ArkdddVocabulary.GENERIC_DOMAIN), individuals,
                "arkddd:SubdomainType must have exactly the three individuals Subdomain (bc-core) enumerates");
    }
}
