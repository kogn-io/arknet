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
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabRdfs;
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.resource.application.port.in.ListResources;
import de.hauschel.arknet.resource.application.port.in.ListedResource;
import de.hauschel.arknet.resource.application.port.in.ResourceListing;
import de.hauschel.arknet.resource.application.port.in.ResourceReading;
import de.hauschel.arknet.resource.domain.ArkresVocabulary;
import de.hauschel.arknet.resource.domain.PrefixBindings;
import de.hauschel.arknet.resource.domain.ResourceSchema;

/**
 * The listing works over a type it has never seen in code: fixture shapes and ontology of an invented
 * namespace. A {@code Thing} has a code, a label and a status, a {@code Link} neither label nor
 * status, a {@code Gadget} is a {@code Thing} without status rules of its own.
 */
class ListServiceTest {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String FX = "https://example.org/fixture#";
    private static final String SHAPES = "https://example.org/fixture-shapes#";
    private static final String STORE = "https://example.org/store/";
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final ProjectId PROJECT = new ProjectId("fixture");

    private static final IRI BASE = fx("Base");
    private static final IRI THING = fx("Thing");
    private static final IRI GADGET = fx("Gadget");
    private static final IRI LINK = fx("Link");
    private static final IRI NAME = fx("name");
    private static final IRI STATUS = fx("status");
    private static final IRI OPEN = fx("Open");
    private static final IRI DONE = fx("Done");
    private static final IRI TARGET = fx("target");
    private static final IRI SUB_CLASS_OF = RDF_FACTORY.createIRI(VocabRdfs.NAMESPACE + "subClassOf");

    private final FakeReader reader = new FakeReader();
    private final ReadService service = service();

    @Test
    void listsEveryTypeInTheOrderOfTheirCodesNotOfTheirText() {
        thing(store("a"), "T-10", "Ten", "en", OPEN);
        thing(store("b"), "T-2", "Two", "en", OPEN);
        gadget(store("c"), "G-1", "Gadget one");
        reader.content.add(store("l"), VocabRdf.TYPE, LINK);

        ResourceListing listing = service.list(PROJECT, null, null, "en", false, 0);

        assertEquals(List.of("G-1", "T-2", "T-10", STORE + "l"),
                listing.resources().stream().map(ListedResource::handle).toList());
        assertEquals(4, listing.total());
        assertTrue(listing.readings().isEmpty());
    }

    @Test
    void aTypeIsNamedByPrefixedNameOrLabelAndIncludesItsSubclasses() {
        thing(store("a"), "T-1", "One", "en", OPEN);
        gadget(store("c"), "G-1", "Gadget one");
        reader.content.add(store("l"), VocabRdf.TYPE, LINK);

        List<String> byCurie = handles(service.list(PROJECT, "fx:Thing", null, "en", false, 0));
        List<String> byLabel = handles(service.list(PROJECT, "thing", null, "en", false, 0));
        List<String> byBase = handles(service.list(PROJECT, "Base", null, "en", false, 0));
        List<String> gadgetOnly = handles(service.list(PROJECT, "fx:Gadget", null, "en", false, 0));

        assertEquals(List.of("G-1", "T-1"), byCurie);
        assertEquals(byCurie, byLabel);
        assertEquals(byCurie, byBase);
        assertEquals(List.of("G-1"), gadgetOnly);
    }

