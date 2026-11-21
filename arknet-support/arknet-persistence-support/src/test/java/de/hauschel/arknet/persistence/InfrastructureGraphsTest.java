// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class InfrastructureGraphsTest {

    /** Pins the set itself: a graph added to or dropped from it changes what every reader hides. */
    @Test
    void theInfrastructureGraphsAreTheRevisionTrailAndTheSelfDescription() {
        assertEquals(List.of(ArkprovVocabulary.PROVENANCE_GRAPH, ArkprjVocabulary.IDENTITY_GRAPH),
                InfrastructureGraphs.IRIS);
    }

    @Test
    void theSparqlFormWrapsEveryGraphInAngleBrackets() {
        assertEquals(List.of("<" + ArkprovVocabulary.PROVENANCE_GRAPH + ">",
                "<" + ArkprjVocabulary.IDENTITY_GRAPH + ">"), InfrastructureGraphs.SPARQL_IRIREFS);
    }

    @Test
    void theOutsideExpressionExcludesEveryGraph() {
        assertEquals("?g != <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> && ?g != <"
                + ArkprjVocabulary.IDENTITY_GRAPH + ">", InfrastructureGraphs.outsideExpression("?g"));
    }
}
