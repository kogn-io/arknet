// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.Triple;
import de.hauschel.arknet.persistence.VersionEntry;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * Drives {@code submit} against a real store: every resource type created, changed and deleted
 * through a document, read back over the generic store read path, and a rejected document leaving
 * the store as it was.
 */
class SubmitRealStoreTest {

    static final ProjectId PROJECT = new ProjectId("test-project");

    static final String PREFIXES = """
            @prefix arkres:  <https://w3id.org/arknet/resource#> .
            @prefix arknet:  <https://w3id.org/arknet/core#> .
            @prefix arkreq:  <https://w3id.org/arknet/requirements#> .
            @prefix arkarch: <https://w3id.org/arknet/architecture#> .
            @prefix arkddd:  <https://w3id.org/arknet/ddd#> .
            @prefix arkproc: <https://w3id.org/arknet/process#> .
            @prefix oslc_rm: <http://open-services.net/ns/rm#> .
            @prefix skos:    <http://www.w3.org/2004/02/skos/core#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            """;

    private static final String REQ = "https://w3id.org/arknet/requirements#";
    private static final String ARCH = "https://w3id.org/arknet/architecture#";
    private static final String DCT = "http://purl.org/dc/terms/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private ResourceRepository repository;
    private SubmitService submit;
    private StoreReader reader;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        repository = KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        submit = service(repository);
        reader = new StoreReader(lifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    SubmitService service(final ResourceRepository store) {
        return new SubmitService(KognioRdfResourceRepositoryFactory.schema(), store,
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory());
    }

    @Test
    void oneDocumentCreatesAResourceOfEveryTypeWiredToEachOther() {
        // when
        SubmitResult result = submit.submit(PROJECT, PREFIXES + """
                [] a arkres:Create ; arkres:object _:term .
                _:term a skos:Concept ; skos:prefLabel "Resource"@en ; skos:definition "Kept under an identity."@en .
                [] a arkres:Create ; arkres:object _:con .
                _:con a arkreq:TechnicalConstraint ; dcterms:title "Java 25"@en ;
                    arkreq:constraintStatement "Runs on Java 25."@en .
                [] a arkres:Create ; arkres:object _:fr .
                _:fr a arkreq:FunctionalRequirement ; dcterms:title "Write a resource"@en ;
                    dcterms:description "The user writes a resource."@en ;
                    arkreq:usesTerm _:term ; oslc_rm:constrainedBy _:con ;
                    arkreq:acceptanceCriterion [ arkreq:position 2 ; arkreq:criterionText "Readable afterwards."@en ] ,
                                               [ arkreq:position 1 ; arkreq:criterionText "Accepted at once."@en ] .
                [] a arkres:Create ; arkres:object _:nfr .
                _:nfr a arkreq:NonFunctionalRequirement ; dcterms:title "Fast"@en ;
                    dcterms:description "Within a second."@en ; arkreq:qualityCategory "performance" ;
                    arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText "Below one second."@en ] .
                [] a arkres:Create ; arkres:object _:actor .
                _:actor a arkproc:HumanActor ; arknet:name "Architect"@en .
                [] a arkres:Create ; arkres:object _:role .
                _:role a arkproc:Role ; arknet:name "Modeller"@en ; arkproc:filledBy _:actor .
                [] a arkres:Create ; arkres:object _:uc .
                _:uc a arkreq:UseCase ; dcterms:title "Model a decision"@en ;
                    arkreq:useCaseGoal "A decision is recorded."@en ; arkreq:primaryRole _:role ;
                    oslc_rm:satisfies _:fr ;
                    arkreq:mainStep [ arkreq:position 1 ; arkreq:stepText "Writes the decision."@en ;
                                      arkreq:stepRealises _:fr ] ;
                    arkreq:extensionStep [ arkreq:position 1 ; arkreq:stepText "The store is down."@en ] .
                [] a arkres:Create ; arkres:object _:bc1 .
                _:bc1 a arkddd:BoundedContext ; arknet:name "Modelling"@en ;
                    arkddd:domainVision "Keeps the model consistent."@en .
                [] a arkres:Create ; arkres:object _:bc2 .
                _:bc2 a arkddd:BoundedContext ; arknet:name "Export"@en ;
                    arkddd:domainVision "Turns the model into documents."@en .
                [] a arkres:Create ; arkres:object _:rel .
                _:rel a arkddd:ContextRelationship ; arkddd:upstream _:bc1 ; arkddd:downstream _:bc2 ;
                    arkddd:relationshipType arkddd:CustomerSupplier .
                [] a arkres:Create ; arkres:object _:adr .
                _:adr a arkarch:ArchitectureDecisionRecord ; arknet:name "Store resources as RDF"@en ;
                    arkarch:adrStatus arkarch:Proposed ;
                    arkarch:adrContext "Resources need identities."@en ;
                    arkarch:adrDecision "Every resource is an RDF resource."@en ;
                    arkarch:addressesRequirement _:fr ; arkarch:affectsContext _:bc1 ; arkarch:usesTerm _:term ;
                    arkarch:consequence [ arknet:position 1 ; arkarch:consequenceStatement "Links are first-class."@en ;
                                          arkarch:consequenceType arkarch:Positive ] ;
                    arkarch:consideredOption [ arknet:position 1 ; arknet:name "RDF"@en ;
                                               arkarch:optionRationale "Links are first-class."@en ;
                                               arkarch:optionOutcome arkarch:Chosen ] .
                """);

        // then
        assertEquals(Set.of("TERM-1", "TCON-1", "FR-1", "NFR-1", "ACTOR-1", "ROLE-1", "UC1", "BC-1", "BC-2", "ADR-1"),
                result.changes().stream().map(SubmittedChange::code).filter(code -> code != null)
                        .collect(Collectors.toSet()));
        assertEquals(11, result.changes().size());
        assertTrue(result.changes().stream().allMatch(change -> change.kind() == SubmittedChange.Kind.CREATED));
        for (SubmittedChange change : result.changes()) {
            assertEquals(1, history(change.iri()).size(), "one revision for " + change);
        }

        String fr = iriOf(result, "FR-1");
        Map<String, Set<String>> requirement = fields(fr);
        assertEquals(Set.of(REQ + "FunctionalRequirement"), requirement.get(RDF_TYPE));
        assertEquals(Set.of(iriOf(result, "TERM-1")), requirement.get(REQ + "usesTerm"));
        Set<String> criteria = requirement.get(REQ + "acceptanceCriterion");
        assertEquals(2, criteria.size());
        assertTrue(criteria.stream().allMatch(child -> child.startsWith(fr + "#AC-")), criteria.toString());
        Map<Integer, String> textByPosition = criteria.stream().collect(Collectors.toMap(
                child -> Integer.valueOf(single(fields(child), REQ + "position")),
                child -> single(fields(child), REQ + "criterionText")));
        assertEquals(Map.of(1, "Accepted at once.@en", 2, "Readable afterwards.@en"), textByPosition);
        criteria.forEach(child -> assertEquals(Set.of(REQ + "AcceptanceCriterion"), fields(child).get(RDF_TYPE)));

        String adr = iriOf(result, "ADR-1");
        assertTrue(single(fields(adr), ARCH + "consequence").startsWith(adr + "#Consequence-"));
        assertTrue(single(fields(adr), ARCH + "consideredOption").startsWith(adr + "#Option-"));
        String uc = iriOf(result, "UC1");
        assertTrue(single(fields(uc), REQ + "mainStep").startsWith(uc + "#Step-"));
        assertTrue(single(fields(uc), REQ + "extensionStep").startsWith(uc + "#Extension-"));
    }

    static Stream<Arguments> everyResourceType() {
        return Stream.of(
                Arguments.of("skos:Concept ; skos:prefLabel \"Resource\"@en ; skos:definition \"Old text.\"@en",
                        "skos:definition", "TERM-1"),
                Arguments.of("arkreq:TechnicalConstraint ; dcterms:title \"Java 25\"@en ;"
                        + " arkreq:constraintStatement \"Old text.\"@en", "arkreq:constraintStatement", "TCON-1"),
                Arguments.of("arkreq:BusinessConstraint ; dcterms:title \"Budget\"@en ;"
                        + " arkreq:constraintStatement \"Old text.\"@en", "arkreq:constraintStatement", "BCON-1"),
                Arguments.of("arkreq:RegulatoryConstraint ; dcterms:title \"GDPR\"@en ;"
                        + " arkreq:constraintStatement \"Old text.\"@en", "arkreq:constraintStatement", "RCON-1"),
                Arguments.of("arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
                        + " dcterms:description \"Old text.\"@en ; arkreq:acceptanceCriterion"
                        + " [ arkreq:position 1 ; arkreq:criterionText \"It works.\"@en ]",
                        "dcterms:description", "FR-1"),
                Arguments.of("arkreq:NonFunctionalRequirement ; dcterms:title \"Fast\"@en ;"
                        + " dcterms:description \"Old text.\"@en ; arkreq:qualityCategory \"performance\" ;"
                        + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Fast enough.\"@en ]",
                        "dcterms:description", "NFR-1"),
                Arguments.of("arkproc:SystemActor ; arknet:name \"Mailer\"@en ; arknet:description \"Old text.\"@en",
                        "arknet:description", "ACTOR-1"),
                Arguments.of("arkproc:Role ; arknet:name \"Modeller\"@en ; arknet:description \"Old text.\"@en",
                        "arknet:description", "ROLE-1"),
                Arguments.of("arkddd:BoundedContext ; arknet:name \"Export\"@en ;"
                        + " arkddd:domainVision \"Old text, long enough.\"@en", "arkddd:domainVision", "BC-1"),
                Arguments.of("arkarch:ArchitectureDecisionRecord ; arknet:name \"Use RDF\"@en ;"
                        + " arkarch:adrStatus arkarch:Proposed ; arkarch:adrContext \"Old text.\"@en ;"
                        + " arkarch:adrDecision \"Use RDF.\"@en", "arkarch:adrContext", "ADR-1"));
    }

    @ParameterizedTest
    @MethodSource("everyResourceType")
    void aResourceOfEachTypeIsCreatedUpdatedAndDeleted(final String typeAndFields, final String field,
            final String code) {
        // given
        String iri = iriOf(submit.submit(PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:new .\n_:new a " + typeAndFields + " .\n"), code);

        // when
        submit.submit(PROJECT, PREFIXES + "[] a arkres:Update ; arkres:object <" + iri + "> ;"
                + " arkres:set [ " + field + " \"New text, long enough.\"@en ] .\n");

        // then
        assertTrue(fields(iri).values().stream().flatMap(Set::stream).anyMatch("New text, long enough.@en"::equals));
        assertTrue(fields(iri).values().stream().flatMap(Set::stream).noneMatch(value -> value.startsWith("Old text")));
        assertEquals(2, history(iri).size());

        // when
        SubmitResult deleted = submit.submit(PROJECT, PREFIXES + "[] a arkres:Delete ; arkres:object <" + iri + "> .\n");

        // then
        assertEquals(List.of(new SubmittedChange(SubmittedChange.Kind.DELETED, iri, code)), deleted.changes());
        assertTrue(reader.outgoing(PROJECT, iri).isEmpty());
        assertTrue(history(iri).get(history(iri).size() - 1).tombstone());
    }

    @Test
    void aContextRelationshipWithoutCodeIsCreatedUpdatedAndDeleted() {
        // given
        SubmitResult contexts = submit.submit(PROJECT, PREFIXES + """
                [] a arkres:Create ; arkres:object _:a .
                _:a a arkddd:BoundedContext ; arknet:name "Modelling"@en ; arkddd:domainVision "Keeps the model."@en .
                [] a arkres:Create ; arkres:object _:b .
                _:b a arkddd:BoundedContext ; arknet:name "Export"@en ; arkddd:domainVision "Writes documents."@en .
                [] a arkres:Create ; arkres:object _:rel .
                _:rel a arkddd:ContextRelationship ; arkddd:upstream _:a ; arkddd:downstream _:b ;
                    arkddd:relationshipType arkddd:CustomerSupplier .
                """);
        SubmittedChange relationship = contexts.changes().stream().filter(change -> change.code() == null)
                .findFirst().orElseThrow();
        assertNull(relationship.code());

        // when
        submit.submit(PROJECT, PREFIXES + "[] a arkres:Update ; arkres:object <" + relationship.iri() + "> ;"
                + " arkres:set [ arkddd:relationshipType arkddd:Conformist ] .\n");

        // then
        assertEquals(Set.of("https://w3id.org/arknet/ddd#Conformist"),
                fields(relationship.iri()).get("https://w3id.org/arknet/ddd#relationshipType"));

        // when
        submit.submit(PROJECT, PREFIXES + "[] a arkres:Delete ; arkres:object <" + relationship.iri() + "> .\n");

        // then
        assertTrue(reader.outgoing(PROJECT, relationship.iri()).isEmpty());
    }

    @Test
    void addToAndRemoveFromKeepTheNamesOfTheChildrenThatStay() {
        // given
        String fr = iriOf(submit.submit(PROJECT, PREFIXES + """
                [] a arkres:Create ; arkres:object _:fr .
                _:fr a arkreq:FunctionalRequirement ; dcterms:title "Write"@en ; dcterms:description "Writes it."@en ;
                    arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText "First one."@en ] ,
                                               [ arkreq:position 2 ; arkreq:criterionText "Second one."@en ] .
                """), "FR-1");
        Map<String, String> before = criterionIriByText(fr);

        // when
        submit.submit(PROJECT, PREFIXES + "[] a arkres:AddTo ; arkres:object <" + fr + "> ;"
                + " arkres:field arkreq:acceptanceCriterion ;"
                + " arkres:value [ arkreq:position 1 ; arkreq:criterionText \"New first.\"@en ] .\n"
                + "[] a arkres:RemoveFrom ; arkres:object <" + fr + "> ; arkres:field arkreq:acceptanceCriterion ;"
                + " arkres:position 1 .\n"
                + "[] a arkres:Update ; arkres:object <" + fr + "> ; arkres:set [ dcterms:title \"Write it\"@en ] .\n");

        // then
        Map<String, String> after = criterionIriByText(fr);
        assertEquals(Set.of("New first.@en", "Second one.@en"), after.keySet());
        assertEquals(before.get("Second one.@en"), after.get("Second one.@en"));
        assertEquals("1", single(fields(after.get("New first.@en")), REQ + "position"));
        assertEquals("2", single(fields(after.get("Second one.@en")), REQ + "position"));
        assertTrue(reader.outgoing(PROJECT, before.get("First one.@en")).isEmpty());
        assertEquals(Set.of("Write it@en"), fields(fr).get(DCT + "title"));
        assertEquals(2, history(fr).size());
    }

    @Test
    void aDeletedCodeIsNeverAssignedAgain() {
        // given
        String term = iriOf(submit.submit(PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ; skos:prefLabel \"A\"@en .\n"), "TERM-1");
        submit.submit(PROJECT, PREFIXES + "[] a arkres:Delete ; arkres:object <" + term + "> .\n");

        // when
        SubmitResult next = submit.submit(PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ; skos:prefLabel \"B\"@en .\n");

        // then
        assertEquals("TERM-2", next.changes().get(0).code());
    }

    @Test
    void aDocumentWithOneInvalidActivityChangesNothing() {
        // given
        String term = iriOf(submit.submit(PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ; skos:prefLabel \"A\"@en .\n"), "TERM-1");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Update ; arkres:object <" + term + "> ; arkres:set [ skos:prefLabel \"B\"@en ] .\n"
                        + "[] a arkres:Create ; arkres:object _:c .\n_:c a skos:Concept ; skos:prefLabel \"C\"@en .\n"
                        + "[] a arkres:Create ; arkres:object _:fr .\n"
                        + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"No description\"@en ;"
                        + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Works.\"@en ] .\n"));

        // then
        assertTrue(rejected.getMessage().contains(DCT + "description"), rejected.getMessage());
        assertEquals(Set.of("A@en"), fields(term).get("http://www.w3.org/2004/02/skos/core#prefLabel"));
        assertEquals(1, history(term).size());
        assertTrue(reader.findByIdentifier(PROJECT, "TERM-2").isEmpty());
        assertTrue(reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    @Test
    void aReferenceToNoResourceIsRejected() {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Create ; arkres:object _:fr .\n"
                        + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ; dcterms:description \"Writes.\"@en ;"
                        + " arkreq:usesTerm <https://w3id.org/arknet/id/missing> ;"
                        + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Works.\"@en ] .\n"));

        // then
        assertTrue(rejected.getMessage().contains(REQ + "usesTerm"), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    @Test
    void aTripleOutsideEveryActivityIsRejected() {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "<https://w3id.org/arknet/id/x> skos:prefLabel \"Stray\"@en .\n"
                        + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ; skos:prefLabel \"A\"@en .\n"));

        // then
        assertTrue(rejected.getMessage().contains("belongs to no activity"), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "TERM-1").isEmpty());
    }

    @Test
    void anUpdateMayNotTouchTheCodeOrAnOwnedField() {
        // given
        String fr = iriOf(submit.submit(PROJECT, PREFIXES + """
                [] a arkres:Create ; arkres:object _:fr .
                _:fr a arkreq:FunctionalRequirement ; dcterms:title "Write"@en ; dcterms:description "Writes it."@en ;
                    arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText "First one."@en ] .
                """), "FR-1");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Update ; arkres:object <" + fr + "> ;"
                        + " arkres:set [ dcterms:identifier \"FR-9\" ] ; arkres:clear arkreq:acceptanceCriterion .\n"));

