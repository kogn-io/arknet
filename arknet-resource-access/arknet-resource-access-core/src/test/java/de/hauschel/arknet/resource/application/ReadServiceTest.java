// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabRdfs;
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.resource.application.port.in.HistoryReading;
import de.hauschel.arknet.resource.application.port.in.RenderedField;
import de.hauschel.arknet.resource.application.port.in.RenderedResource;
import de.hauschel.arknet.resource.application.port.in.RenderedValue;
import de.hauschel.arknet.resource.application.port.in.ResourceReading;
import de.hauschel.arknet.resource.application.port.out.ResourceReader;
import de.hauschel.arknet.resource.domain.ArkresVocabulary;
import de.hauschel.arknet.resource.domain.PrefixBindings;
import de.hauschel.arknet.resource.domain.ResourceSchema;

/**
 * The generic read renders a type it has never seen in code: fixture shapes and ontology of an
 * invented namespace, no line of Java for the type. Each test pins one promise of the rendering.
 */
class ReadServiceTest {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String FX = "https://example.org/fixture#";
    private static final String SHAPES = "https://example.org/fixture-shapes#";
    private static final String STORE = "https://example.org/store/";
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final String OWL = "http://www.w3.org/2002/07/owl#";
    private static final ProjectId PROJECT = new ProjectId("fixture");

    private static final IRI THING = fx("Thing");
    private static final IRI LINK = fx("Link");
    private static final IRI STEP = fx("Step");
    private static final IRI TITLED = fx("Titled");
    private static final IRI NAME = fx("name");
    private static final IRI TITLE = fx("title");
    private static final IRI STATUS = fx("status");
    private static final IRI OPEN = fx("Open");
    private static final IRI STEPS = fx("step");
    private static final IRI POSITION = fx("position");
    private static final IRI STEP_TEXT = fx("stepText");
    private static final IRI MENTIONS = fx("mentions");
    private static final IRI REFERS_TO = fx("refersTo");
    private static final IRI RELATED = fx("related");
    private static final IRI SUPERSEDES = fx("supersedes");
    private static final IRI SUPERSEDED_BY = fx("supersededBy");
    private static final IRI TARGET = fx("target");
    private static final IRI LEGACY = fx("legacy");
    private static final IRI PART_OF = fx("partOf");
    private static final IRI KIND_TYPE = fx("kindType");
    private static final IRI CORE = fx("Core");
    private static final IRI NOTE = fx("note");
    private static final IRI NOTE_TEXT = fx("noteText");

    private static final IRI T1 = store("t1");
    private static final IRI T2 = store("t2");
    private static final IRI T3 = store("t3");
    private static final IRI T4 = store("t4");
    private static final IRI LINK1 = store("link1");
    private static final IRI TWO_LABELS = store("two-labels");
    private static final IRI GONE = store("gone");

    private final FakeReader reader = new FakeReader();
    private final ReadService service = service();

    @Test
    void ownedChildrenAreRenderedInPlaceInTheirOrder() {
        storeT1();

        RenderedResource t1 = found("T-1");

        List<RenderedValue> steps = values(t1.fields(), STEPS);
        assertEquals(List.of(Optional.of(1), Optional.of(2)),
                steps.stream().map(step -> ((RenderedValue.Child) step).position()).toList());
        RenderedValue.Child first = (RenderedValue.Child) steps.get(0);
        assertEquals("first", ((RenderedValue.Text) values(first.fields(), STEP_TEXT).get(0)).text());
        assertTrue(first.fields().stream().noneMatch(field -> field.iri().equals(POSITION.getIRIString())),
                "the position is the child's place, not a field");
    }

    @Test
    void anEdgeRendersAsCodeAndLabelOfItsTarget() {
        storeT1();

        RenderedValue.Reference target = (RenderedValue.Reference) values(found("T-1").fields(), REFERS_TO).get(0);

        assertEquals("T-2", target.handle());
        assertEquals("Beta", target.label());
    }

    @Test
    void aTypeWithoutLabelFieldFallsBackToTypeLabelAndHandle() {
        storeT1();
        reader.content.add(LINK1, VocabRdf.TYPE, LINK);
        reader.content.add(LINK1, TARGET, T1);

        RenderedResource link = found(LINK1.getIRIString());
        RenderedValue.Reference incoming = (RenderedValue.Reference) values(found("T-1").incoming(), TARGET).get(0);

        assertEquals(Optional.empty(), link.label());
        assertEquals("Link " + LINK1.getIRIString(), link.displayLabel());
        assertEquals("Link " + LINK1.getIRIString(), incoming.label());
    }

