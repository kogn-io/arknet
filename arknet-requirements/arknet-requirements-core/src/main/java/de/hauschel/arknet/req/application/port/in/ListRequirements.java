// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: list all managed requirements.
 *
 * <p>Backs the MVP tool {@code req_list}.</p>
 */
public interface ListRequirements {

    /**
     * Returns all requirements currently under management in the given project.
     *
     * @param projectId     the project (architecture model) to list requirements from
     * @param displayLocale the BCP-47 language tag the caller wants each requirement's
     *                      {@code title}/{@code description} shown in (e.g. {@code "de"}), or
     *                      {@code null} to fall back to the project's own configured default
     *                      language, and from there to the process-wide default - the same
     *                      fallback chain {@link GetRequirement#get} already applies, so a project
     *                      whose default differs from this daemon's sees the same language variant
     *                      of a multi-language requirement whether it calls {@code req_get} or
     *                      {@code req_list} (issue #281)
     * @return all requirements, never {@code null}
     */
    List<Requirement> list(ProjectId projectId, String displayLocale);
}
