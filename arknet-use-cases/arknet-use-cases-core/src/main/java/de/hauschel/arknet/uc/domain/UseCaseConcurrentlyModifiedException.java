// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a read-modify-write round trip (e.g. {@code uc_update}) keeps losing the
 * optimistic-concurrency race against other writers of the same use case (see
 * {@link de.hauschel.arknet.uc.application.port.out.UseCaseRepository#compareAndUpdate}) across
 * every retry attempt the application service allows.
 *
 * <p>An expected-but-rare domain outcome, not a programming error: sustained, high-frequency
 * concurrent writes to the very same use case. Distinct from {@link UseCaseNotFoundException} -
 * the use case exists throughout, it is just never observed to still match the caller's stale
 * read for long enough to commit.</p>
 */
public class UseCaseConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient UseCaseCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the use case lives in
     * @param code      the use-case code whose update kept losing the race
     */
    public UseCaseConcurrentlyModifiedException(ProjectId projectId, UseCaseCode code) {
        super("use case " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the use case lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the use-case code whose update kept losing the race */
    public UseCaseCode useCaseCode() {
        return code;
    }
}
