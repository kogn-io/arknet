// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.out;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Finds the resource a handle names in a project's store, so a document may name an existing
 * resource by its code ({@code REQ-12}) instead of its IRI.
 */
public interface HandleLookup {

    /**
     * Resolves a handle to the IRI of the resource it names.
     *
     * @param projectId the project whose resources the handle names
     * @param handle    a business id such as {@code REQ-12}
     * @return the IRI of the one resource the handle names
     * @throws IllegalArgumentException naming the reason if the handle names no resource or more than one
     */
    String resolve(ProjectId projectId, String handle);
}
