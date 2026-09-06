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

import de.hauschel.arknet.persistence.ArkprocVocabulary;

/**
 * Nails down that arknet's actor vocabulary has one meaning, not two.
 *
 * <p>The same terms are written down twice, in two modules that do not depend on each other:
 * {@link ArkprocVocabulary} in {@code arknet-persistence-support} is what the actor out-adapter
 * actually writes into the store (and what {@code arknet-mcp}'s traceability read path and
 * {@code arknet-use-cases}' cross-BC actor resolution traverse back out),
 * {@code arknet-actor.ttl} in {@code arknet-ontology} is what arknet ships as the documented
 * meaning of those statements. Nothing connects them.
 *
 * <p><strong>Why this cannot be caught elsewhere (kogn-io/arknet#148).</strong> Before
 * {@link ArkprocVocabulary} existed, each of {@link ArkprocVocabulary#HUMAN_ACTOR_TYPE}/
 * {@link ArkprocVocabulary#SYSTEM_ACTOR_TYPE}/{@link ArkprocVocabulary#LEGAL_ACTOR_TYPE}/
 * {@link ArkprocVocabulary#GROUP_ACTOR_TYPE} was declared as a private copy of the same four IRI
 * literals in three different places ({@code KognioRdfActorRepository},
 * {@code TraceabilityGraph}, {@code KognioRdfActorLookup}), with nothing checking they agreed - an
 * IRI typo in one place would compile and test green while silently desynchronising the write and
 * read sides. The bidirectional check below is the same seam
 * {@code ProvenanceVocabularyMatchesOntologyTest}/{@code ProjectVocabularyMatchesOntologyTest}/
 * {@code ArchitectureVocabularyMatchesOntologyTest}/{@code DddVocabularyMatchesOntologyTest}
 * guard for their modules.</p>
 *
 * <p>Deliberately bidirectional. A constant without an ontology term ships a statement arknet
 * never explains; an ontology term without a constant means the documented vocabulary has a
 * second, unimplemented half. Both are drift, and neither should be discovered by a user.</p>
 */
class ActorVocabularyMatchesOntologyTest {

    private static final String ONTOLOGY_RESOURCE = "/arknet-actor.ttl";

    /** Derived, never re-typed: a typo here would defeat the point of the comparison. */
    private static final String ARKPROC_NAMESPACE =
            ArkprocVocabulary.ACTOR_TYPE.substring(0, ArkprocVocabulary.ACTOR_TYPE.indexOf('#') + 1);

    private final Model ontology = parse(ONTOLOGY_RESOURCE, ActorVocabularyMatchesOntologyTest.class);

