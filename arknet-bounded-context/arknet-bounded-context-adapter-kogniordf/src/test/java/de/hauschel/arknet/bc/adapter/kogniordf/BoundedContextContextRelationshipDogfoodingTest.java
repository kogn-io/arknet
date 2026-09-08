// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.application.port.in.BoundedContextDetail;
import de.hauschel.arknet.bc.application.port.in.RelatedContext;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.ContextRelationshipNotFoundException;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;

/**
 * Dogfooding-shaped end-to-end test for issue #565's own "done" criterion: {@code bc_get BC-5}
 * shows four outgoing and one incoming context relationship - the exact shape arknet's own store
 * carries for its BC-5 (arknet-mcp) since #438, reproduced here against a throwaway, in-memory
 * fixture rather than the shared local daemon's real store (which still runs the pre-#565 jar and
 * must never receive a write call from a test).
 *
 * <p>Exercises the full stack this issue changed: {@link BoundedContextService} over the real,
 * RDF4J-backed {@link KognioRdfBoundedContextRepository}/{@link KognioRdfContextRelationshipRepository}
 * out-adapters - not the in-memory fakes {@code BoundedContextServiceTest} uses - so a
 * read/write mismatch between the two out-adapters (e.g. a predicate the write side serialises
 * differently than the read side expects) would show up here even though neither adapter's own
 * isolated test would catch it.</p>
 */
class BoundedContextContextRelationshipDogfoodingTest {

    private static final ProjectId PROJECT = new ProjectId("dogfooding-565");

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private BoundedContextService service;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        BoundedContextRepository boundedContexts = KognioRdfBoundedContextRepositoryFactory.over(
                datasetLifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        ContextRelationshipRepository contextRelationships =
                KognioRdfContextRelationshipRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT);
        TermLookup unusedTermLookup = (projectId, termCode) -> {
            throw new UnsupportedOperationException("not exercised by this test");
        };
        service = new BoundedContextService(
                boundedContexts, new UuidResourceIdFactory(), unusedTermLookup, contextRelationships);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void bcGetShowsFourOutgoingAndOneIncomingContextRelationship() {
        BoundedContextCode bc1 = add("Requirements");
        BoundedContextCode bc2 = add("UbiquitousLanguage");
        BoundedContextCode bc3 = add("UseCases");
        BoundedContextCode bc4 = add("BoundedContext");
        BoundedContextCode bc5 = add("Mcp");
        BoundedContextCode bc6 = add("Adr");

        // BC-5 is upstream of four sibling contexts (arknet-mcp is the composition root every other
        // hexagon's In-Port is wired into) ...
        service.linkContext(PROJECT, bc5, bc1, RelationshipType.CUSTOMER_SUPPLIER);
        service.linkContext(PROJECT, bc5, bc2, RelationshipType.CUSTOMER_SUPPLIER);
        service.linkContext(PROJECT, bc5, bc3, RelationshipType.CUSTOMER_SUPPLIER);
        service.linkContext(PROJECT, bc5, bc4, RelationshipType.CUSTOMER_SUPPLIER);
        // ... and downstream of exactly one (arknet-adr publishes its own decision model, which the
        // report renders).
        service.linkContext(PROJECT, bc6, bc5, RelationshipType.PUBLISHED_LANGUAGE);

        BoundedContextDetail detail = service.get(PROJECT, bc5, null).orElseThrow();

        assertEquals(5, detail.relationships().size());
        Map<RelatedContext.Direction, List<RelatedContext>> byDirection = detail.relationships().stream()
                .collect(Collectors.groupingBy(RelatedContext::direction));
        assertEquals(4, byDirection.getOrDefault(RelatedContext.Direction.UPSTREAM_OF, List.of()).size());
        assertEquals(1, byDirection.getOrDefault(RelatedContext.Direction.DOWNSTREAM_OF, List.of()).size());
        assertEquals(List.of(bc1, bc2, bc3, bc4).stream()
                        .sorted(Comparator.comparing(BoundedContextCode::value)).toList(),
                byDirection.get(RelatedContext.Direction.UPSTREAM_OF).stream()
                        .map(RelatedContext::peerCode)
                        .sorted(Comparator.comparing(BoundedContextCode::value))
                        .toList());
        assertEquals(bc6, byDirection.get(RelatedContext.Direction.DOWNSTREAM_OF).get(0).peerCode());
    }

    /** {@code bc_list} shows the same relationships as {@code bc_get}, one context at a time. */
    @Test
    void bcListShowsTheSameEdgesAsBcGetForEveryContext() {
        BoundedContextCode upstream = add("Upstream");
        BoundedContextCode downstream = add("Downstream");
        service.linkContext(PROJECT, upstream, downstream, RelationshipType.OPEN_HOST_SERVICE);

        List<BoundedContextDetail> all = service.list(PROJECT, null);

        BoundedContextDetail upstreamDetail = all.stream()
                .filter(d -> d.context().code().equals(upstream)).findFirst().orElseThrow();
        BoundedContextDetail downstreamDetail = all.stream()
                .filter(d -> d.context().code().equals(downstream)).findFirst().orElseThrow();
        assertEquals(1, upstreamDetail.relationships().size());
        assertEquals(RelatedContext.Direction.UPSTREAM_OF, upstreamDetail.relationships().get(0).direction());
        assertEquals(1, downstreamDetail.relationships().size());
        assertEquals(RelatedContext.Direction.DOWNSTREAM_OF, downstreamDetail.relationships().get(0).direction());
    }

    /**
     * The reverse of the dogfooding scenario above: removing a relationship makes it disappear from
     * {@code bc_get} - the read path issue #565 added must reflect {@code bc_unlink_context}
     * immediately, not just {@code bc_link_context}.
     */
    @Test
    void unlinkedRelationshipNoLongerAppearsInBcGet() {
        BoundedContextCode upstream = add("Upstream");
        BoundedContextCode downstream = add("Downstream");
        service.linkContext(PROJECT, upstream, downstream, RelationshipType.ANTICORRUPTION_LAYER);

        service.unlinkContext(PROJECT, upstream, downstream, RelationshipType.ANTICORRUPTION_LAYER);

        assertEquals(List.of(), service.get(PROJECT, upstream, null).orElseThrow().relationships());
        assertEquals(List.of(), service.get(PROJECT, downstream, null).orElseThrow().relationships());
        assertThrows(ContextRelationshipNotFoundException.class, () -> service.unlinkContext(
                PROJECT, upstream, downstream, RelationshipType.ANTICORRUPTION_LAYER));
    }

    private BoundedContextCode add(String name) {
        return service.add(PROJECT, new NewBoundedContext(name, name + " does something useful here.",
                null, null, "en"), null).code();
    }
}
