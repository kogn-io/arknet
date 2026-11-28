// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.resource.application.port.in.FindText;
import de.hauschel.arknet.resource.application.port.in.TextHit;
import de.hauschel.arknet.resource.application.port.in.TextSearchResult;
import de.hauschel.arknet.resource.domain.ArkresVocabulary;
import de.hauschel.arknet.resource.domain.PrefixBindings;
import de.hauschel.arknet.resource.domain.ResourceSchema;

/**
 * The search works over types it has never seen in code: fixture shapes and ontology of an invented
 * namespace. A {@code Thing} has a code, a {@code Link} none; a {@code Note} is a child of a thing
 * and no resource type.
 */
class FindTextServiceTest {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String FX = "https://example.org/fixture#";
    private static final String SHAPES = "https://example.org/fixture-shapes#";
    private static final String STORE = "https://example.org/store/";
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final ProjectId PROJECT = new ProjectId("fixture");

    private static final IRI THING = fx("Thing");
    private static final IRI LINK = fx("Link");
    private static final IRI NOTE = fx("Note");
    private static final IRI NAME = fx("name");
    private static final IRI HAS_NOTE = fx("hasNote");
    private static final IRI NOTE_TEXT = fx("noteText");

    private final FakeReader reader = new FakeReader();
    private final ReadService service = service();

    @Test
    void aMatchOnAResourceIsReportedUnderItsCodeWithFieldLanguageAndSnippet() {
        thing(store("a"), "T-1", "Login form", "en");

        TextSearchResult result = service.find(PROJECT, "LOGIN", null);

        assertEquals(1, result.total());
        assertEquals(new TextHit("T-1", "fx:name", Optional.empty(), Optional.of("en"), "Login form"),
                result.hits().get(0));
        assertEquals(0, result.notShown());
    }

    @Test
    void severalMatchesOfOneResourceCarryTheSameHandleInAStableOrder() {
        thing(store("a"), "T-1", "Login form", "en");
        reader.content.add(store("a"), fx("rationale"), RDF_FACTORY.createLiteral("Users must log in", "en"));

        TextSearchResult result = service.find(PROJECT, "log", null);

        assertEquals(List.of("T-1", "T-1"), result.hits().stream().map(TextHit::handle).toList());
        assertEquals(List.of("fx:name", "fx:rationale"), result.hits().stream().map(TextHit::field).toList());
    }

    @Test
    void aMatchInAFragmentChildIsReportedUnderTheOwningResourceWithTheEdgeItWasReachedThrough() {
        thing(store("a"), "T-1", "Order", "en");
        IRI child = store("a#note-1");
        reader.content.add(store("a"), HAS_NOTE, child);
        reader.content.add(child, VocabRdf.TYPE, NOTE);
        reader.content.add(child, NOTE_TEXT, RDF_FACTORY.createLiteral("Check the zzzcredential", "en"));

        TextSearchResult result = service.find(PROJECT, "zzzcredential", null);

        assertEquals(List.of(new TextHit("T-1", "fx:noteText", Optional.of("fx:hasNote"), Optional.of("en"),
                "Check the zzzcredential")), result.hits());
    }

    @Test
    void aMatchOnABlankNodeValueIsReportedUnderTheResourceHoldingIt() {
        thing(store("a"), "T-1", "Order", "en");
        BlankNode held = RDF_FACTORY.createBlankNode();
        reader.content.add(store("a"), HAS_NOTE, held);
        reader.content.add(held, NOTE_TEXT, RDF_FACTORY.createLiteral("zzzblank"));

        TextSearchResult result = service.find(PROJECT, "zzzblank", null);

        assertEquals(List.of(new TextHit("T-1", "fx:noteText", Optional.of("fx:hasNote"), Optional.empty(),
                "zzzblank")), result.hits());
    }

    @Test
    void aNodeOfAnEarlierLayoutWithoutTypeOrCodeIsClimbedToTheNodePointingAtIt() {
        thing(store("a"), "T-1", "Order", "en");
        IRI legacyStep = store("step-9");
        reader.content.add(store("a"), HAS_NOTE, legacyStep);
        reader.content.add(legacyStep, NOTE_TEXT, RDF_FACTORY.createLiteral("zzzlegacy"));

        TextSearchResult result = service.find(PROJECT, "zzzlegacy", null);

        assertEquals("T-1", result.hits().get(0).handle());
        assertEquals(Optional.of("fx:hasNote"), result.hits().get(0).viaField());
    }

    @Test
    void aNodeNothingOwnsIsReportedUnderItselfInsteadOfFailing() {
        BlankNode orphan = RDF_FACTORY.createBlankNode();
        reader.content.add(orphan, NOTE_TEXT, RDF_FACTORY.createLiteral("zzzorphan"));
        reader.content.add(store("loose"), NOTE_TEXT, RDF_FACTORY.createLiteral("zzzorphan too"));

        TextSearchResult result = service.find(PROJECT, "zzzorphan", null);

        assertEquals(2, result.total());
        assertEquals(List.of(STORE + "loose"), result.hits().stream().map(TextHit::handle)
                .filter(handle -> !handle.startsWith("_:")).toList());
        assertTrue(result.hits().stream().anyMatch(hit -> hit.handle().startsWith("_:")));
    }

