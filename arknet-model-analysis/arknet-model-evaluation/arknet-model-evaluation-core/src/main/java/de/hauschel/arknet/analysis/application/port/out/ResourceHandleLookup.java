// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.application.port.out;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Resolves a caller's resource handle against the store, for the one tool that takes a resource
 * as an argument instead of reading the whole model.
 *
 * <p>Separate from {@link ModelSnapshots} because resolving a bare business code asks the store a
 * question of its own ({@code dcterms:identifier}) rather than filtering a snapshot already
 * read.</p>
 */
public interface ResourceHandleLookup {

    /**
     * @param projectId the project the handle is resolved in
     * @param handle    CURIE, full IRI, or bare business code
     * @return the resolved IRI, or the handle unchanged when nothing matches
     */
    String resolve(ProjectId projectId, String handle);
}
