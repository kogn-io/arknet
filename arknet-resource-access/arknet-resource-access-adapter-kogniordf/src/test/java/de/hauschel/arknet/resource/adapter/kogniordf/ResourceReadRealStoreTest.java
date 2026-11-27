// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.DescribeResourceType;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.Kind;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.Presence;
import de.hauschel.arknet.resource.application.port.in.HistoryReading;
import de.hauschel.arknet.resource.application.port.in.ReadResources;
import de.hauschel.arknet.resource.application.port.in.RenderedField;
import de.hauschel.arknet.resource.application.port.in.RenderedResource;
import de.hauschel.arknet.resource.application.port.in.RenderedValue;
import de.hauschel.arknet.resource.application.port.in.ResourceHistory;
import de.hauschel.arknet.resource.application.port.in.ResourceReading;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeDescription;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;

/**
 * The generic read against the shipped shapes and a real store: every resource type reads back what
 * its {@code describe} example wrote, and the reader port answers each of its reads in one
 * transaction of the real store.
 */
class ResourceReadRealStoreTest {

    private static final DescribeResourceType DESCRIBER =
            KognioRdfResourceRepositoryFactory.describer(DisplayLocale.DEFAULT);
    private static final ResourceSchema SCHEMA = KognioRdfResourceRepositoryFactory.schema();
    private static final SimpleRdf RDF = new SimpleRdf();
    private static final String ARKARCH = "https://w3id.org/arknet/architecture#";
    private static final String ADR_CLASS = ARKARCH + "ArchitectureDecisionRecord";
    private static final String CONSEQUENCE = ARKARCH + "consequence";
    private static final String RELATED_TO = ARKARCH + "relatedTo";

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private SubmitService submit;
    private ReadResources read;
    private ResourceHistory history;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        submit = new SubmitService(SCHEMA, KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                Clock.systemDefaultZone());
        read = KognioRdfResourceRepositoryFactory.readResources(lifecycle, DisplayLocale.DEFAULT);
        history = KognioRdfResourceRepositoryFactory.resourceHistory(lifecycle, DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    static Stream<String> resourceTypes() {
        return DESCRIBER.catalog(null).types().stream().map(type -> type.name());
    }

    @ParameterizedTest
    @MethodSource("resourceTypes")
    void everyTypeReadsBackWhatItsExampleWrote(final String type) {
        // given
        ResourceTypeDescription description = DESCRIBER.describe(type, "en");
        Optional<String> codePrefix = description.summary().codePrefix();
        SubmittedChange created = submit.submit(SubmitRealStoreTest.PROJECT, description.example()).changes().stream()
                .filter(change -> codePrefix.map(prefix -> change.code() != null && change.code().startsWith(prefix))
                        .orElse(change.code() == null))
                .findFirst().orElseThrow();
        String handle = created.code() != null ? created.code() : created.iri();

        // when
        RenderedResource resource = found(handle);

        // then
        assertEquals(handle, resource.handle());
        assertEquals(description.summary().label(), resource.typeLabel());
        IRI typeIri = new SimpleRdf().createIRI(description.summary().iri());
        assertEquals(SCHEMA.labelField(Set.of(typeIri)).isPresent(), resource.label().isPresent(),
                resource.toString());
        if (resource.label().isEmpty()) {
            assertEquals(resource.typeLabel() + " " + handle, resource.displayLabel());
        }
        assertTrue(resource.created().isPresent(), "every write publishes");
        description.fields().stream().filter(field -> field.kind() == Kind.CHILDREN).forEach(owned -> assertTrue(
                values(resource.fields(), owned.iri()).stream().allMatch(RenderedValue.Child.class::isInstance),
                owned.name()));
        assertTrue(resource.fields().stream().map(RenderedField::iri).toList()
                .containsAll(description.fields().stream()
                        .filter(field -> field.presence() == Presence.REQUIRED)
                        .map(field -> field.iri())
                        .toList()),
                "every required field the example wrote is rendered");
    }

    @Test
    void childrenComeInOrderAndASymmetricEdgeIsReadFromBothEnds() {
        // given
        submit.submit(SubmitRealStoreTest.PROJECT, adr("\"Use RDF\"@en",
                "arkarch:consequence " + consequence(2, "Second.") + " , " + consequence(1, "First.")));
        submit.submit(SubmitRealStoreTest.PROJECT, adr("\"Use SHACL\"@en",
                "arkarch:consequence " + consequence(1, "Only.") + " ; arkarch:relatedTo <ADR-1>"));

        // when
        RenderedResource first = found("ADR-1");

        // then
        assertEquals("Use RDF", first.displayLabel());
        List<RenderedValue> consequences = values(first.fields(), CONSEQUENCE);
        assertEquals(List.of(Optional.of(1), Optional.of(2)),
                consequences.stream().map(child -> ((RenderedValue.Child) child).position()).toList());
        RenderedValue.Reference related = (RenderedValue.Reference) values(first.fields(), RELATED_TO).get(0);
        assertEquals("ADR-2", related.handle());
        assertEquals("Use SHACL", related.label());
        assertTrue(first.incoming().stream().noneMatch(field -> field.iri().equals(RELATED_TO)));
    }

    @Test
    void aDeletedResourceAnswersWithItsDeletionAndHistory() {
        // given
        submit.submit(SubmitRealStoreTest.PROJECT, adr("\"Use RDF\"@en", "arkarch:consequence "
                + consequence(1, "Only.")));
        submit.submit(SubmitRealStoreTest.PROJECT, "[] a arkres:Delete ; arkres:object <ADR-1> .");

        // when
        ResourceReading reading = read.read(SubmitRealStoreTest.PROJECT, List.of("ADR-1"), "en", false).get(0);
        HistoryReading entries = history.history(SubmitRealStoreTest.PROJECT, "ADR-1", "en");

        // then
        ResourceReading.Deleted deleted = assertInstanceOf(ResourceReading.Deleted.class, reading);
        assertTrue(deleted.deletedAt().isPresent());
        assertEquals(Optional.of("ADR-1"), deleted.code());
        assertFalse(deleted.lastVersion().isEmpty());
        assertEquals("DELETED", entries.publishState());
        assertTrue(entries.entries().get(entries.entries().size() - 1).tombstone());
        assertTrue(entries.deleted().isPresent());
    }

    @Test
    void theReaderListsATypeAndFindsLiteralsInTheSameStore() {
        // given
        submit.submit(SubmitRealStoreTest.PROJECT, adr("\"Use RDF\"@en", "arkarch:consequence "
                + consequence(1, "Links are first-class.")));
        KognioRdfResourceReader reader = new KognioRdfResourceReader(new StoreReader(lifecycle));
        SimpleRdf rdf = new SimpleRdf();

        // when
        Set<IRI> adrs = reader.read(SubmitRealStoreTest.PROJECT,
                snapshot -> snapshot.typedWith(Set.of(rdf.createIRI(ADR_CLASS))));
        long hits = reader.read(SubmitRealStoreTest.PROJECT, snapshot -> snapshot.literalsContaining("FIRST-CLASS")
                .size());

        // then
        assertEquals(1, adrs.size());
        assertEquals(1, hits);
    }

    @Test
    void aFragmentNoOwnedFieldReachesIsRenderedInPlace() {
        // given: a bounded context with its subdomain, written the way the typed adapter writes it
        String ddd = "https://w3id.org/arknet/ddd#";
        SimpleRdf rdf = new SimpleRdf();
        IRI context = rdf.createIRI("https://w3id.org/arknet/id/0b6c1b70-4a39-4f0e-9d1a-5a7c3e1f2d11");
        IRI subdomain = rdf.createIRI(context.getIRIString() + "#Subdomain");
        Graph slice = rdf.createGraph();
        slice.add(context, VocabRdf.TYPE, rdf.createIRI(ddd + "BoundedContext"));
        slice.add(context, VocabDct.IDENTIFIER, rdf.createLiteral("BC-1"));
        slice.add(context, rdf.createIRI("https://w3id.org/arknet/core#name"), rdf.createLiteral("Billing", "en"));
        slice.add(context, rdf.createIRI(ddd + "domainVision"),
                rdf.createLiteral("Bills every customer once a month.", "en"));
        slice.add(context, rdf.createIRI(ddd + "partOf"), subdomain);
        slice.add(subdomain, VocabRdf.TYPE, rdf.createIRI(ddd + "Subdomain"));
        slice.add(subdomain, rdf.createIRI(ddd + "subdomainType"), rdf.createIRI(ddd + "CoreDomain"));
        KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT).writeAll(
                SubmitRealStoreTest.PROJECT, batch -> batch.create(context, "BC-1", slice, rdf.createGraph()));

        // when
        RenderedResource resource = found("BC-1");

        // then
        RenderedValue.Child part = assertInstanceOf(RenderedValue.Child.class,
                values(resource.fields(), ddd + "partOf").get(0));
        assertEquals(subdomain.getIRIString(), part.iri());
        assertEquals(ddd + "CoreDomain", ((RenderedValue.Reference) values(
                Stream.concat(part.fields().stream(), part.otherStatements().stream()).toList(),
                ddd + "subdomainType").get(0)).iri());
    }

