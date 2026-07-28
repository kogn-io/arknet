// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when
 * {@link de.hauschel.arknet.bc.application.port.out.BoundedContextRepository#create} is called
 * with a {@link BoundedContextCode} that already labels a different bounded context in the
 * targeted workspace.
 *
 * <p>Distinct from {@link ResourceAlreadyExistsException}: that one flags an opaque-identity
 * collision (a programming error - identities are minted once and never reused), while this one
 * flags a business-label collision, e.g. two bounded contexts both claiming {@code BC-1}. Since
 * {@code dcterms:identifier} is how a human addresses a bounded context, this is an expected,
 * rejectable outcome - not a stack trace.</p>
 */
public class DuplicateBoundedContextCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient BoundedContextCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the code collided in
     * @param code        the bounded-context code that already exists
     */
    public DuplicateBoundedContextCodeException(ProjectId projectId, BoundedContextCode code) {
        super("bounded context code " + Objects.requireNonNull(code, "code").value()
                + " already exists in project " + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the code collided in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the bounded-context code that already exists */
    public BoundedContextCode code() {
        return code;
    }
}
