// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: accept a proposed requirement.
 *
 * <p>Backs the tool {@code req_set_status}'s {@code ACCEPTED} target. The legal transition is
 * {@code PROPOSED -> ACCEPTED}, so this port takes no target status of its own; a generic
 * {@code RequirementStatus status} parameter would only ever legally resolve to {@code ACCEPTED}
 * here, since {@link ProposeRequirement} covers this lifecycle's other transition as its own,
 * equally narrow port (issue #291; FR-5 in arknet's own store - the requirements context's
 * original, one-way-only cut of this port was itself the misleading surface, formerly
 * {@code SetRequirementStatus}). The transition rule itself lives on {@link Requirement#accept()},
 * not here or in the implementing application service.</p>
 *
 * <p><strong>{@code defaultLanguage} (issue #468).</strong> This call touches no language-tagged
 * field, but it still reads one to echo it back unchanged in the reply: without the project's own
 * default language, the read-modify-write round trip behind this call would fall back to the
 * process-wide configured language instead, so a project with {@code defaultLanguage: de} could
 * answer {@code req_set_status} with the English title and a directly following {@code req_get}
 * with the German one - the same defect issue #456 fixed for {@code req_update}, one call
 * further.</p>
 */
public interface AcceptRequirement {

    /**
     * Accepts the requirement identified by {@code code} within a project, transitioning it from
     * {@code PROPOSED} to {@code ACCEPTED}.
     *
     * @param projectId       the project (architecture model) the requirement lives in
     * @param code            the requirement code, e.g. {@code FR-1}
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - consulted only for the read this call
     *                        makes to echo an untouched field back, never for a write
     * @return the updated requirement, or the unchanged requirement if it was already {@code
     *         ACCEPTED}
     */
    Requirement accept(ProjectId projectId, RequirementCode code, String defaultLanguage);
}
