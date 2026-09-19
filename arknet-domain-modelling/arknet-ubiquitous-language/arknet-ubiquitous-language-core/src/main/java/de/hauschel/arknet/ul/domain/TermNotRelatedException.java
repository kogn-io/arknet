// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code term_unlink_related} names two terms that are not currently related in
 * either direction.
 *
 * <p>An expected domain outcome, not a programming error, and the exact counterpart of {@code
 * TermNotLinkedException} (bounded context): an unlink that removes nothing must fail loudly, or
 * a typo in the peer code reads as a successful removal and the edge stays where the caller
 * believes it is gone. Linking an already-related pair remains an idempotent no-op - adding what
 * is already there leaves the model in the state the caller asked for, removing what is not there
 * does not.</p>
 *
 * <p>Surfaces as the tool call's own error message rather than a stack trace; no driving adapter
 * has to catch and translate it.</p>
 */
public class TermNotRelatedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient TermCode code;
    private final transient TermCode peerCode;

    /**
     * Creates the exception.
     *
     * @param projectId the project that was searched
     * @param code      the term code the caller named
     * @param peerCode  the peer term code the caller named
     */
    public TermNotRelatedException(ProjectId projectId, TermCode code, TermCode peerCode) {
        super("term " + Objects.requireNonNull(code, "code").value() + " is not related to "
                + Objects.requireNonNull(peerCode, "peerCode").value() + " in project "
                + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
        this.peerCode = peerCode;
    }

    /** @return the project that was searched */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the term code the caller named */
    public TermCode code() {
        return code;
    }

    /** @return the peer term code the caller named */
    public TermCode peerCode() {
        return peerCode;
    }
}
