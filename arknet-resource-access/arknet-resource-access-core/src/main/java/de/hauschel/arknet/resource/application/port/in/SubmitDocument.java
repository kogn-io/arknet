// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Applies a submitted Turtle document of activities ({@code arkres:Create}, {@code arkres:Update},
 * {@code arkres:AddTo}, {@code arkres:RemoveFrom}, {@code arkres:Delete}) to the resources of one
 * project: all of it in one transaction, or nothing (ADR-59). Every resource the document changes
 * gets one revision (ADR-66).
 *
 * <p>The port carries strings and kernel types only: the driving adapter that calls it knows no
 * RDF technology.</p>
 */
public interface SubmitDocument {

    /**
     * Applies the document.
     *
     * @param projectId the project whose resources the document changes
     * @param document  the Turtle document, with its own prefix declarations
     * @return what the store now holds differently
     * @throws de.hauschel.arknet.resource.domain.SubmitRejectedException if the document is not
     *         valid Turtle, states an activity the store cannot apply, or leaves a resource that
     *         violates its shapes; the store is unchanged
     */
    SubmitResult submit(ProjectId projectId, String document);
}
