// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code uc_unlink_term} names a glossary term that the use case does not currently
 * link.
 *
 * <p>An expected domain outcome, not a programming error, mirroring
 * {@code de.hauschel.arknet.bc.domain.TermNotLinkedException} in the bounded-context BC: an
 * unlink that removes nothing must fail loudly, or a typo in the term code reads as a successful
 * removal while the edge stays exactly where the caller believes it is gone. Linking an
 * already-linked term remains an idempotent no-op - adding what is already there leaves the model
 * in the state the caller asked for, removing what is not there does not.</p>
 *
 * <p>Surfaces as the tool call's own error message rather than a stack trace; no driving adapter
 * has to catch and translate it.</p>
 */
public class TermNotLinkedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient UseCaseCode code;
    private final transient String termCode;

    /**
     * Creates the exception.
     *
     * @param projectId the project that was searched
     * @param code      the use-case code the caller named
     * @param termCode  the term code the caller named
     */
    public TermNotLinkedException(ProjectId projectId, UseCaseCode code, String termCode) {
        super("use case " + Objects.requireNonNull(code, "code").value() + " does not link term "
                + Objects.requireNonNull(termCode, "termCode") + " in project "
                + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
        this.termCode = termCode;
    }

    /** @return the project that was searched */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the use-case code the caller named */
    public UseCaseCode code() {
        return code;
    }

    /** @return the term code the caller named */
    public String termCode() {
        return termCode;
    }
}
