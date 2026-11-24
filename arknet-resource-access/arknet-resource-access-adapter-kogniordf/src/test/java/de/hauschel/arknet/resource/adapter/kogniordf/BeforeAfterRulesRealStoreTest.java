// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.resource.application.DescribeService;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.ChangeExample;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeDescription;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;
import de.hauschel.arknet.resource.domain.PrefixBindings;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The rules comparing a resource before and after a document against a real store and real shape
 * evaluation, over fixture rule data of a paper type that belongs to no bounded context: a field
 * group of title, abstract and the owned sections that only grows once the paper is published, an
 * immutable acronym and category, and the publication day the publishing transition writes unless
 * the document does, which republishing keeps. The day comes from a fixed clock.
 */
class BeforeAfterRulesRealStoreTest {

    static final ProjectId PROJECT = new ProjectId("test-project");
    static final String FX = "https://example.org/papers#";
    static final String PREFIXES = "@prefix arkres: <https://w3id.org/arknet/resource#> .\n"
            + "@prefix fx: <" + FX + "> .\n@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);

    static final String SHAPES = """
            @prefix arkres: <https://w3id.org/arknet/resource#> .
            @prefix fx:     <https://example.org/papers#> .
            @prefix fxs:    <https://example.org/papers/shapes#> .
            @prefix sh:     <http://www.w3.org/ns/shacl#> .
            @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .
            @prefix rdf:    <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

            fx:Paper a arkres:ResourceType ; arkres:codePrefix "PA-" .

            fxs:Paper a sh:NodeShape ;
                sh:targetClass fx:Paper ;
                sh:property [ sh:path fx:title ; sh:datatype rdf:langString ; sh:minCount 1 ; sh:uniqueLang true ] ,
                            [ sh:path fx:abstract ; sh:datatype rdf:langString ; sh:uniqueLang true ] ,
                            [ sh:path fx:acronym ; sh:datatype rdf:langString ; sh:minCount 1 ;
                              arkres:immutable true ] ,
                            [ sh:path fx:category ; sh:in ( fx:Essay fx:Review ) ; arkres:immutable true ] ,
                            [ sh:path fx:publishedOn ; sh:datatype xsd:date ; sh:maxCount 1 ] ,
                            [ sh:path fx:section ; sh:class fx:Section ;
                              arkres:childKind "Section" ; arkres:orderedBy fx:position ] ,
                            fxs:Paper-state .

            fxs:Section a sh:NodeShape ;
                sh:targetClass fx:Section ;
                sh:property [ sh:path fx:heading ; sh:datatype rdf:langString ; sh:minCount 1 ] ,
                            [ sh:path fx:position ; sh:datatype xsd:integer ; sh:minCount 1 ; sh:maxCount 1 ] .

            fxs:Paper-state a sh:PropertyShape ;
                sh:path fx:state ;
                sh:minCount 1 ;
                sh:maxCount 1 ;
                sh:in ( fx:Draft fx:Published fx:Withdrawn ) ;
                arkres:initialStatus fx:Draft ;
                arkres:fieldGroup [ arkres:groupField fx:title , fx:abstract , fx:section ;
                                    arkres:editableIn fx:Draft ] ;
                arkres:transition [ arkres:fromStatus fx:Draft ; arkres:toStatus fx:Published ;
                                    arkres:condition fxs:Dated ;
                                    arkres:default [ arkres:defaultField fx:publishedOn ;
                                                     arkres:defaultValue arkres:today ] ] ,
                                  [ arkres:fromStatus fx:Published ; arkres:toStatus fx:Withdrawn ] ,
                                  [ arkres:fromStatus fx:Withdrawn ; arkres:toStatus fx:Published ] .

            fxs:Dated a sh:NodeShape ;
                sh:property [ sh:path fx:publishedOn ; sh:minCount 1 ;
                    sh:message "A published paper carries its publication day."@en ] .
            """;

