// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The one content graph of a project dataset (ADR-63): every model resource of a project lives in
 * it, whatever its type, and resource types differ by their class, not by their graph. It is the
 * draft view of the library versioning (ADR-61), so every read path names it explicitly - a query
 * without {@code GRAPH} would also see the published view and the library's own graphs.
 */
public final class ContentGraph {

    /** The content graph as an absolute IRI string, without angle brackets. */
    public static final String GRAPH_IRI = "https://w3id.org/arknet/model/content";

    /** The content graph as a SPARQL {@code IRIREF}, i.e. wrapped in angle brackets. */
    public static final String SPARQL_IRIREF = "<" + GRAPH_IRI + ">";

    private ContentGraph() {
    }
}