    @Test
    void aNodeReachingTwoLabelFieldsFallsBackInsteadOfFailingTheRead() {
        storeT1();
        reader.content.add(TWO_LABELS, VocabRdf.TYPE, THING);
        reader.content.add(TWO_LABELS, VocabRdf.TYPE, TITLED);
        reader.content.add(TWO_LABELS, VocabDct.IDENTIFIER, RDF_FACTORY.createLiteral("T-7"));
        reader.content.add(TWO_LABELS, NAME, RDF_FACTORY.createLiteral("Name", "en"));
        reader.content.add(TWO_LABELS, TITLE, RDF_FACTORY.createLiteral("Title", "en"));
        reader.content.add(T3, REFERS_TO, TWO_LABELS);

        List<ResourceReading> readings = service.read(PROJECT, List.of("T-7", "T-3", "T-1"), "en", false);

        RenderedResource twoLabels = ((ResourceReading.Found) readings.get(0)).resource();
        assertEquals("Thing T-7", twoLabels.displayLabel());
        RenderedValue.Reference edge = values(((ResourceReading.Found) readings.get(1)).resource().fields(),
                REFERS_TO).stream().map(RenderedValue.Reference.class::cast)
                .filter(reference -> reference.handle().equals("T-7")).findFirst().orElseThrow();
        assertEquals("Thing T-7", edge.label());
        assertInstanceOf(ResourceReading.Found.class, readings.get(2));
    }

    @Test
    void aMultilingualFieldShowsTheDisplayLanguageAndMarksAFallback() {
        storeT1();

        RenderedResource t1 = found("T-1");
        RenderedResource t2 = found("T-2");

        assertEquals("Alpha", t1.label().orElseThrow().text());
        assertFalse(t1.label().orElseThrow().fallback());
        assertEquals("Beta", t2.label().orElseThrow().text());
        assertTrue(t2.label().orElseThrow().fallback(), "T-2 has no English name");
        RenderedResource german = ((ResourceReading.Found) service.read(PROJECT, List.of("T-1"), "de", false)
                .get(0)).resource();
        assertEquals("Alpha-de", german.label().orElseThrow().text());
        assertEquals("Ding", german.typeLabel());
    }

    @Test
    void allLanguagesShowsEveryValueOfAMultilingualField() {
        storeT1();

        RenderedResource t1 = ((ResourceReading.Found) service.read(PROJECT, List.of("T-1"), "en", true).get(0))
                .resource();

        assertEquals(Set.of("Alpha", "Alpha-de"), Set.copyOf(values(t1.fields(), NAME).stream()
                .map(value -> ((RenderedValue.Text) value).text()).toList()));
    }

    @Test
    void symmetricAndInverseEdgesAreMergedIntoTheResourcesOwnFields() {
        storeT1();

        RenderedResource t1 = found("T-1");

        assertEquals(List.of("T-3"), handles(values(t1.fields(), RELATED)));
        assertEquals(List.of("T-2"), handles(values(t1.fields(), SUPERSEDES)));
        assertTrue(t1.incoming().stream().noneMatch(field -> field.iri().equals(RELATED.getIRIString())
                || field.iri().equals(SUPERSEDED_BY.getIRIString())), "merged edges are not incoming");
    }

    @Test
    void otherIncomingEdgesAreGroupedByPropertyFromTheOwningResource() {
        storeT1();

        RenderedResource t1 = found("T-1");

        assertEquals(List.of("T-3"), handles(values(t1.incoming(), REFERS_TO)));
        assertEquals(List.of("T-4"), handles(values(t1.incoming(), MENTIONS)), "the step's owner, not the step");
    }

    @Test
    void statementsOutsideTheShapesAreKeptAsFurtherStatements() {
        storeT1();

        RenderedResource t1 = found("T-1");

        assertEquals("old", ((RenderedValue.Text) values(t1.otherStatements(), LEGACY).get(0)).text());
        assertTrue(t1.fields().stream().noneMatch(field -> field.iri().equals(LEGACY.getIRIString())));
    }

    @Test
    void theFieldsFollowTheOrderOfDescribe() {
        storeT1();

        assertEquals(List.of("dcterms:identifier", "fx:name", "fx:refersTo", "fx:related", "fx:status", "fx:step",
                "fx:supersedes"), found("T-1").fields().stream().map(RenderedField::name).toList());
    }