    static final String ONTOLOGY = """
            @prefix fx:   <https://example.org/papers#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            fx:Paper a owl:Class .
            fx:Section a owl:Class .
            """;

    private static final PrefixBindings BINDINGS = new PrefixBindings(Map.of("arkres",
            "https://w3id.org/arknet/resource#", "fx", FX, "xsd", "http://www.w3.org/2001/XMLSchema#"));

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private StoreReader reader;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        reader = new StoreReader(lifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void aDraftChangesItsGroupFreely() {
        // given
        String paper = paper();

        // when
        submit("[] a arkres:Update ; arkres:object <" + paper + "> ; arkres:set [ fx:title \"Rewritten\"@en ] .\n");

        // then
        assertEquals(Set.of("Rewritten", "Titel"), field(paper, FX + "title"));
    }

    @Test
    void aPublishedPaperTakesOnlyALanguageNewToTheWholeGroup() {
        // given
        String paper = published(paper());

        // when
        SubmitRejectedException rewritten = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + paper + "> ; arkres:set [ fx:title \"Rewritten\"@en ] .\n"));
        SubmitRejectedException overlapping = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + paper + "> ;"
                        + " arkres:set [ fx:title \"Titel\"@de ; fx:abstract \"Zusammenfassung\"@de ] .\n"));
        submit("[] a arkres:Update ; arkres:object <" + paper + "> ;"
                + " arkres:set [ fx:title \"Titre\"@fr ; fx:abstract \"Resume\"@fr ] .\n");

        // then
        assertTrue(rewritten.getMessage().contains("https://w3id.org/arknet/resource#fieldGroup"),
                rewritten.getMessage());
        assertTrue(overlapping.getMessage().contains("[de, en]"), overlapping.getMessage());
        assertEquals(Set.of("Title", "Titel", "Titre"), field(paper, FX + "title"));
    }

    @Test
    void aPublishedPaperKeepsItsSectionsAndTakesNewOnesAfterTheLast() {
        // given
        String paper = published(paper());

        // when
        SubmitRejectedException removed = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:RemoveFrom ; arkres:object <" + paper + "> ; arkres:field fx:section ;"
                        + " arkres:position 1 .\n"));
        SubmitRejectedException inserted = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:AddTo ; arkres:object <" + paper + "> ; arkres:field fx:section ;"
                        + " arkres:value [ fx:heading \"Preface\"@en ; fx:position 1 ] .\n"));
        submit("[] a arkres:AddTo ; arkres:object <" + paper + "> ; arkres:field fx:section ;"
                + " arkres:value [ fx:heading \"Appendix\"@en ] .\n");

        // then
        assertTrue(removed.getMessage().contains("removes the child"), removed.getMessage());
        assertTrue(inserted.getMessage().contains(FX + "position"), inserted.getMessage());
        assertEquals(2, field(paper, FX + "section").size());
    }

    @Test
    void anImmutableFieldKeepsWhatTheCreateWroteInEveryStatus() {
        // given
        String paper = paper();

        // when
        SubmitRejectedException recategorised = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + paper + "> ; arkres:set [ fx:category fx:Review ] .\n"));
        SubmitRejectedException translated = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + paper + "> ; arkres:set [ fx:acronym \"PP\"@de ] .\n"));

        // then
        assertTrue(recategorised.getMessage().contains("https://w3id.org/arknet/resource#immutable"),
                recategorised.getMessage());
        assertTrue(translated.getMessage().contains(FX + "acronym"), translated.getMessage());
        assertEquals(Set.of(FX + "Essay"), field(paper, FX + "category"));
    }

    @Test
    void publishingWritesTodayUnlessTheDocumentWritesTheDayAndRepublishingKeepsIt() {
        // given
        String stamped = paper();
        String dated = paper();

        // when
        published(stamped);
        submit("[] a arkres:Update ; arkres:object <" + dated + "> ;"
                + " arkres:set [ fx:state fx:Published ; fx:publishedOn \"2026-01-15\"^^xsd:date ] .\n");
        submit("[] a arkres:Update ; arkres:object <" + dated + "> ; arkres:set [ fx:state fx:Withdrawn ] .\n");
        submit("[] a arkres:Update ; arkres:object <" + dated + "> ; arkres:set [ fx:state fx:Published ] .\n");

        // then
        assertEquals(Set.of("2026-11-24"), field(stamped, FX + "publishedOn"));
        assertEquals(Set.of("2026-01-15"), field(dated, FX + "publishedOn"));
    }

    @Test
    void theDescribedChangeExamplesPassAnImmutableFieldBy() {
        // given
        Graph shapes = turtle(SHAPES);
        Graph ontology = turtle(ONTOLOGY);
        DescribeService describer = new DescribeService(shapes, ontology, ResourceSchema.of(shapes, ontology),
                BINDINGS, DisplayLocale.DEFAULT);
        ResourceTypeDescription description = describer.describe("fx:Paper", "en");
        String paper = created(submit(description.example()));
        String code = reader.outgoing(PROJECT, paper).stream()
                .filter(triple -> triple.predicate().equals("http://purl.org/dc/terms/identifier"))
                .map(triple -> ((RdfNode.Literal) triple.object()).lexicalForm()).findFirst().orElseThrow();

        // when
        List<String> examples = description.changeExamples().stream().map(ChangeExample::document).toList();

        // then
        assertFalse(examples.get(0).contains("fx:acronym"), examples.get(0));
        for (String example : examples) {
            assertDoesNotThrow(() -> submit(example.replace(ChangeExample.PLACEHOLDER, "<" + code + ">")), example);
        }
    }

    private String paper() {
        return created(submit("[] a arkres:Create ; arkres:object _:p .\n_:p a fx:Paper ; fx:title \"Title\"@en ,"
                + " \"Titel\"@de ; fx:abstract \"Abstract\"@en ; fx:acronym \"PP\"@en ; fx:category fx:Essay ;"
                + " fx:section [ fx:heading \"Introduction\"@en ; fx:position 1 ] .\n"));
    }

    private String published(final String paper) {
        submit("[] a arkres:Update ; arkres:object <" + paper + "> ; arkres:set [ fx:state fx:Published ] .\n");
        return paper;
    }

    private SubmitResult submit(final String document) {
        return service(lifecycle, repository(lifecycle)).submit(PROJECT, PREFIXES + document);
    }

    /** The engine over the paper fixture's rule data, the given store side and the fixed clock. */
    static SubmitService service(final DatasetLifecycle lifecycle, final ResourceRepository repository) {
        return new SubmitService(ResourceSchema.of(turtle(SHAPES), turtle(ONTOLOGY)), repository,
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(), BINDINGS,
                KognioRdfResourceRepositoryFactory.handles(lifecycle), CLOCK);
    }

    /** The store side over the paper fixture's rule data. */
    static ResourceRepository repository(final DatasetLifecycle lifecycle) {
        return KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT, turtle(SHAPES),
                turtle(ONTOLOGY));
    }

    static String created(final SubmitResult result) {
        return result.changes().stream().filter(change -> change.kind() == SubmittedChange.Kind.CREATED)
                .findFirst().orElseThrow().iri();
    }

    private Set<String> field(final String iri, final String property) {
        return reader.outgoing(PROJECT, iri).stream()
                .filter(triple -> triple.predicate().equals(property))
                .map(triple -> switch (triple.object()) {
                    case RdfNode.Resource resource -> resource.iri();
                    case RdfNode.Literal literal -> literal.lexicalForm();
                })
                .collect(Collectors.toSet());
    }

    static Graph turtle(final String text) {
        return KognioRdfResourceRepositoryFactory.parser()
                .readTurtle(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
