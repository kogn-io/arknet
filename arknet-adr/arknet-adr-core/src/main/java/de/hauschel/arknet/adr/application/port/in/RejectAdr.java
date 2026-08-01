// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: reject a proposed architecture decision.
 *
 * <p>Backs the tool {@code adr_set_status}. The legal transition is {@code PROPOSED -> REJECTED}, so
 * this port takes no target status - the same shape {@link AcceptAdr} takes for its own single
 * transition, itself the shape the requirements context settled on for the identical situation,
 * where the former {@code SetRequirementStatus} became {@code AcceptRequirement}. The
 * transition rule itself lives on {@link Adr#reject()}, not here and not in the implementing
 * application service.</p>
 */
public interface RejectAdr {

    /**
     * Rejects the architecture decision identified by {@code code} within a project, transitioning
     * it from {@code PROPOSED} to {@code REJECTED}.
     *
     * @param projectId the project (architecture model) the decision lives in
     * @param code      the ADR code, e.g. {@code ADR-1}
     * @return the updated decision, or the unchanged decision if it was already {@code REJECTED}
     * @throws de.hauschel.arknet.adr.domain.AdrNotFoundException if no decision with {@code code}
     *                                                           exists
     * @throws IllegalStateException                              if the decision is
     *                                                             {@code ACCEPTED} or
     *                                                             {@code DEPRECATED}
     */
    AdrDetail reject(ProjectId projectId, AdrCode code);
}
