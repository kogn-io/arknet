// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: register a new bounded context.
 *
 * <p>Backs the MVP tool {@code bc_add}. Identity assignment (the opaque
 * {@link de.hauschel.arknet.bc.domain.BoundedContextId}) and business-code assignment
 * ({@code BC-N}) are policy of the implementing application service.</p>
 */
public interface AddBoundedContext {

    /**
     * Adds a new bounded context.
     *
     * @param projectId the project (architecture model) to add the bounded context to
     * @param command     the data describing the bounded context to create
     * @return the persisted bounded context including its assigned identity and code
     */
    BoundedContext add(ProjectId projectId, NewBoundedContext command);

    /**
     * Input data for {@link #add(ProjectId, NewBoundedContext)}.
     *
     * @param name         the context's human-readable name (e.g. {@code OrderManagement})
     * @param domainVision one sentence stating what this context does and why it exists
     * @param subdomain    strategic subdomain classification; optional (may be {@code null})
     * @param ownedBy      the owning team name; optional (may be {@code null})
     */
    record NewBoundedContext(
            String name,
            String domainVision,
            Subdomain subdomain,
            String ownedBy) {
    }
}
