// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when
 * {@link de.hauschel.arknet.bc.application.port.out.BoundedContextRepository#create} is called
 * with an identity that already exists in the targeted workspace.
 *
 * <p>A programming-error signal, not an expected domain outcome: identities are minted once by
 * a {@link de.hauschel.arknet.kernel.ResourceIdFactory} and are never reused, so this should
 * only fire if something outside the normal {@code bc_add} path collides with an existing
 * subject.</p>
 */
public class ResourceAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient ResourceId id;

    /**
     * Creates the exception.
     *
     * @param projectId the workspace the identity collided in
     * @param id          the resource identity that already exists
     */
    public ResourceAlreadyExistsException(ProjectId projectId, ResourceId id) {
        super("resource " + Objects.requireNonNull(id, "id").value()
                + " already exists in workspace " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.id = id;
    }

    /** @return the workspace the identity collided in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the resource identity that already exists */
    public ResourceId id() {
        return id;
    }
}
