// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
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
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;
import de.hauschel.arknet.resource.domain.PrefixBindings;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The status mechanics against a real store and real shape evaluation, over fixture rule data of a
 * ticket type that belongs to no bounded context: the initial status of a create, transitions with
 * conditions on the changed resource and, through {@code sh:node}, on the target of one of its
 * edges, checked in the state after the document and only at the transition; the same status as no
 * change; deletion only in a deletable status; a type without status rule data untouched.
 */
class StatusTransitionsRealStoreTest {

    static final ProjectId PROJECT = new ProjectId("test-project");
    static final String FX = "https://example.org/tickets#";
    private static final String FXS = "https://example.org/tickets/shapes#";
    static final String PREFIXES = "@prefix arkres: <https://w3id.org/arknet/resource#> .\n"
            + "@prefix fx: <" + FX + "> .\n";

    static final String SHAPES = """
            @prefix arkres: <https://w3id.org/arknet/resource#> .
            @prefix fx:     <https://example.org/tickets#> .
            @prefix fxs:    <https://example.org/tickets/shapes#> .
            @prefix sh:     <http://www.w3.org/ns/shacl#> .
            @prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .

            fx:Ticket a arkres:ResourceType ; arkres:codePrefix "TK-" .
            fx:Memo a arkres:ResourceType ; arkres:codePrefix "MM-" .

            fxs:Ticket a sh:NodeShape ;
                sh:targetClass fx:Ticket ;
                sh:property [ sh:path fx:title ; sh:datatype xsd:string ; sh:minCount 1 ] ,
                            [ sh:path fx:note ; sh:datatype xsd:string ] ,
                            [ sh:path fx:replacedBy ; sh:class fx:Ticket ] ,
                            fxs:Ticket-state .

            fxs:Ticket-state a sh:PropertyShape ;
                sh:path fx:state ;
                sh:minCount 1 ;
                sh:maxCount 1 ;
                sh:in ( fx:Done fx:Open fx:Closed ) ;
                arkres:initialStatus fx:Open ;
                arkres:deletableIn fx:Open , fx:Done ;
                arkres:transition [ arkres:fromStatus fx:Open ; arkres:toStatus fx:Done ;
                                    arkres:condition fxs:HasNote ] ,
                                  [ arkres:fromStatus fx:Done ; arkres:toStatus fx:Open ] ,
                                  [ arkres:fromStatus fx:Done ; arkres:toStatus fx:Closed ;
                                    arkres:condition fxs:ReplacedByDoneTicket ] .

            fxs:HasNote a sh:NodeShape ;
                sh:property [ sh:path fx:note ; sh:minCount 1 ;
                    sh:message "A ticket is done only with a note."@en ,
                               "Ein Ticket ist nur mit Notiz erledigt."@de ] .

            fxs:ReplacedByDoneTicket a sh:NodeShape ;
                sh:property [ sh:path fx:replacedBy ; sh:minCount 1 ; sh:node fxs:DoneTicket ;
                    sh:message "A ticket is closed only when a done ticket replaces it."@en ,
                               "Ein Ticket wird nur geschlossen, wenn ein erledigtes Ticket es ersetzt."@de ] .

            fxs:DoneTicket a sh:NodeShape ;
                sh:property [ sh:path fx:state ; sh:hasValue fx:Done ] .

            fxs:Memo a sh:NodeShape ;
                sh:targetClass fx:Memo ;
                sh:property [ sh:path fx:title ] , [ sh:path fx:state ] .
            """;

    static final String ONTOLOGY = """
            @prefix fx:   <https://example.org/tickets#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            fx:Ticket a owl:Class .
            fx:Memo a owl:Class .
            """;

    private static final PrefixBindings BINDINGS = new PrefixBindings(
            Map.of("arkres", "https://w3id.org/arknet/resource#", "fx", FX));

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
    void aCreatedTicketStartsInTheInitialStatus() {
        // when
        String ticket = ticket("\"First\"", "");

        // then
        assertEquals(Set.of(FX + "Open"), field(ticket, FX + "state"));
    }

    @Test
    void aCreateInAnotherStatusIsRejected() {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> ticket("\"First\"", "fx:state fx:Done"));