    @Test
    void theHeadCarriesCodeTypeLabelStatusAndTheHistoryDates() {
        storeT1();
        reader.histories.put(T1, List.of(
                new ResourceReader.HistoryEntry(1, false, "2026-01-01T00:00:00Z", Optional.of("v1"), Optional.empty()),
                new ResourceReader.HistoryEntry(2, false, "2026-02-01T00:00:00Z", Optional.of("v2"),
                        Optional.empty())));

        RenderedResource t1 = found("T-1");

        assertEquals("T-1", t1.handle());
        assertEquals("Thing", t1.typeLabel());
        assertEquals("Open", t1.status().orElseThrow().label());
        assertEquals(Optional.of("2026-01-01T00:00:00Z"), t1.created());
        assertEquals(Optional.of("2026-02-01T00:00:00Z"), t1.lastChanged());
    }

    @Test
    void aDeletedCodeAnswersWithItsDeletionAndLastVersion() {
        storeT1();
        Graph last = RDF_FACTORY.createGraph();
        last.add(GONE, VocabRdf.TYPE, THING);
        last.add(GONE, NAME, RDF_FACTORY.createLiteral("Gone", "en"));
        reader.retainedCodes.put("T-9", GONE);
        reader.deletions.put(GONE, new ResourceReader.Deletion(GONE, Optional.of("2026-03-01T00:00:00Z"),
                Optional.empty(), Optional.of("T-9"), last));

        ResourceReading.Deleted deleted = assertInstanceOf(ResourceReading.Deleted.class,
                service.read(PROJECT, List.of("T-9"), "en", false).get(0));

        assertEquals(Optional.of("2026-03-01T00:00:00Z"), deleted.deletedAt());
        assertEquals("Gone", ((RenderedValue.Text) values(deleted.lastVersion(), NAME).get(0)).text());
    }

    @Test
    void severalHandlesAreReadInOneTransactionAndAnUnknownOneFailsOnlyItself() {
        storeT1();
        reader.content.add(store("dup1"), VocabDct.IDENTIFIER, RDF_FACTORY.createLiteral("T-5"));
        reader.content.add(store("dup2"), VocabDct.IDENTIFIER, RDF_FACTORY.createLiteral("T-5"));

        List<ResourceReading> readings = service.read(PROJECT,
                List.of("T-1", "T-99", "zz:x", "T-5", "fx:Thing", "T-2"), "en", false);

        assertEquals(1, reader.reads);
        assertInstanceOf(ResourceReading.Found.class, readings.get(0));
        assertInstanceOf(ResourceReading.Unresolved.class, readings.get(1));
        assertTrue(((ResourceReading.Unresolved) readings.get(2)).reason().contains("not bound"));
        assertTrue(((ResourceReading.Unresolved) readings.get(3)).reason().contains("several"));
        assertInstanceOf(ResourceReading.Unresolved.class, readings.get(4));
        assertEquals("T-2", ((ResourceReading.Found) readings.get(5)).resource().handle());
    }

    @Test
    void aFragmentOrBlankNodeNoOwnedFieldReachesIsRenderedInPlace() {
        storeT1();
        IRI kind = RDF_FACTORY.createIRI(T1.getIRIString() + "#Kind");
        reader.content.add(T1, PART_OF, kind);
        reader.content.add(kind, KIND_TYPE, CORE);
        BlankNode note = RDF_FACTORY.createBlankNode();
        reader.content.add(T1, NOTE, note);
        reader.content.add(note, NOTE_TEXT, RDF_FACTORY.createLiteral("inner"));

        RenderedResource t1 = found("T-1");

        RenderedValue.Child part = assertInstanceOf(RenderedValue.Child.class, values(t1.otherStatements(), PART_OF)
                .get(0));
        assertEquals(kind.getIRIString(), part.iri());
        assertEquals(Optional.empty(), part.position());
        assertEquals("fx:Core", ((RenderedValue.Reference) values(part.otherStatements(), KIND_TYPE).get(0)).handle());
        RenderedValue.Child inner = assertInstanceOf(RenderedValue.Child.class, values(t1.otherStatements(), NOTE)
                .get(0));
        assertEquals("inner", ((RenderedValue.Text) values(inner.otherStatements(), NOTE_TEXT).get(0)).text());
    }

