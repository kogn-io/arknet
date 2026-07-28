// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

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

    private final transient ProjectId projectId;
    private final transient BoundedContextCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the bounded context lives in
     * @param code        the bounded-context code whose update kept losing the race
     */
    public BoundedContextConcurrentlyModifiedException(ProjectId projectId, BoundedContextCode code) {
        super("bounded context " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the bounded context lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the bounded-context code whose update kept losing the race */
    public BoundedContextCode boundedContextCode() {
        return code;
    }
}
