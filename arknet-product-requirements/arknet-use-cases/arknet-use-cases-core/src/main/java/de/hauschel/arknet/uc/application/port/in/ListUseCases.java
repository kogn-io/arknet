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
     * @param projectId     the project (architecture model) to list use cases from
     * @param displayLocale the BCP-47 language tag the caller wants each use case's
     *                      title/goal/scope/trigger/precondition/postcondition/step/extension
     *                      text shown in (e.g. {@code "de"}), or {@code null} to fall back to the
     *                      project's own configured default language, and from there to the
     *                      process-wide default - the same fallback chain {@link GetUseCase#get}
     *                      already applies, so a project whose default differs from this daemon's
     *                      sees the same language variant of a multi-language use case whether it
     *                      calls {@code uc_get} or {@code uc_list} (issue #281)
     * @return all use cases, never {@code null}
     */
    List<UseCase> list(ProjectId projectId, String displayLocale);
}