    @Test
    void anIncomingEdgeFromABlankNodeIsAttributedToTheResourceHoldingIt() {
        // given: BC-2 reaches BC-1 through a blank node of its own slice, BC-1 reaches itself through one
        String ddd = "https://w3id.org/arknet/ddd#";
        IRI note = RDF.createIRI("https://example.org/fixture#note");
        IRI mentions = RDF.createIRI("https://example.org/fixture#mentions");
        IRI first = RDF.createIRI("https://w3id.org/arknet/id/1d8b8e52-0c4b-4b8e-9a51-6f0b8f5c1a01");
        IRI second = RDF.createIRI("https://w3id.org/arknet/id/2e9c9f63-1d5c-4c9f-8b62-7a1c9a6d2b02");
        Graph firstSlice = context(first, "BC-1", ddd);
        BlankNode own = RDF.createBlankNode();
        firstSlice.add(first, note, own);
        firstSlice.add(own, mentions, first);
        Graph secondSlice = context(second, "BC-2", ddd);
        BlankNode across = RDF.createBlankNode();
        secondSlice.add(second, note, across);
        secondSlice.add(across, mentions, first);
        KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT).writeAll(
                SubmitRealStoreTest.PROJECT, batch -> {
                    batch.create(first, "BC-1", firstSlice, RDF.createGraph());
                    batch.create(second, "BC-2", secondSlice, RDF.createGraph());
                });

