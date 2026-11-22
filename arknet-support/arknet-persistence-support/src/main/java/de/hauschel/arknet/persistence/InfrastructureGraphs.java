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
 * project's self-description ({@link ArkprjVocabulary#IDENTITY_GRAPH}). Nobody wrote them as part of the
 * model.
 *
 * <p>No read path filters by this list: every read of the model names the content graph alone,
 * which is what leaves these graphs out. The list documents what a dataset holds besides the
 * model, and tests use it to check that none of these graphs reaches a model read.</p>
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
