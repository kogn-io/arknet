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

import de.hauschel.arknet.resource.domain.ArkresVocabulary;

/**
 * Nails down that the vocabulary of generic resource access has one meaning, not two.
 *
 * <p>The terms are written down twice, in two modules that do not depend on each other:
 * {@link ArkresVocabulary} in {@code arknet-resource-access-core} is what the component names in
 * its code, {@code arknet-resource.ttl} in {@code arknet-ontology} is what arknet ships as their
 * documented meaning. Same construction as {@code ProjectVocabularyMatchesOntologyTest}, and
 * deliberately bidirectional for the same reason: a constant without an ontology term names
 * something arknet never explains, an ontology term without a constant is a documented half
 * nothing implements.</p>
 */
class ResourceVocabularyMatchesOntologyTest {

    private static final String ONTOLOGY_RESOURCE = "/arknet-resource.ttl";

    private final Model ontology = parse(ONTOLOGY_RESOURCE, ResourceVocabularyMatchesOntologyTest.class);

    /** The set of {@code arkres:} terms is the same on both sides. */
    @Test
    void theOntologyDeclaresExactlyTheArkresTermsTheComponentNames() {
        final Set<String> declared = ontology.subjects().stream()
                .filter(IRI.class::isInstance)
                .map(Resource::stringValue)
                .filter(iri -> iri.startsWith(ArkresVocabulary.NAMESPACE))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkresVocabulary.ACTIVITY,
                ArkresVocabulary.CREATE,
                ArkresVocabulary.UPDATE,
                ArkresVocabulary.ADD_TO,
                ArkresVocabulary.REMOVE_FROM,
                ArkresVocabulary.DELETE,
                ArkresVocabulary.OBJECT,
                ArkresVocabulary.SET,
                ArkresVocabulary.CLEAR,
                ArkresVocabulary.FIELD,
                ArkresVocabulary.VALUE,
                ArkresVocabulary.POSITION,
                ArkresVocabulary.RESOURCE_TYPE,
                ArkresVocabulary.CODE_PREFIX,
                ArkresVocabulary.CHILD_KIND,
                ArkresVocabulary.ORDERED_BY,
                ArkresVocabulary.INITIAL_STATUS,
                ArkresVocabulary.TRANSITION_CLASS,
                ArkresVocabulary.TRANSITION,
                ArkresVocabulary.FROM_STATUS,
                ArkresVocabulary.TO_STATUS,
                ArkresVocabulary.CONDITION,
                ArkresVocabulary.DELETABLE_IN,
                ArkresVocabulary.FIELD_GROUP_CLASS,
                ArkresVocabulary.FIELD_GROUP,
                ArkresVocabulary.GROUP_FIELD,
                ArkresVocabulary.EDITABLE_IN,
                ArkresVocabulary.IMMUTABLE,
                ArkresVocabulary.DEFAULT_CLASS,
                ArkresVocabulary.DEFAULT,
                ArkresVocabulary.DEFAULT_FIELD,
                ArkresVocabulary.DEFAULT_VALUE,
                ArkresVocabulary.TODAY), declared,
                "arknet-resource.ttl and ArkresVocabulary must describe the same vocabulary");
    }

    /** Every activity is a class, and every one but the root is an activity. */
    @Test
    void theOntologyDeclaresTheActivitiesAsSubclassesOfActivity() {
        assertTrue(ontology.contains(iri(ArkresVocabulary.ACTIVITY), RDF.TYPE, OWL.CLASS),
                "arkres:Activity must be declared an owl:Class");
        for (final String activity : Set.of(ArkresVocabulary.CREATE, ArkresVocabulary.UPDATE,
                ArkresVocabulary.ADD_TO, ArkresVocabulary.REMOVE_FROM, ArkresVocabulary.DELETE)) {
            assertTrue(ontology.contains(iri(activity), RDF.TYPE, OWL.CLASS),
                    activity + " must be declared an owl:Class");
            assertTrue(ontology.contains(iri(activity), RDFS.SUBCLASSOF, iri(ArkresVocabulary.ACTIVITY)),
                    activity + " must be a subclass of arkres:Activity");
        }
    }

    /** The namespace the ontology announces for itself is the one the constants are built on. */
    @Test
    void theOntologyAnnouncesTheNamespaceTheConstantsUse() {
        assertEquals(ArkresVocabulary.NAMESPACE, ontology.getNamespace("arkres").orElseThrow().getName(),
                "the arkres prefix of arknet-resource.ttl must bind ArkresVocabulary.NAMESPACE");
    }
}
