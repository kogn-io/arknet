// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
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
import de.hauschel.arknet.resource.application.ReadService;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.DescribeResourceType;
import de.hauschel.arknet.resource.application.port.in.ListedResource;
import de.hauschel.arknet.resource.application.port.in.ResourceListing;
import de.hauschel.arknet.resource.application.port.in.ResourceReading;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeDescription;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeSummary;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;

/**
 * The listing against the shipped shapes and a real store: every resource type lists what its
 * {@code describe} example wrote, by label and by prefixed name.
 */
class ResourceListRealStoreTest {

    private static final DescribeResourceType DESCRIBER =
            KognioRdfResourceRepositoryFactory.describer(DisplayLocale.DEFAULT);

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private SubmitService submit;
    private ReadService reading;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        submit = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                Clock.systemDefaultZone());
        reading = KognioRdfResourceRepositoryFactory.reading(lifecycle, DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    static Stream<ResourceTypeSummary> resourceTypes() {
        return DESCRIBER.catalog(null).types().stream();
    }

    @ParameterizedTest
    @MethodSource("resourceTypes")
    void everyTypeListsWhatItsExampleWroteByLabelAndByPrefixedName(final ResourceTypeSummary type) {
        // given
        ResourceTypeDescription description = DESCRIBER.describe(type.name(), "en");
        SubmittedChange created = submit.submit(SubmitRealStoreTest.PROJECT, description.example()).changes().stream()
                .filter(change -> type.codePrefix().map(prefix -> change.code() != null
                        && change.code().startsWith(prefix)).orElse(change.code() == null))
                .findFirst().orElseThrow();
        String handle = created.code() != null ? created.code() : created.iri();

        // when
        ResourceListing byLabel = reading.list(SubmitRealStoreTest.PROJECT, type.label(), null, "en", false, 0);
        ResourceListing byName = reading.list(SubmitRealStoreTest.PROJECT, type.name(), null, "en", false, 0);
        ResourceListing everyType = reading.list(SubmitRealStoreTest.PROJECT, null, null, "en", false, 0);

        // then
        assertTrue(handles(byLabel).contains(handle), byLabel.toString());
        assertEquals(handles(byLabel), handles(byName));
        assertTrue(handles(everyType).contains(handle));
        ListedResource row = byName.resources().stream().filter(candidate -> candidate.handle().equals(handle))
                .findFirst().orElseThrow();
        assertEquals(type.label(), row.typeLabel());
    }

    @Test
    void aStatusFilterKeepsTheRequirementsInThatStatusAndFullShowsEachOne() {
        // given
        submit.submit(SubmitRealStoreTest.PROJECT, DESCRIBER.describe("arkreq:FunctionalRequirement", "en").example());

        // when
        ResourceListing proposed = reading.list(SubmitRealStoreTest.PROJECT, "Requirement", "Proposed", "en", true, 0);
        ResourceListing approved = reading.list(SubmitRealStoreTest.PROJECT, "Requirement", "Accepted", "en", false,
                0);

        // then
        assertEquals(1, proposed.total(), proposed.toString());
        assertEquals(Optional.of("Proposed"), proposed.resources().get(0).status().map(status -> status.label()));
        assertInstanceOf(ResourceReading.Found.class, proposed.readings().get(0));
        assertEquals(0, approved.total());
        assertThrows(IllegalArgumentException.class,
                () -> reading.list(SubmitRealStoreTest.PROJECT, "Requirement", "Nonsense", "en", false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> reading.list(SubmitRealStoreTest.PROJECT, null, "Proposed", "en", false, 0));
    }

    private static List<String> handles(final ResourceListing listing) {
        return listing.resources().stream().map(ListedResource::handle).toList();
    }
}
