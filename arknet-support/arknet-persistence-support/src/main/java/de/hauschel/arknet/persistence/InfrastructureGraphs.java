// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.List;

/**
 * The named graphs every project dataset holds besides its model: the revision trail
 * ({@link ArkprovVocabulary#PROVENANCE_GRAPH}) and the project's self-description
 * ({@link ArkprjVocabulary#IDENTITY_GRAPH}). Nobody wrote them as part of the model, so every
 * reader that separates "model content" from "infrastructure" - {@link StoreReader} for the store
 * report, the project registry's dataset counting for deletion - takes the set from here and the
 * two cannot drift apart.
 */
public final class InfrastructureGraphs {

    /** The infrastructure graphs as absolute IRI strings, without angle brackets. */
    public static final List<String> IRIS = List.of(
            ArkprovVocabulary.PROVENANCE_GRAPH,
            ArkprjVocabulary.IDENTITY_GRAPH);

    /** The infrastructure graphs as SPARQL {@code IRIREF}s, i.e. each wrapped in angle brackets. */
    public static final List<String> SPARQL_IRIREFS = IRIS.stream().map(iri -> "<" + iri + ">").toList();

    /**
     * A SPARQL filter expression that holds for a graph variable bound to anything but an
     * infrastructure graph, e.g. {@code ?g != <a> && ?g != <b>}.
     *
     * @param graphVariable the graph variable including its leading question mark, e.g. {@code ?g}
     * @return the expression, to be placed inside {@code FILTER( ... )}
     */
    public static String outsideExpression(final String graphVariable) {
        return SPARQL_IRIREFS.stream()
                .map(graph -> graphVariable + " != " + graph)
                .reduce((left, right) -> left + " && " + right)
                .orElseThrow();
    }

    private InfrastructureGraphs() {
    }
}
