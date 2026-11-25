// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdfs;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * The label field rule datum: read off the shapes, at most one path per class, and every
 * contradiction in it failing the schema build. Fixture shapes, no namespace of a bounded context.
 */
class ResourceSchemaLabelFieldTest {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final String VOCAB = "https://example.org/vocab#";
    private static final String SHAPES = "https://example.org/shapes#";

    private static final IRI BOOK = vocab("Book");
    private static final IRI NOVEL = vocab("Novel");
    private static final IRI LINK = vocab("Link");
    private static final IRI TITLE = vocab("title");
    private static final IRI NAME = vocab("name");
    private static final IRI CHAPTERS = vocab("chapter");
    private static final IRI TITLE_SHAPE = shape("Book-title");
    private static final IRI TITLE_AGAIN_SHAPE = shape("Book-title-notBlank");
    private static final IRI NAME_SHAPE = shape("Book-name");
    private static final IRI CHAPTERS_SHAPE = shape("Book-chapter");

    @Test
    void theLabelFieldIsReadOffTheShapesAndInheritedBySubclasses() {
        ResourceSchema schema = ResourceSchema.of(shapes(), ontology());

        assertEquals(Optional.of(TITLE), schema.labelField(Set.of(BOOK)));
        assertEquals(Optional.of(TITLE), schema.labelField(Set.of(NOVEL)));
    }

    @Test
    void aTypeWithoutATextFieldHasNoLabelField() {
        assertEquals(Optional.empty(), ResourceSchema.of(shapes(), ontology()).labelField(Set.of(LINK)));
    }

    @Test
    void theSamePathMarkedOnTwoShapesOfOneClassIsOnePath() {
        Graph shapes = shapes();
        shapes.add(TITLE_AGAIN_SHAPE, arkres(ArkresVocabulary.LABEL_FIELD), bool());

        assertEquals(Optional.of(TITLE), ResourceSchema.of(shapes, ontology()).labelField(Set.of(BOOK)));
    }

    @Test
    void twoDifferentPathsOnOneClassFailTheSchema() {
        Graph shapes = shapes();
        shapes.add(NAME_SHAPE, arkres(ArkresVocabulary.LABEL_FIELD), bool());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, ontology()));

        assertTrue(failure.getMessage().contains("two label fields"), failure.getMessage());
        assertTrue(failure.getMessage().contains(TITLE.getIRIString()), failure.getMessage());
        assertTrue(failure.getMessage().contains(NAME.getIRIString()), failure.getMessage());
    }

    @Test
    void twoDifferentPathsAcrossASuperclassFailTheSchema() {
        Graph shapes = shapes();
        IRI node = shape("Novel");
        IRI own = shape("Novel-name");
        shapes.add(node, shacl("targetClass"), NOVEL);
        shapes.add(node, shacl("property"), own);
        shapes.add(own, shacl("path"), NAME);
        shapes.add(own, arkres(ArkresVocabulary.LABEL_FIELD), bool());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, ontology()));

        assertTrue(failure.getMessage().contains(NOVEL.getIRIString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("two label fields"), failure.getMessage());
    }

    @Test
    void aMarkerOtherThanTrueFailsTheSchema() {
        Graph shapes = shapes();
        shapes.stream(TITLE_SHAPE, arkres(ArkresVocabulary.LABEL_FIELD), null).toList().forEach(shapes::remove);
        shapes.add(TITLE_SHAPE, arkres(ArkresVocabulary.LABEL_FIELD), RDF_FACTORY.createLiteral("yes"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, ontology()));

        assertTrue(failure.getMessage().contains("boolean true"), failure.getMessage());
    }

    @Test
    void aMarkerWithoutAPathFailsTheSchema() {
        Graph shapes = shapes();
        IRI pathless = shape("Book-pathless");
        shapes.add(shape("Book"), shacl("property"), pathless);
        shapes.add(pathless, arkres(ArkresVocabulary.LABEL_FIELD), bool());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, ontology()));

        assertTrue(failure.getMessage().contains("no sh:path"), failure.getMessage());
    }

    @Test
    void aLabelOnAnOwnedFieldFailsTheSchema() {
        Graph shapes = shapes();
        shapes.add(CHAPTERS_SHAPE, arkres(ArkresVocabulary.LABEL_FIELD), bool());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, ontology()));

        assertTrue(failure.getMessage().contains("owns its children"), failure.getMessage());
    }

    @Test
    void aNodeTypedWithTwoClassesReachingTwoPathsIsRejectedWhenAsked() {
        Graph shapes = shapes();
        IRI other = vocab("Other");
        IRI node = shape("Other");
        IRI own = shape("Other-name");
        shapes.add(node, shacl("targetClass"), other);
        shapes.add(node, shacl("property"), own);
        shapes.add(own, shacl("path"), NAME);
        shapes.add(own, arkres(ArkresVocabulary.LABEL_FIELD), bool());
        ResourceSchema schema = ResourceSchema.of(shapes, ontology());

        assertThrows(IllegalArgumentException.class, () -> schema.labelField(Set.of(BOOK, other)));
    }

    /** Book with a label title (marked twice), a name, owned chapters; Link has no text field. */
    private static Graph shapes() {
        Graph shapes = RDF_FACTORY.createGraph();
        IRI node = shape("Book");
        shapes.add(node, shacl("targetClass"), BOOK);
        for (IRI property : new IRI[] {TITLE_SHAPE, TITLE_AGAIN_SHAPE, NAME_SHAPE, CHAPTERS_SHAPE}) {
            shapes.add(node, shacl("property"), property);
        }
        shapes.add(TITLE_SHAPE, shacl("path"), TITLE);
        shapes.add(TITLE_SHAPE, arkres(ArkresVocabulary.LABEL_FIELD), bool());
        shapes.add(TITLE_AGAIN_SHAPE, shacl("path"), TITLE);
        shapes.add(NAME_SHAPE, shacl("path"), NAME);
        shapes.add(CHAPTERS_SHAPE, shacl("path"), CHAPTERS);
        shapes.add(CHAPTERS_SHAPE, shacl("class"), vocab("Chapter"));
        shapes.add(CHAPTERS_SHAPE, arkres(ArkresVocabulary.CHILD_KIND), RDF_FACTORY.createLiteral("Chapter"));
        shapes.add(CHAPTERS_SHAPE, arkres(ArkresVocabulary.ORDERED_BY), vocab("position"));
        IRI linkNode = shape("Link");
        IRI target = shape("Link-target");
        shapes.add(linkNode, shacl("targetClass"), LINK);
        shapes.add(linkNode, shacl("property"), target);
        shapes.add(target, shacl("path"), vocab("target"));
        return shapes;
    }

    private static Graph ontology() {
        Graph ontology = RDF_FACTORY.createGraph();
        ontology.add(NOVEL, RDF_FACTORY.createIRI(VocabRdfs.NAMESPACE + "subClassOf"), BOOK);
        return ontology;
    }

    private static RDFTerm bool() {
        return RDF_FACTORY.createLiteral("true", VocabXsd.BOOLEAN);
    }

    private static IRI vocab(final String localName) {
        return RDF_FACTORY.createIRI(VOCAB + localName);
    }

    private static IRI shape(final String localName) {
        return RDF_FACTORY.createIRI(SHAPES + localName);
    }

    private static IRI arkres(final String iri) {
        return RDF_FACTORY.createIRI(iri);
    }

    private static IRI shacl(final String localName) {
        return RDF_FACTORY.createIRI(SHACL + localName);
    }
}
