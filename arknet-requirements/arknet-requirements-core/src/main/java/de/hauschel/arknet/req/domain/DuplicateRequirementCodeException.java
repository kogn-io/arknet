// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@link de.hauschel.arknet.req.application.port.out.RequirementRepository#create}
 * is called with a {@link RequirementCode} that already labels a different requirement in the
 * targeted workspace.
 *
 * <p>Distinct from {@link ResourceAlreadyExistsException}: that one flags an opaque-identity
 * collision (a programming error - identities are minted once and never reused), while this one
 * flags a business-label collision, e.g. two requirements both claiming {@code FR-1}. Since
 * {@code dcterms:identifier} is how a human addresses a requirement, this is an expected,
 * rejectable outcome - not a stack trace.</p>
 */
public class DuplicateRequirementCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RequirementCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the workspace the code collided in
     * @param code        the requirement code that already exists
     */
    public DuplicateRequirementCodeException(ProjectId projectId, RequirementCode code) {
        super("requirement code " + Objects.requireNonNull(code, "code").value()
                + " already exists in workspace " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the workspace the code collided in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the requirement code that already exists */
    public RequirementCode code() {
        return code;
    }
}
