// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import de.hauschel.arknet.kernel.ProjectId;
import java.util.Objects;

/**
 * Thrown when {@link de.hauschel.arknet.prj.application.port.out.ProjectRegistry#register} is
 * called with an identity that already exists in the registry.
 *
 * <p>A programming-error signal, not an expected domain outcome: a {@link ProjectId} is minted
 * once, freshly, by the application service (a UUID, never reused - see {@link ProjectId}'s
 * Javadoc for why it is not a {@link de.hauschel.arknet.kernel.ResourceIdFactory}-minted
 * {@code ResourceId}), so this should only fire if something outside the normal
 * {@code project_add} path collides with an already-registered identity.</p>
 */
public class ResourceAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId id;

    /**
     * Creates the exception.
     *
     * @param id the project identity that already exists
     */
    public ResourceAlreadyExistsException(ProjectId id) {
        super("project " + Objects.requireNonNull(id, "id").value() + " already exists in the registry");
        this.id = id;
    }

    /** @return the project identity that already exists */
    public ProjectId id() {
        return id;
    }
}
