// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: reset an accepted requirement back to proposed.
 *
 * <p>Backs the tool {@code req_set_status}'s {@code PROPOSED} target (issue #291, ADR-019 point
 * 4). The legal transition is {@code ACCEPTED -> PROPOSED} - the same narrow shape
 * {@link AcceptRequirement} takes for its own single transition, mirroring how the adr context
 * later split {@code adr_set_status} across {@code AcceptAdr}/{@code RejectAdr}/
 * {@code DeprecateAdr}, one port per transition, none taking a target status of its own; the
 * caller-visible dispatch happens only in the driving adapter. The transition rule itself lives
 * on {@link Requirement#propose()}, not here and not in the implementing application service.</p>
 */
public interface ProposeRequirement {

    /**
     * Resets the requirement identified by {@code code} within a project, transitioning it from
     * {@code ACCEPTED} back to {@code PROPOSED}.
     *
     * @param projectId the project (architecture model) the requirement lives in
     * @param code      the requirement code, e.g. {@code FR-1}
     * @return the updated requirement, or the unchanged requirement if it was already
     *         {@code PROPOSED}
     */
    Requirement propose(ProjectId projectId, RequirementCode code);
}
