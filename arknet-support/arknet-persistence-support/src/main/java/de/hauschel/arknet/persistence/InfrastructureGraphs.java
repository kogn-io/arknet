// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.List;
import java.util.stream.Stream;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.versioning.VersioningGraphs;

/**
 * The named graphs a project dataset holds besides its {@link ContentGraph content graph}: the
 * library versioning's own graphs (published view, history, content store, configuration), the
 * retained codes of deleted resources ({@link VersionedWriteFunnel#RETAINED_CODES_GRAPH}) and the
 * project's self-description ({@link ArkprjVocabulary#IDENTITY_GRAPH}). Nobody wrote them as part
 * of the model; every read path that shows the model reads the content graph alone, and this list
 * names what it leaves out.
 */
public final class InfrastructureGraphs {

    /** The infrastructure graphs as absolute IRI strings, without angle brackets. */
    public static final List<String> IRIS = Stream.concat(
            VersioningGraphs.ALL.stream().map(IRI::getIRIString),
            Stream.of(VersionedWriteFunnel.RETAINED_CODES_GRAPH, ArkprjVocabulary.IDENTITY_GRAPH))
            .toList();

    private InfrastructureGraphs() {
    }
}
