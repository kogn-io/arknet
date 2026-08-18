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
import de.hauschel.arknet.uc.application.port.out.ConstraintLookup;

/**
 * Integration test for {@link KognioRdfConstraintLookup} against an in-memory RDF4J-backed
 * kognio-rdf store (issue #329). Mirrors {@link KognioRdfRequirementLookupTest} exactly, into the
 * neighbouring requirements bounded context's constraints graph instead.
 */
class KognioRdfConstraintLookupTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String CONSTRAINTS_GRAPH = "https://w3id.org/arknet/model/constraints";

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private ConstraintLookup constraintLookup;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        constraintLookup = new KognioRdfConstraintLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void resolvesAKnownConstraintCodeToItsSubjectIdentity() {
        String constraintIri = givenConstraint(PROJECT_A, "TCON-1");

        ResourceId resolved = constraintLookup.resolveByCode(PROJECT_A, "TCON-1");

        assertEquals(ResourceId.of(constraintIri), resolved);
    }

    @Test
    void rejectsAnUnknownConstraintCode() {
        givenConstraint(PROJECT_A, "TCON-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> constraintLookup.resolveByCode(PROJECT_A, "TCON-99"));

        assertTrue(ex.getMessage().contains("TCON-99"), ex.getMessage());
        assertTrue(ex.getMessage().contains("constraint_add"), ex.getMessage());
    }

    @Test
    void rejectsAnAmbiguousConstraintCode() {
        givenConstraintAtIri(PROJECT_A, "https://w3id.org/arknet/model/constraint/dup-1", "TCON-1");
        givenConstraintAtIri(PROJECT_A, "https://w3id.org/arknet/model/constraint/dup-2", "TCON-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> constraintLookup.resolveByCode(PROJECT_A, "TCON-1"));

        assertTrue(ex.getMessage().contains("ambiguous"), ex.getMessage());
    }

    /** A constraint of another project must not satisfy this project's reference. */
    @Test
    void aConstraintOfAnotherProjectDoesNotSatisfyThisProjectsReference() {
        givenConstraint(PROJECT_B, "TCON-1");

        assertThrows(UnresolvedReferenceException.class,
                () -> constraintLookup.resolveByCode(PROJECT_A, "TCON-1"));
    }

    /**
     * Writes a constraint straight into the requirements bounded context's constraints graph of
     * the shared project dataset - deliberately via raw SPARQL rather than
     * {@code KognioRdfConstraintRepository}, so this test does not couple the two bounded
     * contexts. Returns the constraint's IRI.
     */
    private String givenConstraint(ProjectId projectId, String constraintCode) {
        String constraintIri = "https://w3id.org/arknet/model/constraint/" + constraintCode;
        givenConstraintAtIri(projectId, constraintIri, constraintCode);
        return constraintIri;
    }

    private void givenConstraintAtIri(ProjectId projectId, String constraintIri, String identifier) {
        String insert = "INSERT DATA { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + "<" + constraintIri + "> a <https://w3id.org/arknet/requirements#TechnicalConstraint> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + identifier + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }
}
