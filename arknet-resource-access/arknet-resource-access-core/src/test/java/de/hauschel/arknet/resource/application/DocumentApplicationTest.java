// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabRdfs;
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.resource.domain.ArkresVocabulary;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;
import de.hauschel.arknet.resource.domain.SubmittedDocument;

/**
 * The two document checks the engine makes against shapes and ontology rather than against the
 * store's shapes gate: a document writes only predicates the shapes know for the node it writes
 * them on, and a delete leaves no edge pointing into nothing. Fixture shapes, no namespace of a
 * bounded context.
 */
class DocumentApplicationTest {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String VOCAB = "https://example.org/vocab#";
    private static final String SHAPES = "https://example.org/shapes#";
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final String STORE = "https://example.org/store/";

    private static final IRI THING = vocab("Thing");
    private static final IRI PART = vocab("Part");
    private static final IRI BASE = vocab("Base");
    private static final IRI SPECIAL = vocab("Special");
    private static final IRI NAME = vocab("name");
    private static final IRI REFERS_TO = vocab("refersTo");
    private static final IRI POINTS_AT = vocab("pointsAt");
    private static final IRI LEGACY = vocab("legacy");
    private static final IRI SEE_ALSO = vocab("seeAlso");
    private static final IRI PARTS = vocab("part");
    private static final IRI TEXT = vocab("text");
    private static final IRI POSITION = vocab("position");
    private static final IRI BASE_FIELD = vocab("baseField");
    private static final IRI DETAIL = vocab("Detail");
    private static final IRI DETAILS = vocab("detail");
    private static final IRI NOTE = vocab("note");

    private static final IRI OBJECT = arkres(ArkresVocabulary.OBJECT);
    private static final IRI SET = arkres(ArkresVocabulary.SET);
    private static final IRI CLEAR = arkres(ArkresVocabulary.CLEAR);
    private static final IRI FIELD = arkres(ArkresVocabulary.FIELD);
    private static final IRI VALUE = arkres(ArkresVocabulary.VALUE);

    private static final ResourceSchema SCHEMA = ResourceSchema.of(shapes(), ontology());

    private final FakeBatch batch = new FakeBatch();
    private final Graph document = RDF_FACTORY.createGraph();

    @Test
    void aCreateWritingAPredicateNoShapeOfItsTypeNamesIsRejected() {
        BlankNode thing = create(THING);
        document.add(thing, NAME, literal("a thing"));
        document.add(thing, LEGACY, literal("not a field"));

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(LEGACY.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(THING.getIRIString()), rejected.getMessage());
        assertTrue(batch.created.isEmpty());
    }

    @Test
    void aCreateMayWriteAFieldTheShapeOfASuperclassNames() {
        BlankNode special = create(SPECIAL);
        document.add(special, BASE_FIELD, literal("inherited"));

        assertDoesNotThrow(this::apply);

        assertEquals(1, batch.created.size());
    }

    @Test
    void anInlineChildIsCheckedAgainstTheShapesOfItsOwnClass() {
        BlankNode thing = create(THING);
        BlankNode part = RDF_FACTORY.createBlankNode();
        document.add(thing, PARTS, part);
        document.add(part, TEXT, literal("first"));
        document.add(part, POSITION, RDF_FACTORY.createLiteral("1", VocabXsd.INTEGER));
        document.add(part, NAME, literal("a field of the parent, not of the child"));

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(NAME.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(PART.getIRIString()), rejected.getMessage());
    }

    @Test
    void aSetOfAPredicateNoShapeNamesIsRejected() {
        IRI stored = storedThing("T-1");
        BlankNode values = update(stored);
        document.add(values, LEGACY, literal("new"));

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(LEGACY.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(stored.getIRIString()), rejected.getMessage());
    }