    @Test
    void aCodeMoreThanOneResourceCarriesGivesWayToTheIriBecauseResourceGetRejectsIt() {
        thing(store("a"), "T-1", "Twin one", "en");
        thing(store("b"), "T-1", "Twin two", "en");
        thing(store("c"), "T-2", "Twin three", "en");

        TextSearchResult result = service.find(PROJECT, "twin", null);

        assertEquals(List.of(STORE + "a", STORE + "b", "T-2"), result.hits().stream().map(TextHit::handle).toList());
    }

    @Test
    void aTypeKeepsOnlyTheMatchesOfThatTypeAndCountsOnlyThose() {
        thing(store("a"), "T-1", "Shared word", "en");
        reader.content.add(store("l"), VocabRdf.TYPE, LINK);
        reader.content.add(store("l"), NAME, RDF_FACTORY.createLiteral("Shared word"));
        IRI child = store("a#note-1");
        reader.content.add(store("a"), HAS_NOTE, child);
        reader.content.add(child, NOTE_TEXT, RDF_FACTORY.createLiteral("shared too"));

        TextSearchResult all = service.find(PROJECT, "shared", null);
        TextSearchResult byLabel = service.find(PROJECT, "shared", "Thing");
        TextSearchResult byName = service.find(PROJECT, "shared", "fx:Link");

        assertEquals(3, all.total());
        assertEquals(2, byLabel.total());
        assertEquals(List.of("T-1", "T-1"), byLabel.hits().stream().map(TextHit::handle).toList());
        assertEquals(List.of(STORE + "l"), byName.hits().stream().map(TextHit::handle).toList());
    }

    @Test
    void anUnknownTypeIsRejectedWithTheTypesThereAre() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> service.find(PROJECT, "x", "Nothing"));

        assertTrue(unknown.getMessage().contains("fx:Thing"), unknown.getMessage());
    }

    @Test
    void aBlankQueryIsRejectedWithAHint() {
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> service.find(PROJECT, "   ", null));

        assertTrue(blank.getMessage().contains("must not be blank"), blank.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.find(PROJECT, null, null));
    }

    @Test
    void noMatchIsAnEmptyResultNotAnError() {
        thing(store("a"), "T-1", "Order", "en");

        TextSearchResult result = service.find(PROJECT, "nothing-matches-this", null);

        assertEquals(0, result.total());
        assertTrue(result.hits().isEmpty());
    }

    @Test
    void moreMatchesThanTheLimitAreCountedNotCutSilently() {
        IntStream.rangeClosed(1, FindText.LIMIT + 5)
                .forEach(number -> thing(store("t" + number), "T-" + number, "common " + number, "en"));

        TextSearchResult result = service.find(PROJECT, "common", null);

        assertEquals(FindText.LIMIT + 5, result.total());
        assertEquals(FindText.LIMIT, result.hits().size());
        assertEquals(5, result.notShown());
        assertEquals(1, reader.reads, "one read transaction");
    }

    private void thing(final IRI iri, final String code, final String name, final String language) {
        reader.content.add(iri, VocabRdf.TYPE, THING);
        reader.content.add(iri, VocabDct.IDENTIFIER, RDF_FACTORY.createLiteral(code));
        reader.content.add(iri, NAME, RDF_FACTORY.createLiteral(name, language));
    }

    private ReadService service() {
        Graph shapes = RDF_FACTORY.createGraph();
        shapes.add(THING, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        shapes.add(LINK, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        shapes.add(THING, arkres(ArkresVocabulary.CODE_PREFIX), RDF_FACTORY.createLiteral("T-"));
        IRI thing = RDF_FACTORY.createIRI(SHAPES + "Thing");
        shapes.add(thing, RDF_FACTORY.createIRI(SHACL + "targetClass"), THING);
        Graph ontology = RDF_FACTORY.createGraph();
        ontology.add(THING, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Thing", "en"));
        ontology.add(LINK, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Link", "en"));
        ontology.add(NOTE, VocabRdfs.LABEL, RDF_FACTORY.createLiteral("Note", "en"));
        ResourceSchema schema = ResourceSchema.of(shapes, ontology);
        PrefixBindings prefixes = new PrefixBindings(Map.of("fx", FX, "dcterms", "http://purl.org/dc/terms/"));
        DescribeService describer = new DescribeService(shapes, ontology, schema, prefixes, DisplayLocale.DEFAULT);
        return new ReadService(reader, schema, ontology, prefixes, describer, DisplayLocale.DEFAULT);
    }

    private static IRI fx(final String localName) {
        return RDF_FACTORY.createIRI(FX + localName);
    }

    private static IRI store(final String localName) {
        return RDF_FACTORY.createIRI(STORE + localName);
    }

    private static IRI arkres(final String iri) {
        return RDF_FACTORY.createIRI(iri);
    }
}
