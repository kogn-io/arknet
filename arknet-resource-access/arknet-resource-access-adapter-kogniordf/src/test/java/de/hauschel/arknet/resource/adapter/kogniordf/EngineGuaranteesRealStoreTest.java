// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.Triple;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The engine guarantees of {@code submit} against a real store: the cases the typed read paths
 * would not see or not read, which no shape rules out because no document can produce them. The
 * engine assigns every code and IRI, never changes a type, keeps every resource and owned child
 * under an IRI and numbers child positions itself. Each case here is a document trying anyway,
 * rejected and leaving the store as it was, or the numbering the engine applies instead.
 */
class EngineGuaranteesRealStoreTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");

    private static final String PREFIXES = SubmitRealStoreTest.PREFIXES;

    private static final String REQ = "https://w3id.org/arknet/requirements#";

    private static final String REQUIREMENT = "[] a arkres:Create ; arkres:object _:fr .\n"
            + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
            + " dcterms:description \"Writes it.\"@en ;"
            + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"First one.\"@en ] ";

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private SubmitService submit;
    private StoreReader reader;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        submit = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                Clock.systemDefaultZone());
        reader = new StoreReader(lifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    // --- codes: the store assigns them -------------------------------------------------------

    @Test
    void aCreateWritingItsOwnIdentifierIsRejected() {
        // when
        SubmitRejectedException rejected = rejected(REQUIREMENT + "; dcterms:identifier \"FR-7\" .\n");

        // then
        assertTrue(rejected.getMessage().contains("the store assigns its code"), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "FR-7").isEmpty());
        assertTrue(reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    @Test
    void clearingTheIdentifierIsRejected() {
        // given
        String fr = storedRequirement();

        // when
        SubmitRejectedException rejected = rejected("[] a arkres:Update ; arkres:object <" + fr + "> ;"
                + " arkres:clear dcterms:identifier .\n");

        // then
        assertTrue(rejected.getMessage().contains("the store assigns the code"), rejected.getMessage());
        assertEquals(1, reader.history(PROJECT, fr).size());
        assertTrue(!reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    // --- types: a create names a concrete resource type, nothing changes it afterwards --------

    @ParameterizedTest
    @ValueSource(strings = {"arkreq:Requirement", "arkreq:Constraint"})
    void aResourceTypedOnlyWithAnAbstractSuperclassIsNoResourceType(final String type) {
        // when
        SubmitRejectedException rejected = rejected("[] a arkres:Create ; arkres:object _:x .\n_:x a " + type
                + " ; dcterms:title \"Write\"@en ; dcterms:description \"Writes it.\"@en .\n");

        // then
        assertTrue(rejected.getMessage().contains("a created resource is typed with one resource type"),
                rejected.getMessage());
    }

    @Test
    void clearingTheTypeIsRejected() {
        // given
        String fr = storedRequirement();

        // when
        SubmitRejectedException rejected = rejected("[] a arkres:Update ; arkres:object <" + fr + "> ;"
                + " arkres:clear rdf:type .\n");

        // then
        assertTrue(rejected.getMessage().contains("the type stays"), rejected.getMessage());
        assertEquals(1, reader.history(PROJECT, fr).size());
    }

    // --- IRIs: no resource and no owned child is a blank node ---------------------------------

    @Test
    void aCreatedResourceAndItsChildrenAreStoredUnderIris() {
        // when
        String fr = storedRequirement();

        // then
        assertTrue(fr.startsWith("https://"), fr);
        List<String> criteria = values(fr, REQ + "acceptanceCriterion");
        assertEquals(1, criteria.size());
        assertTrue(criteria.get(0).startsWith(fr + "#AC-"), criteria.toString());
    }

    @Test
    void anEdgeToAResourceWrittenInlineAsABlankNodeIsRejected() {
        // when
        SubmitRejectedException rejected = rejected(REQUIREMENT + "; arkreq:usesTerm [ a skos:Concept ;"
                + " skos:prefLabel \"Model\"@en ; skos:definition \"What the store holds.\"@en ] .\n");

        // then
        assertTrue(rejected.getMessage().contains(REQ + "usesTerm"), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    @Test
    void anEdgeWithoutANodeKindRuleToAResourceWrittenInlineAsABlankNodeIsRejected() {
        // when
        SubmitRejectedException rejected = rejected("[] a arkres:Create ; arkres:object _:rel .\n"
                + "_:rel a arkddd:ContextRelationship ; arkddd:relationshipType arkddd:Partnership ;"
                + " arkddd:upstream [ a arkddd:BoundedContext ; arknet:name \"Up\"@en ;"
                + " arkddd:domainVision \"Keeps the upstream model.\"@en ] ;"
                + " arkddd:downstream [ a arkddd:BoundedContext ; arknet:name \"Down\"@en ;"
                + " arkddd:domainVision \"Keeps the downstream model.\"@en ] .\n");

        // then
        assertTrue(rejected.getMessage().contains("upstream"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("a class of resources"), rejected.getMessage());
    }

    // --- positions: the engine numbers children from 1 without gaps ---------------------------

    @Test
    void positionsADocumentGivesOnlyOrderTheChildrenAndTheStoreNumbersThemFromOne() {
        // when
        String fr = SubmitRealStoreTest.iriOf(submit.submit(PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:fr .\n_:fr a arkreq:FunctionalRequirement ;"
                + " dcterms:title \"Write\"@en ; dcterms:description \"Writes it.\"@en ;"
                + " arkreq:acceptanceCriterion [ arkreq:position 9 ; arkreq:criterionText \"Second one.\"@en ] ,"
                + " [ arkreq:position 5 ; arkreq:criterionText \"First one.\"@en ] .\n"), "FR-1");

        // then
        assertEquals(Map.of("First one.", "1", "Second one.", "2"), positionByText(fr));
    }

    @Test
    void aChildAddedBeyondTheLastPositionIsAppendedRightAfterIt() {
        // given
        String fr = storedRequirement();

        // when
        submit.submit(PROJECT, PREFIXES + "[] a arkres:AddTo ; arkres:object <" + fr + "> ;"
                + " arkres:field arkreq:acceptanceCriterion ;"
                + " arkres:value [ arkreq:position 99 ; arkreq:criterionText \"Second one.\"@en ] .\n");

        // then
        assertEquals(Map.of("First one.", "1", "Second one.", "2"), positionByText(fr));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "arkreq:position 1 ; arkreq:criterionText \"Second one.\"@en | ask for position 1",
        "arkreq:criterionText \"Second one.\"@en | carries its position"})
    void childrenOfACreateWithoutOrWithTheSamePositionAreRejected(final String secondChild, final String rule) {
        // when
        SubmitRejectedException rejected = rejected(REQUIREMENT + ", [ " + secondChild + " ] .\n");

        // then
        assertTrue(rejected.getMessage().contains(rule), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    // --- language tags: every text a document writes carries one ------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "a skos:Concept ; skos:prefLabel \"Model\" ; skos:definition \"What the store holds.\"@en"
                + " | skos/core#prefLabel",
        "a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ; dcterms:description \"Writes it.\" ;"
                + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"First one.\"@en ]"
                + " | http://purl.org/dc/terms/description",
        "a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ; dcterms:description \"Writes it.\"@en ;"
                + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"First one.\" ]"
                + " | requirements#criterionText"})
    void aTextWrittenWithoutALanguageTagIsRejected(final String resource, final String field) {
        // when
        SubmitRejectedException rejected = rejected("[] a arkres:Create ; arkres:object _:x .\n_:x " + resource
                + " .\n");

        // then
        assertTrue(rejected.getMessage().contains(field), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("language tag"), rejected.getMessage());
    }

    @Test
    void aTextSetWithoutALanguageTagIsRejected() {
        // given
        String fr = storedRequirement();

        // when
        SubmitRejectedException rejected = rejected("[] a arkres:Update ; arkres:object <" + fr + "> ;"
                + " arkres:set [ dcterms:title \"Rewrite\" ] .\n");

        // then
        assertTrue(rejected.getMessage().contains("http://purl.org/dc/terms/title of " + fr), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("language tag"), rejected.getMessage());
        assertEquals(1, reader.history(PROJECT, fr).size());
    }

    @Test
    void aStoredTextWithoutALanguageTagBlocksNeitherItsResourceNorItsNeighbours() {
        // given: a constraint whose title was stored untagged, as written before the tag was mandatory
        String constraint = SubmitRealStoreTest.iriOf(submit.submit(PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:con .\n_:con a arkreq:TechnicalConstraint ;"
                + " dcterms:title \"Java 25\"@en ; arkreq:constraintStatement \"Runs on Java 25.\"@en .\n"), "TCON-1");
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { <" + constraint
                        + "> <http://purl.org/dc/terms/title> \"Java 25 untagged\" } }");
                return null;
            });
        }

        // when
        submit.submit(PROJECT, PREFIXES + "[] a arkres:Update ; arkres:object <" + constraint + "> ;"
                + " arkres:set [ arkreq:constraintStatement \"Runs on Java 25 or later.\"@en ] .\n");
        submit.submit(PROJECT, PREFIXES + REQUIREMENT + "; oslc_rm:constrainedBy <" + constraint + "> .\n");

        // then
        assertTrue(values(constraint, "http://purl.org/dc/terms/title").contains("Java 25 untagged"));
        assertTrue(!reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    // --- helpers -------------------------------------------------------------------------------

    private String storedRequirement() {
        return SubmitRealStoreTest.iriOf(submit.submit(PROJECT, PREFIXES + REQUIREMENT + ".\n"), "FR-1");
    }

    private SubmitRejectedException rejected(final String document) {
        return assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT, PREFIXES + document));
    }

    private List<String> values(final String iri, final String property) {
        return reader.outgoing(PROJECT, iri).stream()
                .filter(triple -> property.equals(triple.predicate()))
                .map(Triple::object)
                .map(EngineGuaranteesRealStoreTest::lexical)
                .sorted()
                .toList();
    }

    private Map<String, String> positionByText(final String requirement) {
        return values(requirement, REQ + "acceptanceCriterion").stream().collect(Collectors.toMap(
                criterion -> values(criterion, REQ + "criterionText").get(0),
                criterion -> values(criterion, REQ + "position").get(0), (first, second) -> first, TreeMap::new));
    }

    private static String lexical(final RdfNode node) {
        return switch (node) {
            case RdfNode.Resource resource -> resource.iri();
            case RdfNode.Literal literal -> literal.lexicalForm();
        };
    }
}
