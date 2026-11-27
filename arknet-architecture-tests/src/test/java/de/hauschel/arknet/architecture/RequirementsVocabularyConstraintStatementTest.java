// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.iri;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.persistence.ArkreqVocabulary;

/**
 * Nails {@link ArkreqVocabulary#CONSTRAINT_STATEMENT} to the property the requirements ontology
 * declares and the constraint shapes constrain. {@link ArkreqVocabulary} has no bidirectional
 * check against {@code arknet-requirements.ttl} as the other {@code *VocabularyMatchesOntologyTest}
 * classes give their vocabularies; this one covers only the constant the comparison tests of
 * kogn-io/arknet#744 added.
 */
class RequirementsVocabularyConstraintStatementTest {

    private static final String SHACL_PATH = "http://www.w3.org/ns/shacl#path";

    private final Model ontology =
            parse("/arknet-requirements.ttl", RequirementsVocabularyConstraintStatementTest.class);
    private final Model shapes =
            parse("/requirements-shapes.ttl", RequirementsVocabularyConstraintStatementTest.class);

    @Test
    void theOntologyDeclaresTheStatementAsADatatypePropertyOfAConstraint() {
        assertTrue(ontology.contains(iri(ArkreqVocabulary.CONSTRAINT_STATEMENT), RDF.TYPE, OWL.DATATYPEPROPERTY),
                "arkreq:constraintStatement must be declared an owl:DatatypeProperty");
        assertTrue(ontology.contains(iri(ArkreqVocabulary.CONSTRAINT_STATEMENT), RDFS.DOMAIN,
                iri(ArkreqVocabulary.CONSTRAINT_TYPE)),
                "arkreq:constraintStatement must have the domain arkreq:Constraint");
    }

    @Test
    void theConstraintShapesConstrainTheStatement() {
        assertTrue(shapes.contains(null, iri(SHACL_PATH), iri(ArkreqVocabulary.CONSTRAINT_STATEMENT)),
                "requirements-shapes.ttl must name arkreq:constraintStatement as an sh:path");
    }
}
