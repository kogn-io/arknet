// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.UseCase;

/**
 * Driving port: list all managed use cases.
 */
public interface ListUseCases {

    /**
     * Returns all use cases currently under management in the given project.
     *
     * <p>No ordering is guaranteed: the result reflects whatever order the underlying store
     * returns, not a sort on {@code UseCaseCode} or any other field.
     *
     * @param projectId the project (architecture model) to list use cases from
     * @return all use cases, never {@code null}
     */
    List<UseCase> list(ProjectId projectId);
}