    @Test
    void aHandleIsACodeAFullIriOrAPrefixedNameButNoBlankNodeAndNoFragment() {
        storeT1();

        List<ResourceReading> readings = service.read(PROJECT, List.of("_:b1", T1.getIRIString() + "#step-1",
                "<" + T1.getIRIString() + ">", T1.getIRIString()), "en", false);

        assertTrue(((ResourceReading.Unresolved) readings.get(0)).reason().contains("blank node"));
        assertTrue(((ResourceReading.Unresolved) readings.get(1)).reason().contains("read that resource")
                && ((ResourceReading.Unresolved) readings.get(1)).reason().contains("<" + T1.getIRIString() + ">"));
        assertEquals("T-1", ((ResourceReading.Found) readings.get(2)).resource().handle());
        assertEquals("T-1", ((ResourceReading.Found) readings.get(3)).resource().handle());
    }

    @Test
    void theHistoryNamesStateEntriesAndTheDeletion() {
        storeT1();
        reader.histories.put(GONE, List.of(
                new ResourceReader.HistoryEntry(1, false, "2026-01-01T00:00:00Z", Optional.of("v1"), Optional.empty()),
                new ResourceReader.HistoryEntry(2, true, "2026-03-01T00:00:00Z", Optional.empty(),
                        Optional.of("gone"))));
        reader.retainedCodes.put("T-9", GONE);
        reader.deletions.put(GONE, new ResourceReader.Deletion(GONE, Optional.of("2026-03-01T00:00:00Z"),
                Optional.of("gone"), Optional.of("T-9"), RDF_FACTORY.createGraph()));

        HistoryReading history = service.history(PROJECT, "T-9", "en");

        assertEquals("DELETED", history.publishState());
        assertEquals(2, history.entries().size());
        assertEquals(Optional.of("2026-01-01T00:00:00Z"), history.created());
        assertTrue(history.deleted().isPresent());
        assertThrows(IllegalArgumentException.class, () -> service.history(PROJECT, "T-99", "en"));
    }

    private void storeT1() {
        thing(T1, "T-1", "Alpha", "en");
        reader.content.add(T1, NAME, RDF_FACTORY.createLiteral("Alpha-de", "de"));
        reader.content.add(T1, STATUS, OPEN);
        reader.content.add(T1, REFERS_TO, T2);
        reader.content.add(T1, LEGACY, RDF_FACTORY.createLiteral("old"));
        step(T1, "step-2", 2, "second");
        step(T1, "step-1", 1, "first");
        thing(T2, "T-2", "Beta", "de");
        reader.content.add(T2, SUPERSEDED_BY, T1);
        thing(T3, "T-3", "Gamma", "en");
        reader.content.add(T3, RELATED, T1);
        reader.content.add(T3, REFERS_TO, T1);
        thing(T4, "T-4", "Delta", "en");
        IRI t4Step = step(T4, "step-1", 1, "mentions T-1");
        reader.content.add(t4Step, MENTIONS, T1);
    }

    private void thing(final IRI iri, final String code, final String name, final String language) {
        reader.content.add(iri, VocabRdf.TYPE, THING);
        reader.content.add(iri, VocabDct.IDENTIFIER, RDF_FACTORY.createLiteral(code));
        reader.content.add(iri, NAME, RDF_FACTORY.createLiteral(name, language));
    }

    private IRI step(final IRI owner, final String fragment, final int position, final String text) {
        IRI step = RDF_FACTORY.createIRI(owner.getIRIString() + "#" + fragment);
        reader.content.add(owner, STEPS, step);
        reader.content.add(step, VocabRdf.TYPE, STEP);
        reader.content.add(step, POSITION, RDF_FACTORY.createLiteral(Integer.toString(position), VocabXsd.INTEGER));
        reader.content.add(step, STEP_TEXT, RDF_FACTORY.createLiteral(text, "en"));
        return step;
    }

    private RenderedResource found(final String handle) {
        return assertInstanceOf(ResourceReading.Found.class, service.read(PROJECT, List.of(handle), "en", false)
                .get(0)).resource();
    }

    private static List<RenderedValue> values(final List<RenderedField> fields, final IRI property) {
        return fields.stream().filter(field -> field.iri().equals(property.getIRIString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + property.getIRIString() + " in " + fields))
                .values();
    }

    private static List<String> handles(final List<RenderedValue> values) {
        return values.stream().map(value -> ((RenderedValue.Reference) value).handle()).toList();
    }

