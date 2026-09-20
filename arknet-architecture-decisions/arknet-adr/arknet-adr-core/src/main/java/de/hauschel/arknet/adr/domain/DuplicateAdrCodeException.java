// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@link de.hauschel.arknet.adr.application.port.out.AdrRepository#create} is called
 * with an {@link AdrCode} that already labels a different architecture decision in the targeted
 * project.
 *
 * <p>Distinct from {@link ResourceAlreadyExistsException}: that one flags an opaque-identity
 * collision (a programming error - identities are minted once and never reused), while this one
 * flags a business-label collision, e.g. two decisions both claiming {@code ADR-1}. Since
 * {@code dcterms:identifier} is how a human addresses a decision, this is an expected, rejectable
 * outcome - not a stack trace. It is also the signal
 * {@link de.hauschel.arknet.kernel.CodeAssignment}'s retry consumes, so in practice a caller never
 * sees it.</p>
 */
public class DuplicateAdrCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient AdrCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the code collided in
     * @param code      the ADR code that already exists
     */
    public DuplicateAdrCodeException(ProjectId projectId, AdrCode code) {
        super("ADR code " + Objects.requireNonNull(code, "code").value()
                + " already exists in project " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the code collided in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the ADR code that already exists */
    public AdrCode code() {
        return code;
    }
}
