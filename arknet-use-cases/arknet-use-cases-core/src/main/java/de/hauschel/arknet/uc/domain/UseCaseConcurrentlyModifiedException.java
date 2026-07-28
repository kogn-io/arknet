// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

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

    private final transient WorkspaceId workspaceId;
    private final transient UseCaseCode code;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace the use case lives in
     * @param code        the use-case code whose update kept losing the race
     */
    public UseCaseConcurrentlyModifiedException(WorkspaceId workspaceId, UseCaseCode code) {
        super("use case " + Objects.requireNonNull(code, "code").value()
                + " in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.workspaceId = workspaceId;
        this.code = code;
    }

    /** @return the workspace the use case lives in */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the use-case code whose update kept losing the race */
    public UseCaseCode useCaseCode() {
        return code;
    }
}
