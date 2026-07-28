// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@link de.hauschel.arknet.ul.application.port.out.TermRepository#create}
 * is called with a {@link TermCode} that already labels a different term in the targeted
 * workspace.
 *
 * <p>Distinct from {@link ResourceAlreadyExistsException}: that one flags an opaque-identity
 * collision (a programming error - identities are minted once and never reused), while this one
 * flags a business-label collision, e.g. two terms both claiming {@code TERM-1}. Since
 * {@code dcterms:identifier} is how a human addresses a term - and how a sibling bounded context
 * resolves an {@code arkreq:usesTerm} edge (#36) - this is an expected, rejectable outcome, not
 * a stack trace.</p>
 */
public class DuplicateTermCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient TermCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the workspace the code collided in
     * @param code        the term code that already exists
     */
    public DuplicateTermCodeException(ProjectId projectId, TermCode code) {
        super("term code " + Objects.requireNonNull(code, "code").value()
                + " already exists in workspace " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the workspace the code collided in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the term code that already exists */
    public TermCode code() {
        return code;
    }
}
