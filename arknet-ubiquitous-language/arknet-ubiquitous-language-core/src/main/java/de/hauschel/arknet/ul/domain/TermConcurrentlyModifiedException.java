// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@link de.hauschel.arknet.ul.application.port.out.TermRepository#update} keeps
 * losing the compare-and-set race against other writers of the same term across every bounded
 * retry attempt the adapter allows (e.g. two overlapping {@code term_update} calls racing to
 * advance the same term's revision head, whether or not they touch the same field).
 *
 * <p>Distinct from {@link DuplicateTermCodeException} on purpose: that one means a business-code
 * collision, an entirely different failure mode that {@code update()} can no longer even reach,
 * since it never rewrites {@code dcterms:identifier}. Unlike the classic lost-update it might be
 * mistaken for, the application-level read-then-write gap is not closed here - each retry attempt
 * reads the term's current state and revision head, then writes conditionally on that head still
 * matching. This exception surfaces only once every one of those bounded attempts found the head
 * had already moved on again; an expected-but-rare domain outcome under sustained,
 * high-frequency concurrent writes to the very same term, not a programming error - the caller
 * should re-check the term's current state and retry.</p>
 */
public class TermConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient TermCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the term lives in
     * @param code        the term code whose update kept losing the race
     */
    public TermConcurrentlyModifiedException(ProjectId projectId, TermCode code) {
        super("term " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the term lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the term code whose update kept losing the race */
    public TermCode code() {
        return code;
    }
}