    private ReadService service() {
        Graph shapes = shapes();
        Graph ontology = ontology();
        ResourceSchema schema = ResourceSchema.of(shapes, ontology);
        PrefixBindings prefixes = new PrefixBindings(Map.of("fx", FX, "dcterms", "http://purl.org/dc/terms/"));
        DescribeService describer = new DescribeService(shapes, ontology, schema, prefixes, DisplayLocale.DEFAULT);
        return new ReadService(reader, schema, ontology, prefixes, describer, DisplayLocale.DEFAULT);
    }

    private static Graph shapes() {
        Graph shapes = RDF_FACTORY.createGraph();
        shapes.add(THING, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        shapes.add(THING, arkres(ArkresVocabulary.CODE_PREFIX), RDF_FACTORY.createLiteral("T-"));
        shapes.add(LINK, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        IRI thing = shape("Thing");
        shapes.add(thing, sh("targetClass"), THING);
        IRI identifier = property(shapes, thing, VocabDct.IDENTIFIER);
        shapes.add(identifier, sh("minCount"), integer(1));
        IRI name = property(shapes, thing, NAME);
        shapes.add(name, sh("minCount"), integer(1));
        shapes.add(name, sh("uniqueLang"), bool());
        shapes.add(name, arkres(ArkresVocabulary.LABEL_FIELD), bool());
        IRI status = property(shapes, thing, STATUS);
        shapes.add(status, arkres(ArkresVocabulary.INITIAL_STATUS), OPEN);
        IRI steps = property(shapes, thing, STEPS);
        shapes.add(steps, sh("class"), STEP);
        shapes.add(steps, arkres(ArkresVocabulary.CHILD_KIND), RDF_FACTORY.createLiteral("step"));
        shapes.add(steps, arkres(ArkresVocabulary.ORDERED_BY), POSITION);
        for (IRI edge : List.of(REFERS_TO, RELATED, SUPERSEDES)) {
            shapes.add(property(shapes, thing, edge), sh("class"), THING);
        }
        IRI step = shape("Step");
        shapes.add(step, sh("targetClass"), STEP);
        property(shapes, step, POSITION);
        shapes.add(property(shapes, step, STEP_TEXT), sh("uniqueLang"), bool());
        property(shapes, step, MENTIONS);
        IRI link = shape("Link");
        shapes.add(link, sh("targetClass"), LINK);
        shapes.add(property(shapes, link, TARGET), sh("class"), THING);
        IRI titled = shape("Titled");
        shapes.add(titled, sh("targetClass"), TITLED);
        shapes.add(property(shapes, titled, TITLE), arkres(ArkresVocabulary.LABEL_FIELD), bool());
        return shapes;
    }

    private static Graph ontology() {
        Graph ontology = RDF_FACTORY.createGraph();
        ontology.add(THING, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Thing", "en"));
        ontology.add(THING, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Ding", "de"));
        ontology.add(LINK, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Link", "en"));
        ontology.add(OPEN, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Open", "en"));
        ontology.add(RELATED, VocabRdf.TYPE, RDF_FACTORY.createIRI(OWL + "SymmetricProperty"));
        ontology.add(SUPERSEDED_BY, RDF_FACTORY.createIRI(OWL + "inverseOf"), SUPERSEDES);
        return ontology;
    }

    private static IRI property(final Graph shapes, final IRI node, final IRI path) {
        IRI property = shape(node.getIRIString().substring(SHAPES.length()) + "-"
                + path.getIRIString().replaceAll(".*[#/]", ""));
        shapes.add(node, sh("property"), property);
        shapes.add(property, sh("path"), path);
        return property;
    }

    private static RDFTerm bool() {
        return RDF_FACTORY.createLiteral("true", VocabXsd.BOOLEAN);
    }

    private static RDFTerm integer(final int value) {
        return RDF_FACTORY.createLiteral(Integer.toString(value), VocabXsd.INTEGER);
    }

    private static IRI fx(final String localName) {
        return RDF_FACTORY.createIRI(FX + localName);
    }

    private static IRI shape(final String localName) {
        return RDF_FACTORY.createIRI(SHAPES + localName);
    }

    private static IRI store(final String localName) {
        return RDF_FACTORY.createIRI(STORE + localName);
    }

    private static IRI sh(final String localName) {
        return RDF_FACTORY.createIRI(SHACL + localName);
    }

    private static IRI arkres(final String iri) {
        return RDF_FACTORY.createIRI(iri);
    }
}
