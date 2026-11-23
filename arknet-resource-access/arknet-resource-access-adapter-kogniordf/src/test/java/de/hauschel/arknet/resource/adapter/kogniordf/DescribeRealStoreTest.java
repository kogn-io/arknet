// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.ChangeExample;
import de.hauschel.arknet.resource.application.port.in.DescribeResourceType;
import de.hauschel.arknet.resource.application.port.in.FieldDescription;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.Kind;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.Presence;
import de.hauschel.arknet.resource.application.port.in.ResourceCatalog;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeDescription;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;

/**
 * Describes the shipped resource types and runs every example against a real store.
 */
class DescribeRealStoreTest {

    /** The placeholder the change examples of a type without code name their resource by. */
    private static final String NO_HANDLE = "<IRI-RETURNED-BY-SUBMIT>";

    private static final DescribeResourceType DESCRIBER =
            KognioRdfResourceRepositoryFactory.describer(DisplayLocale.DEFAULT);

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private SubmitService submit;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        submit = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle));
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    static Stream<String> resourceTypes() {
        return DESCRIBER.catalog(null).types().stream().map(type -> type.name());
    }

    @Test
    void theCatalogListsEveryResourceTypeOfTheSchemaAndTheActivities() {
        // when
        ResourceCatalog catalog = DESCRIBER.catalog("en");

        // then
        assertEquals(KognioRdfResourceRepositoryFactory.schema().resourceTypes(),
                catalog.types().stream().map(type -> type.iri()).collect(Collectors.toSet()));
        assertTrue(catalog.activityVocabulary().stream().anyMatch(term -> term.name().equals("arkres:Create")
                && !term.comment().isBlank()), catalog.activityVocabulary().toString());
        assertTrue(catalog.activityVocabulary().stream().noneMatch(term -> term.name().equals("arkres:codePrefix")));
        assertTrue(catalog.prefixDeclarations().contains("@prefix arkres: <https://w3id.org/arknet/resource#> ."),
                catalog.prefixDeclarations());
    }

    @ParameterizedTest
    @MethodSource("resourceTypes")
    void theExampleOfEveryTypeRunsAsItStands(final String type) {
        // given
        ResourceTypeDescription description = DESCRIBER.describe(type, "en");

        // when
        SubmitResult result = submit.submit(SubmitRealStoreTest.PROJECT, description.example());

        // then
        assertTrue(result.changes().stream().allMatch(change -> change.kind() == SubmittedChange.Kind.CREATED),
                result.toString());
        assertEquals(description.summary().codePrefix().isPresent(), result.changes().stream()
                .anyMatch(change -> change.code() != null
                        && change.code().startsWith(description.summary().codePrefix().orElse("-"))),
                description.example());
    }

    @ParameterizedTest
    @MethodSource("resourceTypes")
    void theChangeExamplesOfEveryTypeRunInOrderAfterItsCreate(final String type) {
        // given
        ResourceTypeDescription description = DESCRIBER.describe(type, "en");
        Optional<String> codePrefix = description.summary().codePrefix();
        SubmittedChange created = submit.submit(SubmitRealStoreTest.PROJECT, description.example()).changes().stream()
                .filter(change -> codePrefix.map(prefix -> change.code() != null && change.code().startsWith(prefix))
                        .orElse(change.code() == null))
                .findFirst().orElseThrow();

        // when
        List<SubmitResult> results = description.changeExamples().stream()
                .map(change -> submit.submit(SubmitRealStoreTest.PROJECT,
                        change.document().replace(NO_HANDLE, "<" + created.iri() + ">")))
                .toList();

        // then
        List<String> activities = description.changeExamples().stream().map(ChangeExample::activity).toList();
        assertEquals("arkres:Update", activities.get(0), description.changeExamples().toString());
        assertEquals("arkres:Delete", activities.get(activities.size() - 1));
        assertEquals(description.fields().stream().anyMatch(field -> field.kind() == Kind.CHILDREN),
                activities.containsAll(List.of("arkres:AddTo", "arkres:RemoveFrom")), activities.toString());
        results.subList(0, results.size() - 1).forEach(result -> assertEquals(
                List.of(new SubmittedChange(SubmittedChange.Kind.UPDATED, created.iri(), created.code())),
                result.changes().stream().filter(change -> change.iri().equals(created.iri())).toList()));
        assertEquals(List.of(new SubmittedChange(SubmittedChange.Kind.DELETED, created.iri(), created.code())),
                results.get(results.size() - 1).changes());
    }

    @Test
    void theChangeExamplesNameTheResourceByItsHandle() {
        // when
        ResourceTypeDescription description = DESCRIBER.describe("arkreq:FunctionalRequirement", "en");

        // then
        assertEquals(List.of("arkres:Update", "arkres:AddTo", "arkres:RemoveFrom", "arkres:Delete"),
                description.changeExamples().stream().map(ChangeExample::activity).toList());
        assertTrue(description.changeExamples().stream()
                .allMatch(change -> change.document().contains("arkres:object <FR-1>")
                        && change.document().contains("@prefix arkres: <https://w3id.org/arknet/resource#> .")),
                description.changeExamples().toString());
    }

    @Test
    void aRequirementShowsItsCodeFieldsEdgesAndChildren() {
        // when
        ResourceTypeDescription description = DESCRIBER.describe("arkreq:FunctionalRequirement", "en");
        Map<String, FieldDescription> fields = byName(description);

        // then
        assertEquals(Optional.of("FR-"), description.summary().codePrefix());
        assertTrue(fields.get("dcterms:identifier").assigned());
        FieldDescription title = fields.get("dcterms:title");
        assertEquals(Kind.TEXT, title.kind());
        assertEquals(Presence.REQUIRED, title.presence());
        assertTrue(title.onePerLanguage());
        assertFalse(title.rules().isEmpty());
        FieldDescription usesTerm = fields.get("arkreq:usesTerm");
        assertEquals(Kind.REFERENCE, usesTerm.kind());
        assertTrue(usesTerm.valueTypes().contains("skos:Concept"), usesTerm.valueTypes().toString());
        FieldDescription priority = fields.get("arkreq:priority");
        assertEquals(Kind.CHOICE, priority.kind());
        assertTrue(priority.allowedValues().contains("arkreq:MustHave"), priority.allowedValues().toString());
        FieldDescription criteria = fields.get("arkreq:acceptanceCriterion");
        assertEquals(Kind.CHILDREN, criteria.kind());
        assertEquals("AC", criteria.children().orElseThrow().kind());
        assertEquals("arkreq:position", criteria.children().orElseThrow().orderedBy());
        assertFalse(criteria.children().orElseThrow().fields().isEmpty());
        assertTrue(description.example().contains("@prefix arkreq: <https://w3id.org/arknet/requirements#> ."),
                description.example());
    }

    @Test
    void theDescriptionDeclaresEveryPrefixItsFieldsNameNotOnlyThoseOfTheExample() {
        // when
        ResourceTypeDescription description = DESCRIBER.describe("arkarch:ArchitectureDecisionRecord", "en");

        // then
        assertTrue(byName(description).get("arkarch:usesTerm").valueTypes().contains("skos:Concept"));
        assertFalse(description.example().contains("skos:"), description.example());
        assertTrue(description.prefixDeclarations()
                .contains("@prefix skos: <http://www.w3.org/2004/02/skos/core#> ."), description.prefixDeclarations());
        assertTrue(description.prefixDeclarations().contains("@prefix arkarch: <"), description.prefixDeclarations());
    }

    @Test
    void aTypeIsNamedByItsFullIriAsWell() {
        assertEquals("arkreq:UseCase",
                DESCRIBER.describe("<https://w3id.org/arknet/requirements#UseCase>", null).summary().name());
    }

    @Test
    void textsFollowTheDisplayLanguage() {
        // when
        ResourceTypeDescription english = DESCRIBER.describe("arkreq:UseCase", "en");
        ResourceTypeDescription german = DESCRIBER.describe("arkreq:UseCase", "de");

        // then
        assertNotEquals(english.summary().label(), german.summary().label());
        assertNotEquals(byName(english).get("dcterms:title").rules(), byName(german).get("dcterms:title").rules());
        assertTrue(german.example().contains("\"@de"), german.example());
    }

    @Test
    void anUnknownTypeIsRejectedWithTheKnownTypes() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> DESCRIBER.describe("arkreq:Unknown", null));

        assertTrue(rejected.getMessage().contains("arkreq:UseCase"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("@prefix arkreq:"), rejected.getMessage());
    }

    private static Map<String, FieldDescription> byName(final ResourceTypeDescription description) {
        return description.fields().stream().collect(Collectors.toMap(FieldDescription::name, Function.identity()));
    }
}
