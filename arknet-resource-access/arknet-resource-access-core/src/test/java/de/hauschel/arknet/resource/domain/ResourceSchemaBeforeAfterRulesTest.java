// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * The before/after rule data on the shapes: field groups of a status field, immutable fields and
 * the defaults of a transition, read off the shapes - and every contradiction in them failing the
 * schema build rather than a write. Fixture shapes, no namespace of a bounded context.
 */
class ResourceSchemaBeforeAfterRulesTest {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final String VOCAB = "https://example.org/vocab#";
    private static final String SHAPES = "https://example.org/shapes#";

    private static final IRI PAPER = vocab("Paper");
    private static final IRI SECTION = vocab("Section");
    private static final IRI STATE = vocab("state");
    private static final IRI TITLE = vocab("title");
    private static final IRI SUMMARY = vocab("summary");
    private static final IRI SECTIONS = vocab("section");
    private static final IRI POSITION = vocab("position");
    private static final IRI CATEGORY = vocab("category");
    private static final IRI PUBLISHED_ON = vocab("publishedOn");
    private static final IRI UNKNOWN = vocab("unknown");
    private static final IRI DRAFT = vocab("Draft");
    private static final IRI PUBLISHED = vocab("Published");
    private static final IRI WITHDRAWN = vocab("Withdrawn");
    private static final IRI STATE_SHAPE = RDF_FACTORY.createIRI(SHAPES + "Paper-state");
    private static final IRI CATEGORY_SHAPE = RDF_FACTORY.createIRI(SHAPES + "Paper-category");
    private static final IRI SECTIONS_SHAPE = RDF_FACTORY.createIRI(SHAPES + "Paper-section");
    private static final IRI TODAY = RDF_FACTORY.createIRI(ArkresVocabulary.TODAY);

    private BlankNode group;
    private BlankNode publish;

    @Test
    void theFieldGroupsImmutableFieldsAndDefaultsAreReadOffTheShapes() {
        ResourceSchema schema = ResourceSchema.of(shapes(), RDF_FACTORY.createGraph());

        ResourceSchema.StatusField status = schema.statusField(Set.of(PAPER)).orElseThrow();
        assertEquals(List.of(new ResourceSchema.FieldGroup(Set.of(TITLE, SUMMARY, SECTIONS), Set.of(DRAFT))),
                status.groups());
        assertTrue(status.groups().get(0).editableIn(DRAFT));
        assertFalse(status.groups().get(0).editableIn(PUBLISHED));
        assertEquals(Set.of(CATEGORY), schema.immutableFields(Set.of(PAPER)));
        ResourceSchema.Transition transition = status.transition(DRAFT, PUBLISHED).orElseThrow();
        assertEquals(List.of(new ResourceSchema.DefaultValue(PUBLISHED_ON, TODAY)), transition.defaults());
        assertTrue(transition.defaults().get(0).today());
        assertTrue(status.transition(WITHDRAWN, PUBLISHED).orElseThrow().defaults().isEmpty());
    }

