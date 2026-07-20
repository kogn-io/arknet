// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.uc.application.port.out.RequirementLookup;

/**
 * Integration test for {@link KognioRdfRequirementLookup} against an in-memory RDF4J-backed
 * kognio-rdf store.
 *
 * <p>This carries the strict, identifier-based cross-BC resolution behaviour that used to be
 * pinned inside {@code KognioRdfUseCaseRepositoryTest} (issue #41) - extracted here because
 * issue #89 moved the resolution itself out of the use-case repository's write path into this
 * dedicated port/adapter.</p>
 */
class KognioRdfRequirementLookupTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("a");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("b");
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";

    private DatasetLifecycleRdf4j lifecycle;
    private RequirementLookup requirementLookup;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-uc-requirement-lookup-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        requirementLookup = new KognioRdfRequirementLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void resolvesAKnownRequirementCodeToItsSubjectIdentity() {
        String requirementIri = givenRequirement(WORKSPACE_A, "FR-1");

        ResourceId resolved = requirementLookup.resolveByCode(WORKSPACE_A, "FR-1");

        assertEquals(ResourceId.of(requirementIri), resolved);
    }

    @Test
    void rejectsAnUnknownRequirementCode() {
        givenRequirement(WORKSPACE_A, "FR-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> requirementLookup.resolveByCode(WORKSPACE_A, "FR-99"));

        assertTrue(ex.getMessage().contains("FR-99"), ex.getMessage());
        assertTrue(ex.getMessage().contains("req_add"), ex.getMessage());
    }

    @Test
    void rejectsAnAmbiguousRequirementCode() {
        givenRequirementAtIri(WORKSPACE_A, "https://w3id.org/arknet/model/requirement/dup-1", "FR-1");
        givenRequirementAtIri(WORKSPACE_A, "https://w3id.org/arknet/model/requirement/dup-2", "FR-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> requirementLookup.resolveByCode(WORKSPACE_A, "FR-1"));

        assertTrue(ex.getMessage().contains("ambiguous"), ex.getMessage());
    }

    /** A requirement of another workspace must not satisfy this workspace's reference. */
    @Test
    void aRequirementOfAnotherWorkspaceDoesNotSatisfyThisWorkspacesReference() {
        givenRequirement(WORKSPACE_B, "FR-1");

        assertThrows(UnresolvedReferenceException.class,
                () -> requirementLookup.resolveByCode(WORKSPACE_A, "FR-1"));
    }

    /**
     * Writes a requirement straight into the sibling requirements graph of the shared workspace
     * dataset - deliberately via raw SPARQL rather than the requirements adapter, so this test
     * does not couple the two bounded contexts. Returns the requirement's IRI.
     */
    private String givenRequirement(WorkspaceId workspaceId, String requirementCode) {
        String requirementIri = "https://w3id.org/arknet/model/requirement/" + requirementCode;
        givenRequirementAtIri(workspaceId, requirementIri, requirementCode);
        return requirementIri;
    }

    private void givenRequirementAtIri(WorkspaceId workspaceId, String requirementIri, String identifier) {
        String insert = "INSERT DATA { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "<" + requirementIri + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + identifier + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }
}
