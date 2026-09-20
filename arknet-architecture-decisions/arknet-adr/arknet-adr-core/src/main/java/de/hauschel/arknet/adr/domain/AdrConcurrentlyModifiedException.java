// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a read-modify-write round trip ({@code adr_set_status}, {@code adr_supersede}) keeps
 * losing the optimistic-concurrency race against other writers of the same architecture decision
 * (see
 * {@link de.hauschel.arknet.adr.application.port.out.AdrRepository#compareAndUpdate}) across every
 * retry attempt the application service allows.
 *
 * <p>An expected-but-rare domain outcome, not a programming error: sustained, high-frequency
 * concurrent writes to the very same decision. Distinct from {@link AdrNotFoundException} - the
 * decision exists throughout, it is just never observed to still match the caller's stale read for
 * long enough to commit.</p>
 */
public class AdrConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient AdrCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the decision lives in
     * @param code      the ADR code whose update kept losing the race
     */
    public AdrConcurrentlyModifiedException(ProjectId projectId, AdrCode code) {
        super("ADR " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the decision lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the ADR code whose update kept losing the race */
    public AdrCode adrCode() {
        return code;
    }
}
