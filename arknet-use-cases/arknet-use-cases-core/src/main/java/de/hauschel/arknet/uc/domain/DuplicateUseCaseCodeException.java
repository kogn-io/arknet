// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Thrown when {@link de.hauschel.arknet.uc.application.port.out.UseCaseRepository#create}
 * is called with a {@link UseCaseCode} that already labels a different use case in the
 * targeted workspace.
 *
 * <p>Distinct from {@link ResourceAlreadyExistsException}: that one flags an opaque-identity
 * collision (a programming error - identities are minted once and never reused), while this one
 * flags a business-label collision, e.g. two use cases both claiming {@code UC1}. Since
 * {@code dcterms:identifier} is how a human addresses a use case, this is an expected,
 * rejectable outcome - not a stack trace.</p>
 */
public class DuplicateUseCaseCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient UseCaseCode code;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace the code collided in
     * @param code        the use-case code that already exists
     */
    public DuplicateUseCaseCodeException(WorkspaceId workspaceId, UseCaseCode code) {
        super("use case code " + Objects.requireNonNull(code, "code").value()
                + " already exists in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value());
        this.workspaceId = workspaceId;
        this.code = code;
    }

    /** @return the workspace the code collided in */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the use-case code that already exists */
    public UseCaseCode code() {
        return code;
    }
}
