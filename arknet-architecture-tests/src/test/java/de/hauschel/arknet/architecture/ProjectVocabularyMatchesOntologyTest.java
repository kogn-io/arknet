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

import de.hauschel.arknet.persistence.ArkprjVocabulary;

/**
 * Nails down that arknet's project vocabulary has one meaning, not two.
 *
 * <p>The same terms are written down twice, in two modules that do not depend on each other:
 * {@link ArkprjVocabulary} in {@code arknet-persistence-support} is what the project out-adapter
 * actually writes into the store, {@code arknet-project.ttl} in {@code arknet-ontology} is what
 * arknet ships as the documented meaning of those statements. Nothing connects them.</p>
 *
 * <p><strong>Why this cannot be caught elsewhere.</strong> Every other test of project
 * registration phrases its assertions with the very constants the adapter writes, so an IRI
 * typo is symmetric and invisible: rename {@code arkprj:anchorValue} to {@code arkprj:anchorVal}
 * in the Java class and the whole suite stays green while the store fills with a predicate no
 * ontology defines and every SPARQL query written against the shipped ontology returns nothing.
 * The ttl is not loaded at runtime by anything (unlike the shapes and the requirements axioms,
 * which the gate factories parse), so it cannot notice the drift either. This module is where
 * such a seam belongs: an invariant across a module cut, held by reviewer attention alone
 * otherwise.</p>
 *
 * <p>Deliberately bidirectional. A constant without an ontology term ships a statement arknet
 * never explains; an ontology term without a constant means the documented vocabulary has a
 * second, unimplemented half. Both are drift, and neither should be discovered by a user.</p>
 */
class ProjectVocabularyMatchesOntologyTest {

    private static final String ONTOLOGY_RESOURCE = "/arknet-project.ttl";

    /** {@code ArkprjVocabulary.NAMESPACE} is public, so this is read, never re-typed. */
    private static final String ARKPRJ_NAMESPACE = ArkprjVocabulary.NAMESPACE;

    private final Model ontology = parse(ONTOLOGY_RESOURCE, ProjectVocabularyMatchesOntologyTest.class);

