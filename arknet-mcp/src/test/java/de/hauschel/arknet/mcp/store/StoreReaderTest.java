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

import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
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
    private RequirementRepository requirements;
    private StoreReader storeReader;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle);
        requirements.create(WORKSPACE, requirementTitled("Login"));
        storeReader = new StoreReader(lifecycle);
    }

    private static Requirement requirementTitled(String title) {
        return new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), title,
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of("Login succeeds with valid credentials"));
    }

    /** Reads {@code updated}'s current head and immediately applies it through the CAS guard. */
    private void replaceViaCompareAndUpdate(Requirement updated) {
        String head = requirements.findCurrentByCode(WORKSPACE, updated.code())
                .map(RequirementRepository.CurrentRequirement::head)
                .orElse(null);
        requirements.compareAndUpdate(WORKSPACE, head, updated);
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

    /**
     * Every guarded write records a PROV-O revision plus a head pointer into the provenance
     * graph (ADR-014), and that trail grows with every write, forever. None of the three read
     * paths surfaces it: the snapshot feeds the store report, a view of the model rather than of
     * its change history, and the head pointer stays hidden even though every user-reachable
     * write now moves it through the funnel (issue #167 resolved {@code req_update}, {@code
     * req_set_status}, {@code req_link_term} and {@code term_update} into it, ADR-014 decision 4)
     * - whether and how to expose it through this generic read path is a separate, still open
     * decision, not gated on the head being a usable token any more.
     */
    @Test
    void noReadPathSurfacesTheProvenanceGraph() {
        assertThat(provenanceStatementCount())
                .as("the write in setUp must have recorded a revision - else this test is vacuous")
                .isPositive();

        assertThat(storeReader.outgoing(WORKSPACE, FR_1_IRI)).noneMatch(StoreReaderTest::isProvenance);
        assertThat(storeReader.incoming(WORKSPACE, FR_1_IRI)).noneMatch(StoreReaderTest::isProvenance);
        assertThat(snapshotTriples()).noneMatch(StoreReaderTest::isProvenance);
    }

    /**
     * The generic read path must not grow with the revision trail: every revision names its
     * resource via {@code prov:specializationOf} and rewrites its head, so an unfiltered view
     * would add rows per write, without bound. The revisions themselves are not model resources
     * either - reaching one by its IRI yields nothing.
     */
    @Test
    void furtherWritesGrowTheTrailInTheStoreButNotTheReadPath() {
        List<Triple> incomingAfterOneWrite = storeReader.incoming(WORKSPACE, FR_1_IRI);
        List<Triple> outgoingAfterOneWrite = storeReader.outgoing(WORKSPACE, FR_1_IRI);
        long trailAfterOneWrite = provenanceStatementCount();
        String firstHead = headIri();

        replaceViaCompareAndUpdate(requirementTitled("Login v2"));
        replaceViaCompareAndUpdate(requirementTitled("Login v3"));

        assertThat(provenanceStatementCount())
                .as("the two updates must have extended the trail in the store")
                .isGreaterThan(trailAfterOneWrite);
        assertThat(headIri())
                .as("and moved the head - so the read path is hiding something that really changed")
                .isNotEqualTo(firstHead);

        assertThat(storeReader.incoming(WORKSPACE, FR_1_IRI))
                .as("two further writes must not add neighbour rows")
                .hasSameSizeAs(incomingAfterOneWrite);
        assertThat(storeReader.outgoing(WORKSPACE, FR_1_IRI))
                .as("nor statement rows")
                .hasSameSizeAs(outgoingAfterOneWrite);
        assertThat(storeReader.outgoing(WORKSPACE, headIri()))
                .as("a revision is not a model resource - the generic read path does not reach it")
                .isEmpty();
    }

    private static boolean isProvenance(Triple triple) {
        return triple.predicate().equals(ArkprovVocabulary.HEAD)
                || triple.predicate().equals(ArkprovVocabulary.SPECIALIZATION_OF);
    }

    private List<Triple> snapshotTriples() {
        return storeReader.readSnapshot(WORKSPACE).resources().stream()
                .flatMap(resource -> resource.outgoing().stream())
                .toList();
    }

    /** Reads the trail straight from the store - the read path under test cannot show it. */
    private long provenanceStatementCount() {
        return selectCount("SELECT ?s ?p ?o WHERE { GRAPH <"
                + ArkprovVocabulary.PROVENANCE_GRAPH + "> { ?s ?p ?o } }");
    }

    private String headIri() {
        String query = "SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + FR_1_IRI + "> <" + ArkprovVocabulary.HEAD + "> ?v } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .findFirst()
                    .orElseThrow();
        }
    }

    private long selectCount(String query) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE.value()))) {
            return handle.sparqlQuery().select(query).count();
        }
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
