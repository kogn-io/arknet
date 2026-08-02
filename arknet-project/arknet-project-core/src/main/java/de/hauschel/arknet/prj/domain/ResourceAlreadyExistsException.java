// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import de.hauschel.arknet.kernel.ProjectId;
import java.util.Objects;

/**
 * Thrown when {@link de.hauschel.arknet.prj.application.port.out.ProjectRegistry#register} is
 * called with an identity that already exists in the registry.
 *
 * <p>On the {@code project_add} path this is a programming-error signal, not an expected domain
 * outcome: there, a {@link ProjectId} is minted once, freshly, by the application service (a
 * UUID, never reused - see {@link ProjectId}'s Javadoc for why it is not a
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}-minted {@code ResourceId}), so it should
 * only fire there if something outside that normal path collides with an already-registered
 * identity. On the {@code project_adopt} path, though, it is an expected, reachable outcome
 * (issue #174): {@code datasetId} is caller-chosen there, not minted, and two concurrent
 * {@code project_adopt} calls naming the very same dataset can genuinely race for it - see
 * {@code ProjectService#adopt}'s javadoc for how the retry that precedes this exception still
 * turns a raw commit conflict into this well-attributed signal rather than leaving the loser with
 * an unactionable one.</p>
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
