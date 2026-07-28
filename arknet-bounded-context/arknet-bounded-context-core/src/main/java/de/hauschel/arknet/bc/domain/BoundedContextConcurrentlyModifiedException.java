// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Thrown when a read-modify-write round trip (today {@code bc_link_term}) keeps losing the
 * optimistic-concurrency race against other writers of the same bounded context (see {@link
 * de.hauschel.arknet.bc.application.port.out.BoundedContextRepository#compareAndUpdate}) across
 * every retry attempt the application service allows.
 *
 * <p>An expected-but-rare domain outcome, not a programming error: sustained, high-frequency
 * concurrent writes to the very same bounded context. Distinct from {@link
 * BoundedContextNotFoundException} - the bounded context exists throughout, it is just never
 * observed to still match the caller's stale read for long enough to commit.</p>
 */
public class BoundedContextConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient BoundedContextCode code;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace the bounded context lives in
     * @param code        the bounded-context code whose update kept losing the race
     */
    public BoundedContextConcurrentlyModifiedException(WorkspaceId workspaceId, BoundedContextCode code) {
        super("bounded context " + Objects.requireNonNull(code, "code").value()
                + " in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.workspaceId = workspaceId;
        this.code = code;
    }

    /** @return the workspace the bounded context lives in */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the bounded-context code whose update kept losing the race */
    public BoundedContextCode boundedContextCode() {
        return code;
    }
}
