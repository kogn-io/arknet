// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
                KognioRdfResourceRepositoryFactory.prefixes());
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
