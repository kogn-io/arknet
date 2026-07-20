// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Unit tests for {@link StoreReader#outgoing(WorkspaceId, String)} and
 * {@link StoreReader#incoming(WorkspaceId, String)}: they must reject a resource handle that
 * cannot appear unescaped inside a SPARQL {@code IRIREF} instead of splicing it into the query
 * text (issue #105 - SPARQL injection via {@code resource_get}'s {@code id} parameter).
 */
class StoreReaderTest {

    private static final WorkspaceId WORKSPACE = new WorkspaceId("noistill");
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/store-reader-test-fr-1";

    /**
     * A handle carrying a payload that, if concatenated unescaped into {@code "<" + iri + ">"},
     * breaks out of the IRIREF and splices live SPARQL syntax into the query (the exact shape
     * reported in issue #105).
     */
    private static final String INJECTION_PAYLOAD =
            "https://x/a> } UNION { ?s ?p ?o . FILTER(1=1) #";

    @TempDir
    Path storageDir;

    private DatasetLifecycle lifecycle;
    private StoreReader storeReader;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        RequirementRepository requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle);
        requirements.create(WORKSPACE, new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of("Login succeeds with valid credentials")));
        storeReader = new StoreReader(lifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(WORKSPACE.value()));
    }

    @Test
    void outgoingRejectsAnIriThatCannotAppearUnescapedInASparqlIrirefInsteadOfExecutingIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> storeReader.outgoing(WORKSPACE, INJECTION_PAYLOAD));
    }

    @Test
    void incomingRejectsAnIriThatCannotAppearUnescapedInASparqlIrirefInsteadOfExecutingIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> storeReader.incoming(WORKSPACE, INJECTION_PAYLOAD));
    }

    @Test
    void outgoingStillReturnsTheStatementsOfAWellFormedIri() {
        List<Triple> outgoing = storeReader.outgoing(WORKSPACE, FR_1_IRI);

        assertThat(outgoing).isNotEmpty();
        assertThat(outgoing).allMatch(triple -> triple.subject().equals(FR_1_IRI));
    }

    /**
     * Regression test: the requirement adapter writes into a named graph
     * ({@code REQUIREMENTS_GRAPH}), and {@link StoreReader}'s queries union a plain triple
     * pattern with an explicit {@code GRAPH ?g} pattern to also reach named-graph data. Without
     * {@code DISTINCT}, a backend whose plain pattern already spans every context (as the
     * RDF4J-based adapter does) matches each named-graph triple twice, doubling every row in
     * the generated store report.
     */
    @Test
    void outgoingDoesNotDuplicateStatementsLivingInANamedGraph() {
        List<Triple> outgoing = storeReader.outgoing(WORKSPACE, FR_1_IRI);

        Set<Triple> distinct = new HashSet<>(outgoing);
        assertThat(outgoing).hasSameSizeAs(distinct);
    }

    @Test
    void readSnapshotDoesNotDuplicateStatementsLivingInANamedGraph() {
        StoreSnapshot snapshot = storeReader.readSnapshot(WORKSPACE);

        List<Triple> triples = snapshot.resources().stream()
                .flatMap(resource -> resource.outgoing().stream())
                .toList();
        Set<Triple> distinct = new HashSet<>(triples);
        assertThat(triples).hasSameSizeAs(distinct);
    }
}
