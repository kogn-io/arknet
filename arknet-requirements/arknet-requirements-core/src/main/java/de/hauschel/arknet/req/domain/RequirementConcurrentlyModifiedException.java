// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a read-modify-write round trip (e.g. {@code req_link_term}, {@code
 * req_set_status}) keeps losing the optimistic-concurrency race against other writers of the
 * same requirement (see {@link
 * de.hauschel.arknet.req.application.port.out.RequirementRepository#compareAndUpdate}) across
 * every retry attempt the application service allows.
 *
 * <p>An expected-but-rare domain outcome, not a programming error: sustained, high-frequency
 * concurrent writes to the very same requirement. Distinct from {@link
 * RequirementNotFoundException} - the requirement exists throughout, it is just never observed
 * to still match the caller's stale read for long enough to commit.</p>
 */
public class RequirementConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RequirementCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the workspace the requirement lives in
     * @param code        the requirement code whose update kept losing the race
     */
    public RequirementConcurrentlyModifiedException(ProjectId projectId, RequirementCode code) {
        super("requirement " + Objects.requireNonNull(code, "code").value()
                + " in workspace " + Objects.requireNonNull(projectId, "projectId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the workspace the requirement lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the requirement code whose update kept losing the race */
    public RequirementCode requirementCode() {
        return code;
    }
}