        // then
        assertTrue(rejected.getMessage().contains(FX + "Open"), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "TK-1").isEmpty());
    }

    @Test
    void aTransitionWhoseConditionHoldsAfterTheDocumentIsTaken() {
        // given
        String ticket = ticket("\"First\"", "");

        // when the note arrives in the same document as the status
        submit("[] a arkres:Update ; arkres:object <" + ticket + "> ;"
                + " arkres:set [ fx:state fx:Done ; fx:note \"Fixed.\" ] .\n");

        // then
        assertEquals(Set.of(FX + "Done"), field(ticket, FX + "state"));
    }

    @Test
    void aTransitionWhoseConditionFailsIsRejectedWithTheShapesMessageInTheDisplayLanguage() {
        // given
        String ticket = ticket("\"First\"", "");

        // when
        SubmitRejectedException english = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + ticket + "> ; arkres:set [ fx:state fx:Done ] .\n"));
        SubmitRejectedException german = assertThrows(SubmitRejectedException.class, () -> service(
                new DisplayLocale(Locale.GERMAN, Locale.ENGLISH)).submit(PROJECT, PREFIXES
                        + "[] a arkres:Update ; arkres:object <" + ticket + "> ; arkres:set [ fx:state fx:Done ] .\n"));

        // then
        assertTrue(english.getMessage().contains(FXS + "HasNote"), english.getMessage());
        assertTrue(english.getMessage().contains("A ticket is done only with a note."), english.getMessage());
        assertTrue(german.getMessage().contains("Ein Ticket ist nur mit Notiz erledigt."), german.getMessage());
        assertEquals(Set.of(FX + "Open"), field(ticket, FX + "state"));
    }

    @Test
    void aStatusChangeWithoutTransitionIsRejected() {
        // given
        String ticket = ticket("\"First\"", "");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + ticket + "> ; arkres:set [ fx:state fx:Closed ] .\n"));

        // then
        assertTrue(rejected.getMessage().contains(FX + "Closed"), rejected.getMessage());
        assertEquals(Set.of(FX + "Open"), field(ticket, FX + "state"));
    }

    @Test
    void aConditionOnAnEdgeTargetReadsTheTargetAfterTheDocument() {
        // given
        String old = doneTicket("\"Old\"");
        String replacement = ticket("\"New\"", "");
        submit("[] a arkres:Update ; arkres:object <" + old + "> ; arkres:set [ fx:replacedBy <" + replacement
                + "> ] .\n");

        // when the replacement is still open
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit(
                "[] a arkres:Update ; arkres:object <" + old + "> ; arkres:set [ fx:state fx:Closed ] .\n"));

        // then
        assertTrue(rejected.getMessage().contains("A ticket is closed only when a done ticket replaces it."),
                rejected.getMessage());

        // when the replacement is done in the same document
        submit("[] a arkres:Update ; arkres:object <" + old + "> ; arkres:set [ fx:state fx:Closed ] .\n"
                + "[] a arkres:Update ; arkres:object <" + replacement + "> ;"
                + " arkres:set [ fx:state fx:Done ; fx:note \"Done.\" ] .\n");

        // then
        assertEquals(Set.of(FX + "Closed"), field(old, FX + "state"));
    }

    @Test
    void aConditionIsCheckedOnlyAtTheTransitionNotOnLaterWritesNorOnReferringResources() {
        // given a done ticket, and an open one referring to it
        String done = doneTicket("\"Done\"");
        String referrer = ticket("\"Referrer\"", "fx:replacedBy <" + done + ">");

        // when the done ticket loses the note its transition required, and the referrer changes
        assertDoesNotThrow(() -> submit("[] a arkres:Update ; arkres:object <" + done + "> ;"
                + " arkres:clear fx:note .\n"));
        assertDoesNotThrow(() -> submit("[] a arkres:Update ; arkres:object <" + referrer + "> ;"
                + " arkres:set [ fx:title \"Renamed\" ] .\n"));

        // then
        assertEquals(Set.of(FX + "Done"), field(done, FX + "state"));
        assertEquals(Set.of("Renamed"), field(referrer, FX + "title"));
    }

    @Test
    void writingTheCurrentStatusAgainIsNoChange() {
        // given a ticket in a status with no outgoing transition to itself and an unmet condition
        String done = doneTicket("\"Done\"");
        submit("[] a arkres:Update ; arkres:object <" + done + "> ; arkres:clear fx:note .\n");

        // when
        assertDoesNotThrow(() -> submit("[] a arkres:Update ; arkres:object <" + done + "> ;"
                + " arkres:set [ fx:state fx:Done ; fx:title \"Again\" ] .\n"));

        // then
        assertEquals(Set.of("Again"), field(done, FX + "title"));
    }

    @Test
    void aDeleteOutsideTheDeletableStatusesIsRejected() {
        // given
        String old = doneTicket("\"Old\"");
        String replacement = doneTicket("\"New\"");
        submit("[] a arkres:Update ; arkres:object <" + old + "> ; arkres:set [ fx:replacedBy <" + replacement
                + "> ; fx:state fx:Closed ] .\n");
        String deletable = ticket("\"Deletable\"", "");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> submit("[] a arkres:Delete ; arkres:object <" + old + "> .\n"));
        SubmitResult accepted = submit("[] a arkres:Delete ; arkres:object <" + deletable + "> .\n");

        // then
        assertTrue(rejected.getMessage().contains(FX + "Closed"), rejected.getMessage());
        assertEquals(1, reader.findByIdentifier(PROJECT, "TK-1").size());
        assertEquals(SubmittedChange.Kind.DELETED, accepted.changes().get(0).kind());
    }

    @Test
    void aTypeWithoutStatusRuleDataTakesAnyValueAndAnyChange() {
        // given
        String memo = created(submit("[] a arkres:Create ; arkres:object _:memo .\n"
                + "_:memo a fx:Memo ; fx:title \"Memo\" ; fx:state fx:Closed .\n"));

        // when
        submit("[] a arkres:Update ; arkres:object <" + memo + "> ; arkres:set [ fx:state fx:Open ] .\n");
        submit("[] a arkres:Delete ; arkres:object <" + memo + "> .\n");

        // then
        assertTrue(reader.findByIdentifier(PROJECT, "MM-1").isEmpty());
    }

    @Test
    void theDescribedExampleWritesTheInitialStatusNotTheFirstAllowedValue() {
        // given
        Graph shapes = turtle(SHAPES);
        Graph ontology = turtle(ONTOLOGY);
        DescribeService describer = new DescribeService(shapes, ontology, ResourceSchema.of(shapes, ontology),
                BINDINGS, DisplayLocale.DEFAULT);

        // when
        String example = describer.describe("fx:Ticket", "en").example();

        // then
        assertTrue(example.contains("fx:state fx:Open"), example);
        String ticket = created(service(DisplayLocale.DEFAULT).submit(PROJECT, example));
        assertEquals(Set.of(FX + "Open"), field(ticket, FX + "state"));
    }

    private String ticket(final String title, final String more) {
        return created(submit("[] a arkres:Create ; arkres:object _:t .\n_:t a fx:Ticket ; fx:title " + title
                + (more.isEmpty() ? "" : " ; " + more) + " .\n"));
    }

    private String doneTicket(final String title) {
        String ticket = ticket(title, "");
        submit("[] a arkres:Update ; arkres:object <" + ticket + "> ;"
                + " arkres:set [ fx:state fx:Done ; fx:note \"Fixed.\" ] .\n");
        return ticket;
    }

    private SubmitResult submit(final String document) {
        return service(DisplayLocale.DEFAULT).submit(PROJECT, PREFIXES + document);
    }

    private SubmitService service(final DisplayLocale displayLocale) {
        return service(lifecycle, KognioRdfResourceRepositoryFactory.over(lifecycle, displayLocale, turtle(SHAPES),
                turtle(ONTOLOGY)));
    }

    /** The engine over the ticket fixture's rule data and the given store side. */
    static SubmitService service(final DatasetLifecycle lifecycle, final ResourceRepository repository) {
        return new SubmitService(ResourceSchema.of(turtle(SHAPES), turtle(ONTOLOGY)), repository,
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(), BINDINGS,
                KognioRdfResourceRepositoryFactory.handles(lifecycle));
    }

    /** The store side over the ticket fixture's rule data. */
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
