// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

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

import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;

/**
 * Integration test for {@link KognioRdfTermLookup} against an in-memory RDF4J-backed
 * kognio-rdf store.
 *
 * <p>Structurally 1:1 to the requirements adapter's homonymous test - both classes resolve a
 * glossary term's {@code dcterms:identifier} against the very same shared-workspace schema.</p>
 */
class KognioRdfTermLookupTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("a");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("b");
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";

    private DatasetLifecycleRdf4j lifecycle;
    private TermLookup termLookup;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-bc-term-lookup-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        termLookup = new KognioRdfTermLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void resolvesAKnownTermCodeToItsSubjectIdentity() {
        String termIri = givenTerm(WORKSPACE_A, "TERM-1");

        ResourceId resolved = termLookup.resolveByCode(WORKSPACE_A, "TERM-1");

        assertEquals(ResourceId.of(termIri), resolved);
    }

    /**
     * The edge resolves via the term's {@code dcterms:identifier}, never its
     * {@code skos:prefLabel} - so an unknown identity is rejected even though a concept with
     * that text as a label exists.
     */
    @Test
    void rejectsAnUnknownTermCode() {
        givenTerm(WORKSPACE_A, "TERM-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> termLookup.resolveByCode(WORKSPACE_A, "TERM-99"));

        assertTrue(ex.getMessage().contains("TERM-99"), ex.getMessage());
        assertTrue(ex.getMessage().contains("term_add"), ex.getMessage());
    }

    @Test
    void rejectsAnAmbiguousTermCode() {
        givenTermAtIri(WORKSPACE_A, "https://w3id.org/arknet/model/term/dup-1", "TERM-1");
        givenTermAtIri(WORKSPACE_A, "https://w3id.org/arknet/model/term/dup-2", "TERM-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> termLookup.resolveByCode(WORKSPACE_A, "TERM-1"));

        assertTrue(ex.getMessage().contains("ambiguous"), ex.getMessage());
    }

    /** A term of another workspace must not satisfy this workspace's reference. */
    @Test
    void aTermOfAnotherWorkspaceDoesNotSatisfyThisWorkspacesReference() {
        givenTerm(WORKSPACE_B, "TERM-1");

        assertThrows(UnresolvedReferenceException.class,
                () -> termLookup.resolveByCode(WORKSPACE_A, "TERM-1"));
    }

    /**
     * Writes a glossary term straight into the sibling terms graph of the shared workspace
     * dataset - deliberately via raw SPARQL rather than the ubiquitous-language adapter, so
     * this test does not couple the two bounded contexts. Returns the term's IRI.
     */
    private String givenTerm(WorkspaceId workspaceId, String termId) {
        String termIri = "https://w3id.org/arknet/model/term/" + termId;
        givenTermAtIri(workspaceId, termIri, termId);
        return termIri;
    }

    private void givenTermAtIri(WorkspaceId workspaceId, String termIri, String identifier) {
        String insert = "INSERT DATA { GRAPH <" + TERMS_GRAPH + "> { "
                + "<" + termIri + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + identifier + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Anmeldung\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }
}
