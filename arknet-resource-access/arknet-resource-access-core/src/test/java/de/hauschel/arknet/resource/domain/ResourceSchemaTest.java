// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * The guards of the rule data: an owned field names exactly one class for its children, since that
 * class is what puts a new child under its node shape.
 */
class ResourceSchemaTest {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final IRI PATH = RDF_FACTORY.createIRI(SHACL + "path");
    private static final IRI SHACL_CLASS = RDF_FACTORY.createIRI(SHACL + "class");
    private static final IRI OWNING_SHAPE = RDF_FACTORY.createIRI("https://example.org/shapes#Parent-child");
    private static final IRI NARROWER_SHAPE = RDF_FACTORY.createIRI("https://example.org/shapes#Parent-child-narrow");
    private static final IRI CHILD_EDGE = RDF_FACTORY.createIRI("https://example.org/vocab#child");
    private static final IRI CHILD_CLASS = RDF_FACTORY.createIRI("https://example.org/vocab#Child");
    private static final IRI OTHER_CLASS = RDF_FACTORY.createIRI("https://example.org/vocab#Other");

    @Test
    void anOwnedFieldTakesTheOneClassItsShapesName() {
        Graph shapes = owningShape();
        shapes.add(OWNING_SHAPE, SHACL_CLASS, CHILD_CLASS);

        ResourceSchema.OwnedField field = ResourceSchema.of(shapes).ownedField(CHILD_EDGE).orElseThrow();

        assertEquals(CHILD_CLASS, field.childClass());
        assertEquals("Child", field.childKind());
    }

    @Test
    void anOwnedFieldWithoutChildClassFailsTheSchema() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(owningShape()));

        assertTrue(failure.getMessage().contains("exactly one sh:class"), failure.getMessage());
    }

    @Test
    void anOwnedFieldWhoseShapesNameTwoClassesFailsTheSchema() {
        Graph shapes = owningShape();
        shapes.add(OWNING_SHAPE, SHACL_CLASS, CHILD_CLASS);
        shapes.add(NARROWER_SHAPE, PATH, CHILD_EDGE);
        shapes.add(NARROWER_SHAPE, SHACL_CLASS, OTHER_CLASS);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ResourceSchema.of(shapes));

        assertTrue(failure.getMessage().contains(OTHER_CLASS.getIRIString()), failure.getMessage());
    }

    private static Graph owningShape() {
        Graph shapes = RDF_FACTORY.createGraph();
        shapes.add(OWNING_SHAPE, PATH, CHILD_EDGE);
        shapes.add(OWNING_SHAPE, RDF_FACTORY.createIRI(ArkresVocabulary.CHILD_KIND), RDF_FACTORY.createLiteral("Child"));
        shapes.add(OWNING_SHAPE, RDF_FACTORY.createIRI(ArkresVocabulary.ORDERED_BY),
                RDF_FACTORY.createIRI("https://example.org/vocab#position"));
        return shapes;
    }
}
