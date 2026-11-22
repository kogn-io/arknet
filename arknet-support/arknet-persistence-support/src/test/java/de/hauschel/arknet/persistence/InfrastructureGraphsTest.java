// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

class InfrastructureGraphsTest {

    /** Pins the set itself: a graph added to or dropped from it changes what the model leaves out. */
    @Test
    void theInfrastructureGraphsAreTheLibraryGraphsTheRetainedCodesTheIssuedFragmentsAndTheSelfDescription() {
        assertEquals(List.of(
                "https://w3id.org/kognio/rdf/versioning/graph/published",
                "https://w3id.org/kognio/rdf/versioning/graph/content-store",
                "https://w3id.org/kognio/rdf/versioning/graph/history",
                "https://w3id.org/kognio/rdf/versioning/graph/config",
                VersionedWriteFunnel.RETAINED_CODES_GRAPH,
                ChildFragments.ISSUED_FRAGMENTS_GRAPH,
                ArkprjVocabulary.IDENTITY_GRAPH), InfrastructureGraphs.IRIS);
    }

    @Test
    void theContentGraphIsNoInfrastructureGraph() {
        assertFalse(InfrastructureGraphs.IRIS.contains(ContentGraph.GRAPH_IRI));
    }
}
