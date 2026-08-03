// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.adr.application.port.out.RequirementLookup;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Schema-drift regression guard (mirrors the bounded-context adapter's
 * {@code KognioRdfTermLookupUbiquitousLanguageSchemaTest}):
 * {@link KognioRdfRequirementLookup} hardcodes the exact storage schema (graph IRI,
 * {@code dcterms:identifier} predicate) that {@code arknet-requirements-adapter-kogniordf}'s
 * {@code KognioRdfRequirementRepository} writes - with no Java import between the two adapter
 * modules that the compiler or ArchUnit could ever catch drift on; the only thing tying the two
 * together is a prose code comment.
 *
 * <p>This test is the substitute for that missing compile-time coupling: it writes a requirement
 * through the requirements BC's <em>actual</em> out-adapter and resolves it through the ADR BC's
 * actual {@link KognioRdfRequirementLookup} against the very same store. If either adapter's private
 * schema ever drifts from the other's, this test - not a silent
 * {@link UnresolvedReferenceException} at runtime for a requirement that demonstrably exists - is
 * what fails.</p>
 */
class KognioRdfRequirementLookupRequirementsSchemaTest {

    private static final ProjectId PROJECT = new ProjectId("adr-req-schema");

    private DatasetLifecycleRdf4j lifecycle;
    private RequirementRepository requirementRepository;
    private RequirementLookup adrRequirementLookup;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-adr-req-schema-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        requirementRepository =
                KognioRdfRequirementRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT);
        adrRequirementLookup = new KognioRdfRequirementLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void adrResolvesARequirementWrittenByTheRealRequirementsOutAdapter() {
        RequirementId id = new RequirementId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        Requirement requirement = new Requirement(id, new RequirementCode("FR-1"),
                "Store the model", "The system shall persist the architecture model.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(), List.of("Done when the model survives a restart."), List.of());

        requirementRepository.create(PROJECT, requirement, null);

        assertEquals(id.value(), adrRequirementLookup.resolveByCode(PROJECT, "FR-1"));
    }

    @Test
    void anUnknownRequirementCodeIsRejectedDidactically() {
        assertThrows(UnresolvedReferenceException.class,
                () -> adrRequirementLookup.resolveByCode(PROJECT, "FR-99"));
    }
}
