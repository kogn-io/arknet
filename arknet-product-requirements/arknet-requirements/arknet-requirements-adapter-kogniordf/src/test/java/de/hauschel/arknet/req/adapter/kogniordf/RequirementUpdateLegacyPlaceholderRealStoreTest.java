// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.pr.shared.RequirementCode;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.TermLookup;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.AcceptanceCriterionTextPatch;
import de.hauschel.arknet.req.domain.MissingAcceptanceCriteriaException;
import de.hauschel.arknet.req.domain.RemovedPositions;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;

/**
 * Issue #728: the two ways out of the legacy acceptance-criterion placeholder that
 * {@link MissingAcceptanceCriteriaException} names - removing position 1 together with a new
 * criterion, and patching the text at position 1 - run through {@link RequirementService#update}
 * against the real kognio-rdf adapter (positions, versioned write, SHACL gate), not only the
 * in-memory test repository of {@code RequirementServiceTest}.
 */
class RequirementUpdateLegacyPlaceholderRealStoreTest {

    private static final ProjectId PROJECT = new ProjectId("legacy-placeholder");
    private static final String LANGUAGE = "en";
    private static final RequirementCode CODE = new RequirementCode("FR-1");
    private static final String PLACEHOLDER_MARKER = "Altdatensatz";

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private RequirementRepository repository;
    private RequirementService service;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = KognioRdfRequirementRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT);
        VersionedWriteFunnel funnel =
                KognioRdfRequirementRepositoryFactory.buildFunnel(datasetLifecycle, DisplayLocale.DEFAULT);
        ConstraintRepository constraints =
                KognioRdfConstraintRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT, funnel);
        TermLookup unusedTermLookup = (projectId, termCode) -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        service = new RequirementService(repository, new UuidResourceIdFactory(), unusedTermLookup, constraints,
                List::of);
        givenLegacyRequirementWithoutAcceptanceCriterion();
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void removingThePlaceholderPositionTogetherWithANewCriterionReplacesItWithTheRealOne() {
        // given a requirement predating acceptance criteria, read back with the placeholder at position 1
        assertTrue(readCurrent().acceptanceCriteriaIsSynthesized());

        // when
        service.update(PROJECT, CODE, null, null, null, List.of("A real criterion"), null,
                new RemovedPositions(Set.of(1)), null, null, LANGUAGE, null);

        // then
        RequirementRepository.CurrentRequirement current = readCurrent();
        assertEquals(List.of(new AcceptanceCriterion(1, "A real criterion")), current.value().acceptanceCriteria());
        assertFalse(current.acceptanceCriteriaIsSynthesized());
    }

    @Test
    void removingThePlaceholderPositionWithSeveralNewCriteriaLeavesGaplessPositions() {
        service.update(PROJECT, CODE, null, null, null, List.of("First", "Second"), null,
                new RemovedPositions(Set.of(1)), null, null, LANGUAGE, null);

        RequirementRepository.CurrentRequirement current = readCurrent();
        assertEquals(List.of(new AcceptanceCriterion(1, "First"), new AcceptanceCriterion(2, "Second")),
                current.value().acceptanceCriteria());
        assertFalse(current.acceptanceCriteriaIsSynthesized());
    }

    @Test
    void patchingThePlaceholderPositionReplacesItWithTheRealCriterion() {
        service.update(PROJECT, CODE, null, null, null, null,
                List.of(new AcceptanceCriterionTextPatch(1, "A real criterion")), null, null, null, LANGUAGE, null);

        RequirementRepository.CurrentRequirement current = readCurrent();
        assertEquals(List.of(new AcceptanceCriterion(1, "A real criterion")), current.value().acceptanceCriteria());
        assertFalse(current.acceptanceCriteriaIsSynthesized());
    }

    @Test
    void anUpdateThatLeavesThePlaceholderInPlaceIsStillRefusedAndPersistsNothing() {
        assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.update(PROJECT, CODE, "Renamed", null, null, null, null, null, null, null,
                        LANGUAGE, null));

        Requirement found = repository.findByCode(PROJECT, CODE, null).orElseThrow();
        assertEquals("Login", found.title());
        assertTrue(found.acceptanceCriteria().get(0).text().contains(PLACEHOLDER_MARKER));
    }

    private RequirementRepository.CurrentRequirement readCurrent() {
        return repository.findCurrentByCode(PROJECT, CODE, null).orElseThrow();
    }

    /** A legacy requirement without any {@code arkreq:acceptanceCriterion} triple - shape-illegal today, so seeded store-first past the write gate. */
    private void givenLegacyRequirementWithoutAcceptanceCriterion() {
        RequirementId id = new RequirementId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        String insert = "INSERT DATA { GRAPH <" + ContentGraph.GRAPH_IRI + "> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + CODE.value() + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }
}