    @Test
    void aFieldGroupOnAShapeThatMarksNoStatusFieldFailsTheSchema() {
        Graph shapes = shapes();
        shapes.stream(STATE_SHAPE, arkres(ArkresVocabulary.INITIAL_STATUS), null).toList().forEach(shapes::remove);
        shapes.stream(STATE_SHAPE, arkres(ArkresVocabulary.TRANSITION), null).toList().forEach(shapes::remove);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains(ArkresVocabulary.FIELD_GROUP), failure.getMessage());
    }

    @Test
    void aFieldGroupWithoutEditableStatusFailsTheSchema() {
        Graph shapes = shapes();
        shapes.stream(group, arkres(ArkresVocabulary.EDITABLE_IN), null).toList().forEach(shapes::remove);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains(ArkresVocabulary.EDITABLE_IN), failure.getMessage());
    }

    @Test
    void aFieldGroupNamingNoFieldOfTheTypeFailsTheSchema() {
        Graph shapes = shapes();
        shapes.add(group, arkres(ArkresVocabulary.GROUP_FIELD), UNKNOWN);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains(UNKNOWN.getIRIString()), failure.getMessage());
    }

    @Test
    void aFieldInTwoGroupsFailsTheSchema() {
        Graph shapes = shapes();
        BlankNode second = RDF_FACTORY.createBlankNode();
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.FIELD_GROUP), second);
        shapes.add(second, arkres(ArkresVocabulary.GROUP_FIELD), TITLE);
        shapes.add(second, arkres(ArkresVocabulary.EDITABLE_IN), PUBLISHED);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains("two field groups"), failure.getMessage());
    }

    @Test
    void theStatusFieldInAGroupFailsTheSchema() {
        Graph shapes = shapes();
        shapes.add(group, arkres(ArkresVocabulary.GROUP_FIELD), STATE);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains("the status field itself"), failure.getMessage());
    }

    @Test
    void anEditableStatusOutsideTheStatusFieldsShInFailsTheSchema() {
        Graph shapes = shapes();
        shapes.add(group, arkres(ArkresVocabulary.EDITABLE_IN), vocab("Lost"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains(VOCAB + "Lost"), failure.getMessage());
    }

    @Test
    void anImmutableMarkerOtherThanTrueFailsTheSchema() {
        Graph shapes = shapes();
        shapes.stream(CATEGORY_SHAPE, arkres(ArkresVocabulary.IMMUTABLE), null).toList().forEach(shapes::remove);
        shapes.add(CATEGORY_SHAPE, arkres(ArkresVocabulary.IMMUTABLE), RDF_FACTORY.createLiteral("yes"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains("boolean true"), failure.getMessage());
    }

    @Test
    void anImmutableOwnedFieldFailsTheSchema() {
        Graph shapes = shapes();
        shapes.add(SECTIONS_SHAPE, arkres(ArkresVocabulary.IMMUTABLE), bool(true));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains("owns its children"), failure.getMessage());
    }

    @Test
    void anImmutableStatusFieldFailsTheSchema() {
        Graph shapes = shapes();
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.IMMUTABLE), bool(true));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains("is a status field"), failure.getMessage());
    }

    @Test
    void aDefaultIntoAnImmutableFieldFailsTheSchema() {
        Graph shapes = shapes();
        defaultValue(shapes, publish, CATEGORY, vocab("Essay"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains(CATEGORY.getIRIString()), failure.getMessage());
        assertTrue(failure.getMessage().contains(ArkresVocabulary.IMMUTABLE), failure.getMessage());
    }

    @Test
    void aDefaultIntoAnOwnedOrUnknownFieldOrTheStatusFailsTheSchema() {
        for (IRI field : List.of(SECTIONS, UNKNOWN, STATE)) {
            Graph shapes = shapes();
            defaultValue(shapes, publish, field, vocab("Anything"));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()), field.getIRIString());

            assertTrue(failure.getMessage().contains(field.getIRIString()), failure.getMessage());
        }
    }

    @Test
    void aDefaultWithoutExactlyOneValueFailsTheSchema() {
        Graph shapes = shapes();
        BlankNode value = RDF_FACTORY.createBlankNode();
        shapes.add(publish, arkres(ArkresVocabulary.DEFAULT), value);
        shapes.add(value, arkres(ArkresVocabulary.DEFAULT_FIELD), SUMMARY);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains(ArkresVocabulary.DEFAULT_VALUE), failure.getMessage());
    }

    @Test
    void twoDefaultsIntoOneFieldFailTheSchema() {
        Graph shapes = shapes();
        defaultValue(shapes, publish, PUBLISHED_ON, RDF_FACTORY.createLiteral("2026-01-01", VocabXsd.DATE));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ResourceSchema.of(shapes, RDF_FACTORY.createGraph()));

        assertTrue(failure.getMessage().contains("two defaults"), failure.getMessage());
    }

    /**
     * A paper: a status Draft/Published/Withdrawn, a group of title, summary and the owned sections
     * editable in Draft, an immutable category, and the publication day as default of Draft to
     * Published but not of Withdrawn to Published.
     */
    private Graph shapes() {
        Graph shapes = RDF_FACTORY.createGraph();
        IRI node = RDF_FACTORY.createIRI(SHAPES + "Paper");
        shapes.add(node, shacl("targetClass"), PAPER);
        for (IRI field : List.of(TITLE, SUMMARY, PUBLISHED_ON)) {
            IRI property = RDF_FACTORY.createIRI(SHAPES + "Paper-" + field.getIRIString().substring(VOCAB.length()));
            shapes.add(node, shacl("property"), property);
            shapes.add(property, shacl("path"), field);
        }
        shapes.add(node, shacl("property"), CATEGORY_SHAPE);
        shapes.add(CATEGORY_SHAPE, shacl("path"), CATEGORY);
        shapes.add(CATEGORY_SHAPE, arkres(ArkresVocabulary.IMMUTABLE), bool(true));
        shapes.add(node, shacl("property"), SECTIONS_SHAPE);
        shapes.add(SECTIONS_SHAPE, shacl("path"), SECTIONS);
        shapes.add(SECTIONS_SHAPE, shacl("class"), SECTION);
        shapes.add(SECTIONS_SHAPE, arkres(ArkresVocabulary.CHILD_KIND), RDF_FACTORY.createLiteral("Section"));
        shapes.add(SECTIONS_SHAPE, arkres(ArkresVocabulary.ORDERED_BY), POSITION);
        shapes.add(node, shacl("property"), STATE_SHAPE);
        shapes.add(STATE_SHAPE, shacl("path"), STATE);
        shapes.add(STATE_SHAPE, shacl("in"), list(shapes, DRAFT, PUBLISHED, WITHDRAWN));
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.INITIAL_STATUS), DRAFT);
        group = RDF_FACTORY.createBlankNode();
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.FIELD_GROUP), group);
        for (IRI field : List.of(TITLE, SUMMARY, SECTIONS)) {
            shapes.add(group, arkres(ArkresVocabulary.GROUP_FIELD), field);
        }
        shapes.add(group, arkres(ArkresVocabulary.EDITABLE_IN), DRAFT);
        publish = transition(shapes, DRAFT, PUBLISHED);
        defaultValue(shapes, publish, PUBLISHED_ON, TODAY);
        transition(shapes, PUBLISHED, WITHDRAWN);
        transition(shapes, WITHDRAWN, PUBLISHED);
        return shapes;
    }

    private static BlankNode transition(final Graph shapes, final IRI from, final IRI to) {
        BlankNode transition = RDF_FACTORY.createBlankNode();
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.TRANSITION), transition);
        shapes.add(transition, arkres(ArkresVocabulary.FROM_STATUS), from);
        shapes.add(transition, arkres(ArkresVocabulary.TO_STATUS), to);
        return transition;
    }

    private static void defaultValue(final Graph shapes, final BlankNode transition, final IRI field,
            final RDFTerm value) {
        BlankNode node = RDF_FACTORY.createBlankNode();
        shapes.add(transition, arkres(ArkresVocabulary.DEFAULT), node);
        shapes.add(node, arkres(ArkresVocabulary.DEFAULT_FIELD), field);
        shapes.add(node, arkres(ArkresVocabulary.DEFAULT_VALUE), value);
    }

    private static BlankNode list(final Graph shapes, final IRI... items) {
        BlankNode head = RDF_FACTORY.createBlankNode();
        BlankNode cell = head;
        for (int index = 0; index < items.length; index++) {
            shapes.add(cell, VocabRdf.FIRST, items[index]);
            if (index == items.length - 1) {
                shapes.add(cell, VocabRdf.REST, VocabRdf.NIL);
            } else {
                BlankNode next = RDF_FACTORY.createBlankNode();
                shapes.add(cell, VocabRdf.REST, next);
                cell = next;
            }
        }
        return head;
    }

    private static RDFTerm bool(final boolean value) {
        return RDF_FACTORY.createLiteral(Boolean.toString(value), VocabXsd.BOOLEAN);
    }

    private static IRI vocab(final String localName) {
        return RDF_FACTORY.createIRI(VOCAB + localName);
    }

    private static IRI arkres(final String iri) {
        return RDF_FACTORY.createIRI(iri);
    }

    private static IRI shacl(final String localName) {
        return RDF_FACTORY.createIRI(SHACL + localName);
    }
}