    /**
     * The set of {@code arkproc:} terms is the same on both sides - this is the assertion that
     * turns red on any rename, on either side, in either direction.
     */
    @Test
    void theOntologyDeclaresExactlyTheArkprocTermsTheAdapterWrites() {
        Set<String> declared = ontology.subjects().stream()
                .filter(IRI.class::isInstance)
                .map(Resource::stringValue)
                .filter(iri -> iri.startsWith(ARKPROC_NAMESPACE))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkprocVocabulary.ACTOR_TYPE,
                ArkprocVocabulary.HUMAN_ACTOR_TYPE,
                ArkprocVocabulary.SYSTEM_ACTOR_TYPE,
                ArkprocVocabulary.LEGAL_ACTOR_TYPE,
                ArkprocVocabulary.GROUP_ACTOR_TYPE,
                ArkprocVocabulary.ROLE_TYPE,
                ArkprocVocabulary.FILLED_BY), declared,
                "arknet-actor.ttl and ArkprocVocabulary must describe the same vocabulary");
    }

    /**
     * {@code arkproc:Actor} is the abstract superclass every concrete actor type subclasses, itself
     * a {@code prov:Agent} - the reference guard {@code ReferenceGuardsCoverEveryOntologyEdgeTest}
     * relies on this exact range to find every property pointing at an actor.
     */
    @Test
    void theOntologyDeclaresActorAsAProvAgentSubclass() {
        assertTrue(ontology.contains(iri(ArkprocVocabulary.ACTOR_TYPE), RDF.TYPE, OWL.CLASS),
                "arkproc:Actor must be declared an owl:Class");
        assertTrue(ontology.contains(iri(ArkprocVocabulary.ACTOR_TYPE), RDFS.SUBCLASSOF,
                iri("http://www.w3.org/ns/prov#Agent")),
                "arkproc:Actor must be declared rdfs:subClassOf prov:Agent");
    }

    /**
     * The four concrete types the actor out-adapter actually types every actor with - exactly one
     * per actor, never the abstract {@link ArkprocVocabulary#ACTOR_TYPE} itself.
     */
    @Test
    void theOntologyDeclaresTheFourConcreteActorTypesAsActorSubclasses() {
        assertTrue(ontology.contains(iri(ArkprocVocabulary.HUMAN_ACTOR_TYPE), RDFS.SUBCLASSOF,
                iri(ArkprocVocabulary.ACTOR_TYPE)), "arkproc:HumanActor must be rdfs:subClassOf arkproc:Actor");
        assertTrue(ontology.contains(iri(ArkprocVocabulary.SYSTEM_ACTOR_TYPE), RDFS.SUBCLASSOF,
                iri(ArkprocVocabulary.ACTOR_TYPE)), "arkproc:SystemActor must be rdfs:subClassOf arkproc:Actor");
        assertTrue(ontology.contains(iri(ArkprocVocabulary.LEGAL_ACTOR_TYPE), RDFS.SUBCLASSOF,
                iri(ArkprocVocabulary.ACTOR_TYPE)), "arkproc:LegalActor must be rdfs:subClassOf arkproc:Actor");
        assertTrue(ontology.contains(iri(ArkprocVocabulary.GROUP_ACTOR_TYPE), RDFS.SUBCLASSOF,
                iri(ArkprocVocabulary.ACTOR_TYPE)), "arkproc:GroupActor must be rdfs:subClassOf arkproc:Actor");
    }

    /**
     * The Java enum {@code ActorType} in the actor core has exactly four constants
     * (HUMAN/SYSTEM/LEGAL/GROUP). This is the invariant that keeps the ontology's closed set of
     * concrete actor types in lockstep with that enum - the same bracket
     * {@code ProjectVocabularyMatchesOntologyTest} puts around its three anchor types.
     */
    @Test
    void theOntologyDeclaresExactlyFourConcreteActorTypes() {
        Set<Resource> concreteTypes = ontology.filter(null, RDFS.SUBCLASSOF, iri(ArkprocVocabulary.ACTOR_TYPE))
                .subjects();

        assertEquals(4, concreteTypes.size(),
                "the ActorType enum in the actor core has exactly four constants "
                        + "(HUMAN/SYSTEM/LEGAL/GROUP) - a fifth or missing ontology subclass would "
                        + "desynchronise that closed set");
    }

    /**
     * {@code arkproc:Role} (ADR-37) is a class of its own, never a subclass of
     * {@link ArkprocVocabulary#ACTOR_TYPE}: an actor is rigid, a role is anti-rigid, and an
     * anti-rigid type may not subclass a rigid one.
     */
    @Test
    void theOntologyDeclaresRoleAsAClassNotAnActorSubclass() {
        assertTrue(ontology.contains(iri(ArkprocVocabulary.ROLE_TYPE), RDF.TYPE, OWL.CLASS),
                "arkproc:Role must be declared an owl:Class");
        assertTrue(ontology.filter(iri(ArkprocVocabulary.ROLE_TYPE), RDFS.SUBCLASSOF, null).isEmpty(),
                "arkproc:Role must not be a subclass of arkproc:Actor");
    }

    /** {@code arkproc:filledBy} is the optional, multivalued occupancy edge from a role to its actor(s). */
    @Test
    void theOntologyDeclaresFilledByAsAnObjectPropertyFromRoleToActor() {
        assertTrue(ontology.contains(iri(ArkprocVocabulary.FILLED_BY), RDF.TYPE, OWL.OBJECTPROPERTY),
                "arkproc:filledBy must be declared an owl:ObjectProperty");
        assertTrue(ontology.contains(iri(ArkprocVocabulary.FILLED_BY), RDFS.DOMAIN,
                iri(ArkprocVocabulary.ROLE_TYPE)),
                "arkproc:filledBy must be scoped to arkproc:Role");
        assertTrue(ontology.contains(iri(ArkprocVocabulary.FILLED_BY), RDFS.RANGE,
                iri(ArkprocVocabulary.ACTOR_TYPE)),
                "arkproc:filledBy must range over arkproc:Actor");
    }
}
