// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Resolves the handle a caller names a resource by - a CURIE, a full IRI or a bare business code
 * - to the IRI the model carries: the in-port {@code impact_analysis} needs before it can ask the
 * graph what depends on that resource.
 */
public interface ResolveResourceHandle {

    /**
     * @param projectId the project the handle is resolved in
     * @param handle    CURIE ({@code req:FR-1}), full IRI, or bare business code ({@code FR-1})
     * @return the resolved IRI; an unresolvable handle comes back unchanged rather than as an
     *         error, so the caller can report it as an unknown resource in its own words
     */
    String resolve(ProjectId projectId, String handle);
}
