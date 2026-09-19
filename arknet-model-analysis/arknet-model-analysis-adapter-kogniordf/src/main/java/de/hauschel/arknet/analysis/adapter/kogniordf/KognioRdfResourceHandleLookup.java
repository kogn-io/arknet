// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.adapter.kogniordf;

import java.util.Objects;

import de.hauschel.arknet.analysis.application.port.out.ResourceHandleLookup;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.HandleResolver;

/**
 * Serves {@link ResourceHandleLookup} with the shared handle contract: a CURIE and a full IRI are
 * resolved from the prefix bindings alone, a bare business code costs one {@code
 * dcterms:identifier} query against the project's dataset.
 *
 * <p>Deliberately the same resolver the composition root's {@code resource_get} uses rather than
 * a second reading of the same contract, so {@code impact_analysis} and {@code resource_get}
 * never disagree about what {@code FR-1} means.</p>
 */
public final class KognioRdfResourceHandleLookup implements ResourceHandleLookup {

    private final HandleResolver handleResolver;

    /** @param handleResolver the shared CURIE / IRI / bare-code resolver */
    public KognioRdfResourceHandleLookup(final HandleResolver handleResolver) {
        this.handleResolver = Objects.requireNonNull(handleResolver, "handleResolver");
    }

    @Override
    public String resolve(final ProjectId projectId, final String handle) {
        return handleResolver.resolve(projectId, handle);
    }
}
