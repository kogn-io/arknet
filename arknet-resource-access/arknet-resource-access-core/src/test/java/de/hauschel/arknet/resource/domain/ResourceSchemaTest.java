// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabRdfs;

/**
 * The guards of the rule data: an owned field names exactly one class for its children, since that
 * class is what puts a new child under its node shape. And the two readings over shapes and
 * ontology: the fields of a type, and the edges pointing at it.
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
    private static final String SHAPES = "https://example.org/shapes#";
    private static final IRI SPECIAL = RDF_FACTORY.createIRI("https://example.org/vocab#Special");
    private static final IRI BASE = RDF_FACTORY.createIRI("https://example.org/vocab#Base");
    private static final IRI SPECIAL_FIELD = RDF_FACTORY.createIRI("https://example.org/vocab#specialField");
    private static final IRI BASE_FIELD = RDF_FACTORY.createIRI("https://example.org/vocab#baseField");
    private static final IRI UNRELATED_FIELD = RDF_FACTORY.createIRI("https://example.org/vocab#unrelated");
    private static final IRI SHAPED_EDGE = RDF_FACTORY.createIRI("https://example.org/vocab#shapedEdge");
    private static final IRI EITHER_EDGE = RDF_FACTORY.createIRI("https://example.org/vocab#eitherEdge");
    private static final IRI RANGED_EDGE = RDF_FACTORY.createIRI("https://example.org/vocab#rangedEdge");
    private static final IRI SUB_CLASS_OF = RDF_FACTORY.createIRI(VocabRdfs.NAMESPACE + "subClassOf");
    private static final IRI SEVERITY = RDF_FACTORY.createIRI(SHACL + "severity");
    private static final IRI WARNING = RDF_FACTORY.createIRI(SHACL + "Warning");

    @Test
    void anOwnedFieldTakesTheOneClassItsShapesName() {
        Graph shapes = owningShape();
        shapes.add(OWNING_SHAPE, SHACL_CLASS, CHILD_CLASS);

        ResourceSchema.OwnedField field = ResourceSchema.of(shapes, RDF_FACTORY.createGraph()).ownedField(CHILD_EDGE).orElseThrow();

        assertEquals(CHILD_CLASS, field.childClass());
        assertEquals("Child", field.childKind());
    }

    @Test
    void anOwnedFieldWithoutChildClassFailsTheSchema() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(owningShape(), RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains("exactly one sh:class"), failure.getMessage());
    }

    @Test
    void anOwnedFieldWhoseShapesNameTwoClassesFailsTheSchema() {
        Graph shapes = owningShape();
        shapes.add(OWNING_SHAPE, SHACL_CLASS, CHILD_CLASS);
        shapes.add(NARROWER_SHAPE, PATH, CHILD_EDGE);
        shapes.add(NARROWER_SHAPE, SHACL_CLASS, OTHER_CLASS);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains(OTHER_CLASS.getIRIString()), failure.getMessage());
    }

    @Test
    void theFieldsOfATypeIncludeThoseOfTheShapesOnItsSuperclassesAtAnySeverity() {
        Graph shapes = RDF_FACTORY.createGraph();
        nodeShape(shapes, "Special", SPECIAL, SPECIAL_FIELD);
        nodeShape(shapes, "Base", BASE, BASE_FIELD);
        shapes.add(RDF_FACTORY.createIRI(SHAPES + "Base"), SEVERITY, WARNING);
        nodeShape(shapes, "Unrelated", OTHER_CLASS, UNRELATED_FIELD);
        Graph ontology = RDF_FACTORY.createGraph();
        ontology.add(SPECIAL, SUB_CLASS_OF, BASE);

        Set<IRI> fields = ResourceSchema.of(shapes, ontology).fields(Set.of(SPECIAL));

        assertEquals(Set.of(SPECIAL_FIELD, BASE_FIELD), fields);
    }

    @Test
    void theEdgesToATypeComeFromShClassShOrAndRdfsRangeOnItOrASuperclass() {
        Graph shapes = RDF_FACTORY.createGraph();
        IRI classShape = RDF_FACTORY.createIRI(SHAPES + "Holder-shaped");
        shapes.add(classShape, PATH, SHAPED_EDGE);
        shapes.add(classShape, SHACL_CLASS, BASE);
        IRI orShape = RDF_FACTORY.createIRI(SHAPES + "Holder-either");
        IRI alternative = RDF_FACTORY.createIRI(SHAPES + "Holder-either-special");
        IRI otherAlternative = RDF_FACTORY.createIRI(SHAPES + "Holder-either-other");
        IRI firstCell = RDF_FACTORY.createIRI(SHAPES + "cell-1");
        IRI secondCell = RDF_FACTORY.createIRI(SHAPES + "cell-2");
        shapes.add(orShape, PATH, EITHER_EDGE);
        shapes.add(orShape, RDF_FACTORY.createIRI(SHACL + "or"), firstCell);
        shapes.add(firstCell, VocabRdf.FIRST, otherAlternative);
        shapes.add(firstCell, VocabRdf.REST, secondCell);
        shapes.add(secondCell, VocabRdf.FIRST, alternative);
        shapes.add(secondCell, VocabRdf.REST, VocabRdf.NIL);
        shapes.add(otherAlternative, SHACL_CLASS, OTHER_CLASS);
        shapes.add(alternative, SHACL_CLASS, SPECIAL);
        IRI unrelatedShape = RDF_FACTORY.createIRI(SHAPES + "Holder-other");
        shapes.add(unrelatedShape, PATH, UNRELATED_FIELD);
        shapes.add(unrelatedShape, SHACL_CLASS, OTHER_CLASS);
        Graph ontology = RDF_FACTORY.createGraph();
        ontology.add(SPECIAL, SUB_CLASS_OF, BASE);
        ontology.add(RANGED_EDGE, RDF_FACTORY.createIRI(VocabRdfs.NAMESPACE + "range"), BASE);

        Set<IRI> edges = ResourceSchema.of(shapes, ontology).edgesTo(Set.of(SPECIAL));

        assertEquals(Set.of(SHAPED_EDGE, EITHER_EDGE, RANGED_EDGE), edges);
    }

    private static void nodeShape(final Graph shapes, final String name, final IRI target, final IRI field) {
        IRI node = RDF_FACTORY.createIRI(SHAPES + name);
        IRI property = RDF_FACTORY.createIRI(SHAPES + name + "-field");
        shapes.add(node, RDF_FACTORY.createIRI(SHACL + "targetClass"), target);
        shapes.add(node, RDF_FACTORY.createIRI(SHACL + "property"), property);
        shapes.add(property, PATH, field);
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
