// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: deprecate an accepted architecture decision that has become obsolete without a
 * successor.
 *
 * <p>Backs the tool {@code adr_set_status}. The legal transition is
 * {@code ACCEPTED -> DEPRECATED}, so this port takes no target status - the same shape
 * {@link AcceptAdr} takes for its own single transition, itself the shape the requirements context
 * settled on for the identical situation (issue #190, where the former
 * {@code SetRequirementStatus} became {@code AcceptRequirement}). The transition rule itself lives
 * on {@link Adr#deprecate()}, not here and not in the implementing application service.</p>
 */
public interface DeprecateAdr {

    /**
     * Deprecates the architecture decision identified by {@code code} within a project,
     * transitioning it from {@code ACCEPTED} to {@code DEPRECATED}.
     *
     * @param projectId the project (architecture model) the decision lives in
     * @param code      the ADR code, e.g. {@code ADR-1}
     * @return the updated decision, or the unchanged decision if it was already {@code DEPRECATED}
     * @throws de.hauschel.arknet.adr.domain.AdrNotFoundException if no decision with {@code code}
     *                                                           exists
     * @throws IllegalStateException                              if the decision is
     *                                                             {@code PROPOSED} or
     *                                                             {@code REJECTED}
     */
    AdrDetail deprecate(ProjectId projectId, AdrCode code);
}
