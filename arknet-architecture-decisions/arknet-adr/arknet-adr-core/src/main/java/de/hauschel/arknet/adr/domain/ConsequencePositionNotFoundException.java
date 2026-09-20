// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when an {@code adr_update} consequence correction names a {@link Consequence#position()
 * position} that does not match any existing consequence of the targeted decision.
 *
 * <p>An expected, rejectable caller input (a typo'd or stale position), not a programming error:
 * a correction must correct an existing consequence rather than silently being dropped or
 * accidentally inserting a new one. Mirrors
 * {@code de.hauschel.arknet.req.domain.AcceptanceCriterionPositionNotFoundException}.</p>
 */
public class ConsequencePositionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient AdrCode code;
    private final int position;

    /**
     * Creates the exception.
     *
     * @param projectId the project the decision lives in
     * @param code      the ADR code the correction targeted
     * @param position  the position named by the correction that matched no existing consequence
     */
    public ConsequencePositionNotFoundException(ProjectId projectId, AdrCode code, int position) {
        super("ADR " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " has no consequence at position " + position);
        this.projectId = projectId;
        this.code = code;
        this.position = position;
    }

    /** @return the project the decision lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the ADR code the correction targeted */
    public AdrCode adrCode() {
        return code;
    }

    /** @return the position named by the correction that matched no existing consequence */
    public int position() {
        return position;
    }
}
