// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfAdrRepositoryFactory;
import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.mcp.store.StoreReader;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * The ADR side of the traceability traversal (issue #69), seeded through the real repositories of
 * the requirements, bounded-context and ADR hexagons - never hand-written triples - and queried
 * through {@link TraceabilityGraph} directly.
 *
 * <p>Kept in its own class rather than folded into {@link TraceabilityGraphTest}: an ADR pointing at
 * that fixture's requirements and bounded context would enlarge every one of its
 * {@code dependents}-based expectations, turning a focused regression suite into one that has to be
 * re-counted whenever a new edge type joins the graph.</p>
 */
class TraceabilityGraphAdrEdgesTest {

    private static final ProjectId PROJECT = new ProjectId("trace-graph-adr-test");

    private static final String FR_1_IRI = "https://w3id.org/arknet/id/trace-adr-fr-1";
    private static final String BC_1_IRI = "https://w3id.org/arknet/id/trace-adr-bc-1";
    private static final String ADR_1_IRI = "https://w3id.org/arknet/id/trace-adr-adr-1";
    private static final String ADR_2_IRI = "https://w3id.org/arknet/id/trace-adr-adr-2";
    private static final String ADR_3_IRI = "https://w3id.org/arknet/id/trace-adr-adr-3";
    private static final String ADR_4_IRI = "https://w3id.org/arknet/id/trace-adr-adr-4";
    private static final String ADR_GRAPH = "https://w3id.org/arknet/model/adr";

    @TempDir
    Path storageDir;

    private DatasetLifecycle lifecycle;
    private TraceabilityGraph graph;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        RequirementRepository requirements =
                KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        BoundedContextRepository boundedContexts = KognioRdfBoundedContextRepositoryFactory.over(
                lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        AdrRepository adrs =
                KognioRdfAdrRepositoryFactory.over(lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);

        requirements.create(PROJECT, new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(), List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of()), null);
        boundedContexts.create(PROJECT, new BoundedContext(
                new BoundedContextId(ResourceId.of(BC_1_IRI)), new BoundedContextCode("BC-1"), "Ordering",
                "Wir verarbeiten Bestellungen.", null, null, List.of()));

        // ADR-2 (the successor) must exist before ADR-1 is written: ashapes:ADR-supersededBy
        // carries sh:class arkarch:ArchitectureDecisionRecord, and the write gate's asserted
        // context for that constraint is read from the store, not from the candidate being
        // written - so the successor has to be committed first.
        adrs.create(PROJECT, new Adr(
                new AdrId(ResourceId.of(ADR_2_IRI)), new AdrCode("ADR-2"), "Swap the store",
                AdrStatus.ACCEPTED, "The embedded store no longer covers the team case.",
                "Move to a remote endpoint behind the same out-port.", null, null, null,
                List.of(), List.of(), null, List.of()), "de");
        // ADR-1 addresses FR-1 and affects BC-1; ADR-2 supersedes ADR-1 (kogn-io/arknet#357: the
        // supersededBy edge - and the SUPERSEDED status it is coupled to - is written on the
        // superseded decision, ADR-1, pointing at its successor ADR-2).
        adrs.create(PROJECT, new Adr(
                new AdrId(ResourceId.of(ADR_1_IRI)), new AdrCode("ADR-1"), "Use an embedded triple store",
                AdrStatus.SUPERSEDED, "A single-user client must work without a server.",
                "Use kognio-rdf behind an out-port.", null, null, null,
                List.of(new RequirementRef(ResourceId.of(FR_1_IRI))),
                List.of(new BoundedContextRef(ResourceId.of(BC_1_IRI))),
                new AdrId(ResourceId.of(ADR_2_IRI)), List.of()), "de");

        // ADR-4 legacy-supersedes ADR-3 (pre-#357 shape, store-first only - no tool writes it any
        // more): a raw triple, inserted directly rather than through any repository, the same way
        // store-first data would have arrived before this issue.
        adrs.create(PROJECT, new Adr(
                new AdrId(ResourceId.of(ADR_3_IRI)), new AdrCode("ADR-3"), "Store data in files",
                AdrStatus.DEPRECATED, "An earlier storage choice.",
                "Use plain files behind an out-port.", null, null, null,
                List.of(), List.of(), null, List.of()), "de");
        adrs.create(PROJECT, new Adr(
                new AdrId(ResourceId.of(ADR_4_IRI)), new AdrCode("ADR-4"), "Swap to files, then back",
                AdrStatus.PROPOSED, "The file-based approach did not scale either.",
                "Revisit the embedded triple store.", null, null, null,
                List.of(), List.of(), null, List.of()), "de");
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + ADR_4_IRI
                        + "> <https://w3id.org/arknet/architecture#supersedes> <" + ADR_3_IRI + "> } }");
                return null;
            });
        }

        StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
        graph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(PROJECT.value()));
    }

    /**
     * Changing a requirement affects the decision that addresses it - and, transitively, the decision
     * that supersedes that one.
     */
    @Test
    void dependentsOfARequirementReachTheAddressingAdrAndItsSuccessor() {
        assertThat(graph.dependents(FR_1_IRI)).containsExactlyInAnyOrder(ADR_1_IRI, ADR_2_IRI);
    }

    /** Changing a bounded context affects the decision that declares itself affected by it. */
    @Test
    void dependentsOfABoundedContextReachTheAffectingAdr() {
        assertThat(graph.dependents(BC_1_IRI)).containsExactlyInAnyOrder(ADR_1_IRI, ADR_2_IRI);
    }

    /** Changing a decision affects the decision that supersedes it. */
    @Test
    void dependentsOfASupersededAdrReachItsSuccessor() {
        assertThat(graph.dependents(ADR_1_IRI)).containsExactly(ADR_2_IRI);
    }

    /**
     * The backward closure is one-directional: the newest decision is the one nothing points at, so
     * nothing depends on it.
     */
    @Test
    void dependentsOfTheSupersedingAdrAreEmpty() {
        assertThat(graph.dependents(ADR_2_IRI)).isEmpty();
    }

    /**
     * The pre-#357 legacy shape stays reachable backwards, exactly as the current-model edge is
     * reachable forwards in {@link #dependentsOfASupersededAdrReachItsSuccessor()}: a store-first
     * {@code arkarch:supersedes} triple still lets a changed predecessor reach its successor.
     */
    @Test
    void dependentsOfALegacySupersededAdrReachItsSuccessor() {
        assertThat(graph.dependents(ADR_3_IRI)).containsExactly(ADR_4_IRI);
    }

    /** The digest names decisions by their business code, never by their opaque IRI. */
    @Test
    void adrsCarryTheirBusinessCodeAsTheirDisplayHandle() {
        assertThat(graph.identifierOf(ADR_1_IRI)).contains("ADR-1");
        assertThat(graph.identifierOf(ADR_2_IRI)).contains("ADR-2");
    }
}