    @Test
    void anUnknownTypeIsRejectedWithTheTypesThereAre() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> service.list(PROJECT, "Nothing", null, "en", false, 0));

        assertTrue(unknown.getMessage().contains("fx:Thing"), unknown.getMessage());
        assertTrue(unknown.getMessage().contains("describe"), unknown.getMessage());
    }

    @Test
    void aStatusFilterKeepsTheResourcesInThatStatus() {
        thing(store("a"), "T-1", "One", "en", OPEN);
        thing(store("b"), "T-2", "Two", "en", DONE);

        assertEquals(List.of("T-2"), handles(service.list(PROJECT, "Thing", "done", "en", false, 0)));
        assertEquals(List.of("T-1"), handles(service.list(PROJECT, "Thing", "fx:Open", "en", false, 0)));
    }

    @Test
    void aStatusFilterNeedsATypeWithAStatusFieldAndAKnownStatus() {
        IllegalArgumentException everyType = assertThrows(IllegalArgumentException.class,
                () -> service.list(PROJECT, null, "Open", "en", false, 0));
        IllegalArgumentException noStatusField = assertThrows(IllegalArgumentException.class,
                () -> service.list(PROJECT, "Link", "Open", "en", false, 0));
        IllegalArgumentException unknownStatus = assertThrows(IllegalArgumentException.class,
                () -> service.list(PROJECT, "Thing", "Gone", "en", false, 0));

        assertTrue(everyType.getMessage().contains("describe"), everyType.getMessage());
        assertTrue(noStatusField.getMessage().contains("fx:Link"), noStatusField.getMessage());
        assertTrue(unknownStatus.getMessage().contains("fx:Done") && unknownStatus.getMessage().contains("fx:Open"),
                unknownStatus.getMessage());
    }

    @Test
    void aLabelOutsideTheDisplayLanguageIsMarkedAndAResourceWithoutOneFallsBack() {
        thing(store("a"), "T-1", "One", "de", OPEN);
        reader.content.add(store("l"), VocabRdf.TYPE, LINK);

        List<ListedResource> rows = service.list(PROJECT, null, null, "en", false, 0).resources();

        ListedResource thing = rows.stream().filter(row -> row.handle().equals("T-1")).findFirst().orElseThrow();
        assertTrue(thing.label().orElseThrow().fallback());
        assertEquals("One", thing.label().orElseThrow().text());
        assertEquals("Thing", thing.typeLabel());
        assertEquals("fx:Open", thing.status().orElseThrow().handle());
        ListedResource link = rows.stream().filter(row -> row.handle().equals(STORE + "l")).findFirst()
                .orElseThrow();
        assertEquals(Optional.empty(), link.label());
        assertEquals("Link", link.typeLabel());
    }

    @Test
    void earlierDataWithoutCodeLabelOrAnyShapedFieldIsListedNotRejected() {
        reader.content.add(store("old"), VocabRdf.TYPE, THING);
        reader.content.add(store("old"), fx("legacy"), RDF_FACTORY.createLiteral("kept"));
        reader.content.add(store("old2"), VocabRdf.TYPE, THING);
        reader.content.add(store("old2"), VocabRdf.TYPE, GADGET);
        thing(store("a"), "T-1", "One", "en", OPEN);

        ResourceListing listing = service.list(PROJECT, "Thing", null, "en", false, 0);

        assertEquals(List.of("T-1", STORE + "old", STORE + "old2"), handles(listing));
        assertEquals(Optional.empty(), listing.resources().get(1).label());
        assertEquals("Thing", listing.resources().get(1).typeLabel());
    }

    @Test
    void aCompactListingStopsAtTheCapAndSaysWhereItContinues() {
        IntStream.rangeClosed(1, ListResources.COMPACT_LIMIT + 5)
                .forEach(number -> thing(store("t" + number), "T-" + number, "N" + number, "en", OPEN));

        ResourceListing first = service.list(PROJECT, "Thing", null, "en", false, 0);
        ResourceListing second = service.list(PROJECT, "Thing", null, "en", false,
                first.nextOffset().orElseThrow());

        assertEquals(ListResources.COMPACT_LIMIT, first.resources().size());
        assertEquals(ListResources.COMPACT_LIMIT + 5, first.total());
        assertEquals(Optional.of(ListResources.COMPACT_LIMIT), first.nextOffset());
        assertEquals(List.of("T-" + (ListResources.COMPACT_LIMIT + 1), "T-" + (ListResources.COMPACT_LIMIT + 2),
                "T-" + (ListResources.COMPACT_LIMIT + 3), "T-" + (ListResources.COMPACT_LIMIT + 4),
                "T-" + (ListResources.COMPACT_LIMIT + 5)), handles(second));
        assertEquals(Optional.empty(), second.nextOffset());
    }

    @Test
    void aFullListingRendersEachResourceLikeAReadAndStopsAtItsOwnCap() {
        IntStream.rangeClosed(1, ListResources.FULL_LIMIT + 3)
                .forEach(number -> thing(store("t" + number), "T-" + number, "N" + number, "en", OPEN));

        ResourceListing full = service.list(PROJECT, "Thing", null, "en", true, 0);

        assertEquals(ListResources.FULL_LIMIT, full.resources().size());
        assertEquals(full.resources().size(), full.readings().size());
        assertEquals(Optional.of(ListResources.FULL_LIMIT), full.nextOffset());
        ResourceReading.Found first = assertInstanceOf(ResourceReading.Found.class, full.readings().get(0));
        assertEquals("T-1", first.handle());
        assertEquals("N1", first.resource().label().orElseThrow().text());
        assertFalse(first.resource().fields().isEmpty());
        assertEquals(1, reader.reads, "the listing and its full readings share one read transaction");
    }

    @Test
    void aNegativeOffsetIsRejectedAndOneBeyondTheEndListsNothing() {
        thing(store("a"), "T-1", "One", "en", OPEN);

        assertThrows(IllegalArgumentException.class, () -> service.list(PROJECT, null, null, "en", false, -1));
        ResourceListing beyond = service.list(PROJECT, null, null, "en", false, 10);

        assertEquals(1, beyond.total());
        assertTrue(beyond.resources().isEmpty());
        assertEquals(Optional.empty(), beyond.nextOffset());
    }

    private List<String> handles(final ResourceListing listing) {
        return listing.resources().stream().map(ListedResource::handle).toList();
    }

    private void thing(final IRI iri, final String code, final String name, final String language, final IRI status) {
        reader.content.add(iri, VocabRdf.TYPE, THING);
        reader.content.add(iri, VocabDct.IDENTIFIER, RDF_FACTORY.createLiteral(code));
        reader.content.add(iri, NAME, RDF_FACTORY.createLiteral(name, language));
        reader.content.add(iri, STATUS, status);
    }

    private void gadget(final IRI iri, final String code, final String name) {
        reader.content.add(iri, VocabRdf.TYPE, GADGET);
        reader.content.add(iri, VocabDct.IDENTIFIER, RDF_FACTORY.createLiteral(code));
        reader.content.add(iri, NAME, RDF_FACTORY.createLiteral(name, "en"));
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
        for (IRI type : List.of(THING, GADGET, LINK)) {
            shapes.add(type, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        }
        shapes.add(THING, arkres(ArkresVocabulary.CODE_PREFIX), RDF_FACTORY.createLiteral("T-"));
        shapes.add(GADGET, arkres(ArkresVocabulary.CODE_PREFIX), RDF_FACTORY.createLiteral("G-"));
        IRI thing = shape("Thing");
        shapes.add(thing, sh("targetClass"), THING);
        property(shapes, thing, VocabDct.IDENTIFIER);
        IRI name = property(shapes, thing, NAME);
        shapes.add(name, sh("uniqueLang"), RDF_FACTORY.createLiteral("true", VocabXsd.BOOLEAN));
        shapes.add(name, arkres(ArkresVocabulary.LABEL_FIELD), RDF_FACTORY.createLiteral("true", VocabXsd.BOOLEAN));
        IRI status = property(shapes, thing, STATUS);
        shapes.add(status, arkres(ArkresVocabulary.INITIAL_STATUS), OPEN);
        BlankNode transition = RDF_FACTORY.createBlankNode();
        shapes.add(status, arkres(ArkresVocabulary.TRANSITION), transition);
        shapes.add(transition, arkres(ArkresVocabulary.FROM_STATUS), OPEN);
        shapes.add(transition, arkres(ArkresVocabulary.TO_STATUS), DONE);
        IRI link = shape("Link");
        shapes.add(link, sh("targetClass"), LINK);
        shapes.add(property(shapes, link, TARGET), sh("class"), THING);
        return shapes;
    }

    private static Graph ontology() {
        Graph ontology = RDF_FACTORY.createGraph();
        ontology.add(THING, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Thing", "en"));
        ontology.add(THING, SUB_CLASS_OF, BASE);
        ontology.add(BASE, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Base", "en"));
        ontology.add(GADGET, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Gadget", "en"));
        ontology.add(GADGET, SUB_CLASS_OF, THING);
        ontology.add(LINK, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Link", "en"));
        ontology.add(OPEN, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Open", "en"));
        ontology.add(DONE, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Done", "en"));
        return ontology;
    }

    private static IRI property(final Graph shapes, final IRI node, final IRI path) {
        IRI property = shape(node.getIRIString().substring(SHAPES.length()) + "-"
                + path.getIRIString().replaceAll(".*[#/]", ""));
        shapes.add(node, sh("property"), property);
        shapes.add(property, sh("path"), path);
        return property;
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