    /**
     * The set of {@code arkprj:} terms is the same on both sides - this is the assertion that
     * turns red on any rename, on either side, in either direction.
     *
     * <p>{@link ArkprjVocabulary#REGISTRY_GRAPH} and {@link ArkprjVocabulary#IDENTITY_GRAPH} are
     * deliberately absent from the expected set: unlike every other constant here they do not
     * name a term in the {@code arkprj:} namespace at all but a named graph IRI in
     * {@code https://w3id.org/arknet/model/...} - the same role
     * {@link de.hauschel.arknet.persistence.ArkprovVocabulary#PROVENANCE_GRAPH} plays for the
     * provenance vocabulary. {@link ArkprjVocabulary#NAMESPACE} itself is the prefix, not a term.
     *
     * <p>{@code arkprj:AnchorType} is excluded from the ontology side for the opposite reason:
     * it is a genuine {@code arkprj:} term, but the adapter never writes this class IRI as a
     * triple component - only the three individuals below it, which are constants here. It
     * appears solely as the static range of {@code arkprj:anchorType} and the static type of its
     * three individuals, both asserted directly by
     * {@link #theOntologyDeclaresTheAnchorTypeEdgeAsWrittenByTheAdapter()} and
     * {@link #theOntologyDeclaresExactlyThreeAnchorTypeIndividuals()} instead of through this
     * coverage check.
     */
    @Test
    void theOntologyDeclaresExactlyTheArkprjTermsTheAdapterWrites() {
        final Set<String> declared = ontology.subjects().stream()
                .filter(IRI.class::isInstance)
                .map(Resource::stringValue)
                .filter(iri -> iri.startsWith(ARKPRJ_NAMESPACE))
                .filter(iri -> !iri.equals(ANCHOR_TYPE_CLASS))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                ArkprjVocabulary.PROJECT_TYPE,
                ArkprjVocabulary.ANCHOR_CLASS,
                ArkprjVocabulary.ANCHOR,
                ArkprjVocabulary.ANCHOR_VALUE,
                ArkprjVocabulary.ANCHOR_TYPE,
                ArkprjVocabulary.PATH_ANCHOR,
                ArkprjVocabulary.URL_ANCHOR,
                ArkprjVocabulary.UUID_ANCHOR,
                ArkprjVocabulary.DEFAULT_LANGUAGE), declared,
                "arknet-project.ttl and ArkprjVocabulary must describe the same vocabulary");
    }

    /** The adapter writes a project and its anchors as instances of these two classes. */
    @Test
    void theOntologyDeclaresProjectAndAnchorAsClasses() {
        assertTrue(ontology.contains(iri(ArkprjVocabulary.PROJECT_TYPE), RDF.TYPE, OWL.CLASS),
                "arkprj:Project must be declared an owl:Class");
        assertTrue(ontology.contains(iri(ArkprjVocabulary.ANCHOR_CLASS), RDF.TYPE, OWL.CLASS),
                "arkprj:Anchor must be declared an owl:Class");
    }

    /** The adapter writes exactly one edge from a project to each of its anchor nodes. */
    @Test
    void theOntologyDeclaresTheAnchorEdgeAsWrittenByTheAdapter() {
        assertTrue(ontology.contains(iri(ArkprjVocabulary.ANCHOR), RDF.TYPE, OWL.OBJECTPROPERTY),
                "arkprj:anchor must be declared an owl:ObjectProperty");
        assertTrue(ontology.contains(iri(ArkprjVocabulary.ANCHOR), RDFS.RANGE,
                iri(ArkprjVocabulary.ANCHOR_CLASS)),
                "the adapter only ever points arkprj:anchor at an anchor node - the range must say so");
    }

    /**
     * The adapter writes every anchor's type as one of the three closed-set individuals below,
     * never as a bare literal - {@code arkprj:anchorType} must be an object property whose range
     * is the class those individuals belong to.
     */
    @Test
    void theOntologyDeclaresTheAnchorTypeEdgeAsWrittenByTheAdapter() {
        assertTrue(ontology.contains(iri(ArkprjVocabulary.ANCHOR_TYPE), RDF.TYPE, OWL.OBJECTPROPERTY),
                "arkprj:anchorType must be declared an owl:ObjectProperty");
        assertTrue(ontology.contains(iri(ArkprjVocabulary.ANCHOR_TYPE), RDFS.RANGE, iri(ANCHOR_TYPE_CLASS)),
                "the adapter only ever points arkprj:anchorType at an AnchorType individual - "
                        + "the range must say so");
    }

    /** The anchor's opaque string is a plain datatype value, never a resource. */
    @Test
    void theOntologyDeclaresAnchorValueAsADatatypeProperty() {
        assertTrue(ontology.contains(iri(ArkprjVocabulary.ANCHOR_VALUE), RDF.TYPE, OWL.DATATYPEPROPERTY),
                "arkprj:anchorValue must be declared an owl:DatatypeProperty - "
                        + "the server never interprets it as a resource reference");
    }

    /**
     * A project's optional default display/write language (issue #228) is a plain BCP-47 tag, not
     * a resource - the same reasoning {@link #theOntologyDeclaresAnchorValueAsADatatypeProperty}
     * already applies to {@code arkprj:anchorValue}.
     */
    @Test
    void theOntologyDeclaresDefaultLanguageAsADatatypeProperty() {
        assertTrue(ontology.contains(iri(ArkprjVocabulary.DEFAULT_LANGUAGE), RDF.TYPE, OWL.DATATYPEPROPERTY),
                "arkprj:defaultLanguage must be declared an owl:DatatypeProperty");
        assertTrue(ontology.contains(iri(ArkprjVocabulary.DEFAULT_LANGUAGE), RDFS.DOMAIN,
                iri(ArkprjVocabulary.PROJECT_TYPE)),
                "arkprj:defaultLanguage must be scoped to arkprj:Project");
    }

    /**
     * The Java enum {@code AnchorType} in the project core has exactly three constants
     * (PATH/URL/UUID). This is the invariant that keeps the ontology's closed set of anchor-type
     * individuals in lockstep with that enum: a fourth individual here would be an anchor kind
     * the adapter has no enum constant to serialise, and a missing one would mean the adapter can
     * write an anchor type the ontology never declared.
     */
    @Test
    void theOntologyDeclaresExactlyThreeAnchorTypeIndividuals() {
        assertTrue(ontology.contains(iri(ArkprjVocabulary.PATH_ANCHOR), RDF.TYPE, iri(ANCHOR_TYPE_CLASS)),
                "arkprj:PathAnchor must be typed arkprj:AnchorType");
        assertTrue(ontology.contains(iri(ArkprjVocabulary.URL_ANCHOR), RDF.TYPE, iri(ANCHOR_TYPE_CLASS)),
                "arkprj:UrlAnchor must be typed arkprj:AnchorType");
        assertTrue(ontology.contains(iri(ArkprjVocabulary.UUID_ANCHOR), RDF.TYPE, iri(ANCHOR_TYPE_CLASS)),
                "arkprj:UuidAnchor must be typed arkprj:AnchorType");

        final Set<Resource> individuals = ontology.filter(null, RDF.TYPE, iri(ANCHOR_TYPE_CLASS)).subjects();

        assertEquals(3, individuals.size(),
                "the AnchorType enum in the project core has exactly three constants "
                        + "(PATH/URL/UUID) - a fourth or missing ontology individual would "
                        + "desynchronise that closed set");
    }

    /**
     * {@code arkprj:AnchorType} itself, the class the three individuals above belong to. Not an
     * {@link ArkprjVocabulary} constant: the adapter never writes this IRI as a triple component -
     * the three individual constants above are what it actually serialises - so, unlike every
     * other term in this file, it is read directly off the ontology instead of compared against
     * one.
     */
    private static final String ANCHOR_TYPE_CLASS = ARKPRJ_NAMESPACE + "AnchorType";
}