        // when
        RenderedResource resource = found("BC-1");

        // then
        assertEquals(List.of("BC-2"), values(resource.incoming(), mentions.getIRIString()).stream()
                .map(value -> assertInstanceOf(RenderedValue.Reference.class, value).handle())
                .toList());
    }

    private static Graph context(final IRI context, final String code, final String ddd) {
        Graph slice = RDF.createGraph();
        slice.add(context, VocabRdf.TYPE, RDF.createIRI(ddd + "BoundedContext"));
        slice.add(context, VocabDct.IDENTIFIER, RDF.createLiteral(code));
        slice.add(context, RDF.createIRI("https://w3id.org/arknet/core#name"), RDF.createLiteral(code, "en"));
        slice.add(context, RDF.createIRI(ddd + "domainVision"), RDF.createLiteral("Holds one part of the model.", "en"));
        return slice;
    }

    private RenderedResource found(final String handle) {
        return assertInstanceOf(ResourceReading.Found.class,
                read.read(SubmitRealStoreTest.PROJECT, List.of(handle), "en", false).get(0)).resource();
    }

    private static String adr(final String name, final String more) {
        return "[] a arkres:Create ; arkres:object _:adr .\n_:adr a arkarch:ArchitectureDecisionRecord ;"
                + " arknet:name " + name + " ; arkarch:adrContext \"Resources need identities.\"@en ;"
                + " arkarch:adrDecision \"Every resource is RDF.\"@en ; " + more + " .\n";
    }

    private static String consequence(final int position, final String text) {
        return "[ arknet:position " + position + " ; arkarch:consequenceStatement \"" + text + "\"@en ;"
                + " arkarch:consequenceType arkarch:Positive ]";
    }

    private static List<RenderedValue> values(final List<RenderedField> fields, final String property) {
        return fields.stream().filter(field -> field.iri().equals(property))
                .findFirst()
                .map(RenderedField::values)
                .orElseThrow(() -> new AssertionError("no field " + property + " in " + fields));
    }
}
