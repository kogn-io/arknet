// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.persistence.ArkprovVocabulary;

/**
 * Nails down that arknet's revision vocabulary has one meaning, not two.
 *
 * <p>The same terms are written down twice, in two modules that do not depend on each other:
 * {@link ArkprovVocabulary} in {@code arknet-persistence-support} is what the write funnel
 * actually writes into the store, {@code arknet-provenance.ttl} in {@code arknet-ontology} is
 * what arknet ships as the documented meaning of those statements. Nothing connects them.</p>
 *
 * <p><strong>Why this cannot be caught elsewhere.</strong> Every other test of the revision
 * trail - the funnel's unit tests and all four adapter tests - phrases its assertions with the
 * very constants the funnel writes, so an IRI typo is symmetric and invisible: rename
 * {@code arkprov:head} to {@code arkprov:Head} in the Java class and the whole suite stays
 * green while the store fills with a predicate no ontology defines and every SPARQL query
 * written against the shipped ontology returns nothing. The ttl is not loaded at runtime by
 * anything (unlike the shapes and the requirements axioms, which the gate factories parse), so
 * it cannot notice the drift either. This module is where such a seam belongs: an invariant
 * across a module cut, held by reviewer attention alone otherwise (#60).</p>
 *
 * <p>Deliberately bidirectional. A constant without an ontology term ships a statement arknet
 * never explains; an ontology term without a constant means the documented vocabulary has a
 * second, unimplemented half. Both are drift, and neither should be discovered by a user.</p>
 */
class ProvenanceVocabularyMatchesOntologyTest {

    private static final String ONTOLOGY_RESOURCE = "/arknet-provenance.ttl";

    /** Derived, never re-typed: a typo here would defeat the point of the comparison. */
    private static final String ARKPROV_NAMESPACE =
            ArkprovVocabulary.HEAD.substring(0, ArkprovVocabulary.HEAD.indexOf('#') + 1);

    private final Model ontology = parseOntology();

    /**
     * The set of {@code arkprov:} terms is the same on both sides - this is the assertion that
     * turns red on any rename, on either side, in either direction.
     */
    @Test
    void theOntologyDeclaresExactlyTheArkprovTermsTheFunnelWrites() {
        Set<String> declared = ontology.subjects().stream()
                .filter(IRI.class::isInstance)
                .map(Resource::stringValue)
                .filter(iri -> iri.startsWith(ARKPROV_NAMESPACE))
                .collect(Collectors.toSet());

        assertEquals(Set.of(ArkprovVocabulary.REVISION_TYPE, ArkprovVocabulary.HEAD), declared,
                "arknet-provenance.ttl and ArkprovVocabulary must describe the same vocabulary");
    }

    /**
     * The funnel types every revision as {@code prov:Entity} <em>and</em> {@code arkprov:Revision}
     * precisely so no reasoner is needed to see it is a PROV entity. That dual typing is only
     * justified if the ontology says the two are related that way.
     */
    @Test
    void theOntologyBacksTheDualTypingTheFunnelWrites() {
        assertTrue(ontology.contains(iri(ArkprovVocabulary.REVISION_TYPE), RDF.TYPE, OWL.CLASS),
                "arkprov:Revision must be declared an owl:Class");
        assertTrue(ontology.contains(iri(ArkprovVocabulary.REVISION_TYPE), RDFS.SUBCLASSOF,
                iri(ArkprovVocabulary.ENTITY_TYPE)),
                "the funnel writes both types on every revision - the ontology must relate them");
    }

    /** The head pointer is an object property, and it points at revisions. */
    @Test
    void theOntologyDeclaresTheHeadPointerAsWrittenByTheFunnel() {
        assertTrue(ontology.contains(iri(ArkprovVocabulary.HEAD), RDF.TYPE, OWL.OBJECTPROPERTY),
                "arkprov:head must be declared an owl:ObjectProperty");
        assertTrue(ontology.contains(iri(ArkprovVocabulary.HEAD), RDFS.RANGE,
                iri(ArkprovVocabulary.REVISION_TYPE)),
                "the funnel only ever points a head at a revision - the range must say so");
    }

    private static IRI iri(String value) {
        return Values.iri(value);
    }

    private static Model parseOntology() {
        try (InputStream in = ProvenanceVocabularyMatchesOntologyTest.class
                .getResourceAsStream(ONTOLOGY_RESOURCE)) {
            Objects.requireNonNull(in, "missing classpath resource " + ONTOLOGY_RESOURCE);
            return Rio.parse(in, "", RDFFormat.TURTLE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + ONTOLOGY_RESOURCE, e);
        }
    }
}
