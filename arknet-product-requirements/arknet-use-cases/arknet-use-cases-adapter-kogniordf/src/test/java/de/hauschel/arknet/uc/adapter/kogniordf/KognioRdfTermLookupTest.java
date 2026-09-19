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
import de.hauschel.arknet.uc.application.port.out.TermLookup;

/**
 * Integration test for {@link KognioRdfTermLookup} against an in-memory RDF4J-backed
 * kognio-rdf store (issue #329). Mirrors {@link KognioRdfRequirementLookupTest} exactly, one
 * sibling graph over.
 */
class KognioRdfTermLookupTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private TermLookup termLookup;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        termLookup = new KognioRdfTermLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void resolvesAKnownTermCodeToItsSubjectIdentity() {
        String termIri = givenTerm(PROJECT_A, "TERM-1");

        ResourceId resolved = termLookup.resolveByCode(PROJECT_A, "TERM-1");

        assertEquals(ResourceId.of(termIri), resolved);
    }

    @Test
    void rejectsAnUnknownTermCode() {
        givenTerm(PROJECT_A, "TERM-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> termLookup.resolveByCode(PROJECT_A, "TERM-99"));

        assertTrue(ex.getMessage().contains("TERM-99"), ex.getMessage());
        assertTrue(ex.getMessage().contains("term_add"), ex.getMessage());
    }

    @Test
    void rejectsAnAmbiguousTermCode() {
        givenTermAtIri(PROJECT_A, "https://w3id.org/arknet/model/term/dup-1", "TERM-1");
        givenTermAtIri(PROJECT_A, "https://w3id.org/arknet/model/term/dup-2", "TERM-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> termLookup.resolveByCode(PROJECT_A, "TERM-1"));

        assertTrue(ex.getMessage().contains("ambiguous"), ex.getMessage());
    }

    /** A term of another project must not satisfy this project's reference. */
    @Test
    void aTermOfAnotherProjectDoesNotSatisfyThisProjectsReference() {
        givenTerm(PROJECT_B, "TERM-1");

        assertThrows(UnresolvedReferenceException.class, () -> termLookup.resolveByCode(PROJECT_A, "TERM-1"));
    }

    /**
     * Writes a term straight into the sibling ubiquitous-language graph of the shared project
     * dataset - deliberately via raw SPARQL rather than the ul adapter, so this test does not
     * couple the two bounded contexts. Returns the term's IRI.
     */
    private String givenTerm(ProjectId projectId, String termCode) {
        String termIri = "https://w3id.org/arknet/model/term/" + termCode;
        givenTermAtIri(projectId, termIri, termCode);
        return termIri;
    }

    private void givenTermAtIri(ProjectId projectId, String termIri, String identifier) {
        String insert = "INSERT DATA { GRAPH <" + TERMS_GRAPH + "> { "
                + "<" + termIri + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + identifier + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }
}