        // then
        assertTrue(rejected.getMessage().contains(DCT + "identifier"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(REQ + "acceptanceCriterion"), rejected.getMessage());
        assertEquals(1, history(fr).size());
    }

    @Test
    void aLanguageTaggedSetReplacesOnlyItsLanguage() {
        // given
        String term = iriOf(submit.submit(PROJECT, PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n"
                + "_:t a skos:Concept ; skos:prefLabel \"Resource\"@en , \"Resource\"@de ;"
                + " skos:definition \"Old.\"@en , \"Alt.\"@de .\n"), "TERM-1");

        // when
        submit.submit(PROJECT, PREFIXES + "[] a arkres:Update ; arkres:object <" + term + "> ;"
                + " arkres:set [ skos:definition \"Neu.\"@de ] .\n");

        // then
        assertEquals(Set.of("Old.@en", "Neu.@de"), fields(term).get("http://www.w3.org/2004/02/skos/core#definition"));
    }

    static String iriOf(final SubmitResult result, final String code) {
        return result.changes().stream().filter(change -> code.equals(change.code())).findFirst().orElseThrow().iri();
    }

    Map<String, Set<String>> fields(final String iri) {
        Map<String, Set<String>> fields = new TreeMap<>();
        for (Triple triple : reader.outgoing(PROJECT, iri)) {
            fields.computeIfAbsent(triple.predicate(), key -> new TreeSet<>()).add(render(triple.object()));
        }
        return fields;
    }

    private Map<String, String> criterionIriByText(final String requirement) {
        return fields(requirement).get(REQ + "acceptanceCriterion").stream().collect(Collectors.toMap(
                child -> single(fields(child), REQ + "criterionText"), child -> child));
    }

    List<VersionEntry> history(final String iri) {
        return reader.history(PROJECT, iri);
    }

    private static String single(final Map<String, Set<String>> fields, final String property) {
        Set<String> values = fields.get(property);
        assertEquals(1, values == null ? 0 : values.size(), property + " in " + fields);
        return values.iterator().next();
    }

    private static String render(final RdfNode node) {
        return switch (node) {
            case RdfNode.Resource resource -> resource.iri();
            case RdfNode.Literal literal -> literal.lexicalForm()
                    + (literal.languageTag() == null || literal.languageTag().isEmpty() ? "" : "@" + literal.languageTag());
        };
    }
}
