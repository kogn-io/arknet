// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.uc.application.port.out.RequirementLookup;

/**
 * Integration test for {@link KognioRdfRequirementLookup} against an in-memory RDF4J-backed
 * kognio-rdf store.
 *
 * <p>This carries the strict, identifier-based cross-BC resolution behaviour that used to be
 * pinned inside {@code KognioRdfUseCaseRepositoryTest} - extracted here because the resolution
 * moved out of the use-case repository's write path into this dedicated port/adapter.</p>
 */
class KognioRdfRequirementLookupTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";

    /**
     * The store's on-disk home, managed by JUnit rather than {@code Files.createTempDirectory},
     * which left its directories behind - harmless while the store is {@code IN_MEMORY}, but
     * still an inode left in {@code /tmp} for every test run. Deleted after {@link #tearDown()}
     * has shut the store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private RequirementLookup requirementLookup;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        requirementLookup = new KognioRdfRequirementLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void resolvesAKnownRequirementCodeToItsSubjectIdentity() {
        String requirementIri = givenRequirement(PROJECT_A, "FR-1");

        ResourceId resolved = requirementLookup.resolveByCode(PROJECT_A, "FR-1");

        assertEquals(ResourceId.of(requirementIri), resolved);
    }

    @Test
    void rejectsAnUnknownRequirementCode() {
        givenRequirement(PROJECT_A, "FR-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> requirementLookup.resolveByCode(PROJECT_A, "FR-99"));

        assertTrue(ex.getMessage().contains("FR-99"), ex.getMessage());
        assertTrue(ex.getMessage().contains("req_add"), ex.getMessage());
    }

    @Test
    void rejectsAnAmbiguousRequirementCode() {
        givenRequirementAtIri(PROJECT_A, "https://w3id.org/arknet/model/requirement/dup-1", "FR-1");
        givenRequirementAtIri(PROJECT_A, "https://w3id.org/arknet/model/requirement/dup-2", "FR-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> requirementLookup.resolveByCode(PROJECT_A, "FR-1"));

        assertTrue(ex.getMessage().contains("ambiguous"), ex.getMessage());
    }

    /** A requirement of another project must not satisfy this project's reference. */
    @Test
    void aRequirementOfAnotherProjectDoesNotSatisfyThisProjectsReference() {
        givenRequirement(PROJECT_B, "FR-1");

        assertThrows(UnresolvedReferenceException.class,
                () -> requirementLookup.resolveByCode(PROJECT_A, "FR-1"));
    }

    /**
     * Writes a requirement straight into the sibling requirements graph of the shared project
     * dataset - deliberately via raw SPARQL rather than the requirements adapter, so this test
     * does not couple the two bounded contexts. Returns the requirement's IRI.
     */
    private String givenRequirement(ProjectId projectId, String requirementCode) {
        String requirementIri = "https://w3id.org/arknet/model/requirement/" + requirementCode;
        givenRequirementAtIri(projectId, requirementIri, requirementCode);
        return requirementIri;
    }

    private void givenRequirementAtIri(ProjectId projectId, String requirementIri, String identifier) {
        String insert = "INSERT DATA { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "<" + requirementIri + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + identifier + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }
}