    @Test
    void aClearMayRemoveAStoredPredicateNoShapeNames() {
        IRI stored = storedThing("T-1");
        batch.add(stored, LEGACY, literal("old"));
        BlankNode activity = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.UPDATE));
        document.add(activity, OBJECT, stored);
        document.add(activity, CLEAR, LEGACY);

        assertDoesNotThrow(this::apply);

        assertFalse(batch.updated.get(stored).stream(stored, LEGACY, null).findAny().isPresent());
    }

    @Test
    void anAddToOfAPredicateNoShapeNamesIsRejected() {
        IRI stored = storedThing("T-1");
        IRI other = storedThing("T-2");
        BlankNode activity = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.ADD_TO));
        document.add(activity, OBJECT, stored);
        document.add(activity, FIELD, SEE_ALSO);
        document.add(activity, VALUE, other);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(SEE_ALSO.getIRIString()), rejected.getMessage());
    }

    @Test
    void anInlineValueTypedWithAClassIsHeldToThatClassesFields() {
        BlankNode thing = create(THING);
        BlankNode detail = RDF_FACTORY.createBlankNode();
        document.add(thing, DETAILS, detail);
        document.add(detail, VocabRdf.TYPE, DETAIL);
        document.add(detail, NOTE, literal("a field of the detail"));
        document.add(detail, NAME, literal("a field of the thing, not of the detail"));

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(NAME.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(DETAIL.getIRIString()), rejected.getMessage());
        assertFalse(rejected.getMessage().contains(NOTE.getIRIString() + " is no field"), rejected.getMessage());
    }

    @Test
    void anInlineValueWithFieldsOfItsClassOnlyIsWritten() {
        BlankNode thing = create(THING);
        BlankNode detail = RDF_FACTORY.createBlankNode();
        document.add(thing, DETAILS, detail);
        document.add(detail, VocabRdf.TYPE, DETAIL);
        document.add(detail, NOTE, literal("a field of the detail"));

        assertDoesNotThrow(this::apply);

        assertEquals(1, batch.created.size());
    }

    @Test
    void anUntypedInlineValueHasNoFields() {
        BlankNode thing = create(THING);
        BlankNode detail = RDF_FACTORY.createBlankNode();
        document.add(thing, DETAILS, detail);
        document.add(detail, NOTE, literal("no class names this field for an untyped node"));

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(NOTE.getIRIString() + " is no field"), rejected.getMessage());
    }

    @Test
    void anEdgeTheSameDocumentAddsToAStoredResourceBlocksTheDelete() {
        IRI target = storedThing("T-1");
        IRI referrer = storedThing("T-2");
        delete(target);
        BlankNode activity = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.ADD_TO));
        document.add(activity, OBJECT, referrer);
        document.add(activity, FIELD, REFERS_TO);
        document.add(activity, VALUE, target);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(referrer.getIRIString() + " still points at it over "
                + REFERS_TO.getIRIString()), rejected.getMessage());
        assertTrue(batch.deleted.isEmpty());
    }

    @Test
    void anEdgeAResourceTheSameDocumentCreatesBlocksTheDelete() {
        IRI target = storedThing("T-1");
        delete(target);
        BlankNode thing = create(THING);
        document.add(thing, REFERS_TO, target);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains("(created by this document) still points at it over "
                + REFERS_TO.getIRIString()), rejected.getMessage());
        assertTrue(batch.created.isEmpty());
    }

    @Test
    void deletingAResourceAShapedEdgeStillPointsAtIsRejected() {
        IRI target = storedThing("T-1");
        IRI referrer = storedThing("T-2");
        batch.add(referrer, REFERS_TO, target);
        delete(target);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(target.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(referrer.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(REFERS_TO.getIRIString()), rejected.getMessage());
        assertTrue(batch.deleted.isEmpty());
    }

    @Test
    void deletingAResourceAnEdgeWithItsClassAsRangeStillPointsAtIsRejected() {
        IRI target = storedThing("T-1");
        IRI referrer = storedThing("T-2");
        batch.add(referrer, POINTS_AT, target);
        delete(target);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(POINTS_AT.getIRIString()), rejected.getMessage());
    }

    @Test
    void anEdgeFromAChildCountsForItsResource() {
        IRI target = storedThing("T-1");
        IRI referrer = storedThing("T-2");
        IRI child = RDF_FACTORY.createIRI(referrer.getIRIString() + "#Part-1");
        batch.add(referrer, PARTS, child);
        batch.add(child, VocabRdf.TYPE, PART);
        batch.add(child, POSITION, RDF_FACTORY.createLiteral("1", VocabXsd.INTEGER));
        batch.add(child, REFERS_TO, target);
        delete(target);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(child.getIRIString()), rejected.getMessage());
    }

    @Test
    void deletingTheReferrerInTheSameDocumentLetsTheDeleteThrough() {
        IRI target = storedThing("T-1");
        IRI referrer = storedThing("T-2");
        batch.add(referrer, REFERS_TO, target);
        delete(target);
        delete(referrer);

        assertDoesNotThrow(this::apply);

        assertEquals(2, batch.deleted.size());
    }

    @Test
    void removingTheEdgeInTheSameDocumentLetsTheDeleteThrough() {
        IRI target = storedThing("T-1");
        IRI referrer = storedThing("T-2");
        batch.add(referrer, REFERS_TO, target);
        delete(target);
        BlankNode activity = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.REMOVE_FROM));
        document.add(activity, OBJECT, referrer);
        document.add(activity, FIELD, REFERS_TO);
        document.add(activity, VALUE, target);

        assertDoesNotThrow(this::apply);

        assertEquals(List.of(target), batch.deleted);
    }

    @Test
    void aResourcePointingAtItselfDoesNotBlockItsOwnDelete() {
        IRI target = storedThing("T-1");
        batch.add(target, REFERS_TO, target);
        delete(target);

        assertDoesNotThrow(this::apply);
    }

    private void apply() {
        new DocumentApplication(SubmittedDocument.of(document, SCHEMA), SCHEMA, batch,
                () -> ResourceId.of(STORE + UUID.randomUUID())).apply();
    }

    private BlankNode create(final IRI type) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        BlankNode resource = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.CREATE));
        document.add(activity, OBJECT, resource);
        document.add(resource, VocabRdf.TYPE, type);
        return resource;
    }

    private BlankNode update(final IRI resource) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        BlankNode values = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.UPDATE));
        document.add(activity, OBJECT, resource);
        document.add(activity, SET, values);
        return values;
    }

    private void delete(final IRI resource) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.DELETE));
        document.add(activity, OBJECT, resource);
    }

    private IRI storedThing(final String code) {
        IRI resource = RDF_FACTORY.createIRI(STORE + code);
        batch.add(resource, VocabRdf.TYPE, THING);
        batch.add(resource, NAME, literal("stored " + code));
        return resource;
    }

    private static Graph shapes() {
        Graph shapes = RDF_FACTORY.createGraph();
        shapes.add(THING, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        shapes.add(THING, arkres(ArkresVocabulary.CODE_PREFIX), literal("T-"));
        shapes.add(SPECIAL, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        nodeShape(shapes, "Thing", THING, NAME, REFERS_TO, PARTS, DETAILS);
        nodeShape(shapes, "Detail", DETAIL, NOTE);
        IRI refers = RDF_FACTORY.createIRI(SHAPES + "Thing-refersTo");
        shapes.add(refers, shacl("class"), THING);
        IRI owned = RDF_FACTORY.createIRI(SHAPES + "Thing-part");
        shapes.add(owned, shacl("class"), PART);
        shapes.add(owned, arkres(ArkresVocabulary.CHILD_KIND), literal("Part"));
        shapes.add(owned, arkres(ArkresVocabulary.ORDERED_BY), POSITION);
        nodeShape(shapes, "Part", PART, TEXT, POSITION, REFERS_TO);
        nodeShape(shapes, "Base", BASE, BASE_FIELD);
        return shapes;
    }

    private static Graph ontology() {
        Graph ontology = RDF_FACTORY.createGraph();
        ontology.add(SPECIAL, RDF_FACTORY.createIRI(VocabRdfs.NAMESPACE + "subClassOf"), BASE);
        ontology.add(POINTS_AT, RDF_FACTORY.createIRI(VocabRdfs.NAMESPACE + "range"), THING);
        return ontology;
    }

    private static void nodeShape(final Graph shapes, final String name, final IRI target, final IRI... fields) {
        IRI node = RDF_FACTORY.createIRI(SHAPES + name);
        shapes.add(node, shacl("targetClass"), target);
        for (IRI field : fields) {
            IRI property = RDF_FACTORY.createIRI(SHAPES + name + "-" + field.getIRIString().substring(VOCAB.length()));
            shapes.add(node, shacl("property"), property);
            shapes.add(property, shacl("path"), field);
        }
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

    private static RDFTerm literal(final String text) {
        return RDF_FACTORY.createLiteral(text);
    }
}
