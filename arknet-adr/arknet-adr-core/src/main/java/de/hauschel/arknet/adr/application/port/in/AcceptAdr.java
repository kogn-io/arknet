// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: accept a proposed architecture decision.
 *
 * <p>Backs the tool {@code adr_set_status}. The implemented lifecycle permits exactly one
 * transition - {@code PROPOSED -> ACCEPTED} - so this port takes no target status; a generic
 * {@code AdrStatus status} parameter would only ever legally resolve to {@code ACCEPTED}. That is
 * the shape the requirements context settled on for the identical situation (issue #190, where the
 * former {@code SetRequirementStatus} became {@code AcceptRequirement}); the tool keeps its
 * {@code adr_set_status} name because a richer lifecycle is deferred, not ruled out. The transition
 * rule itself lives on {@link Adr#accept()}, not here and not in the implementing application
 * service.</p>
 */
public interface AcceptAdr {

    /**
     * Accepts the architecture decision identified by {@code code} within a project, transitioning
     * it from {@code PROPOSED} to {@code ACCEPTED}.
     *
     * @param projectId the project (architecture model) the decision lives in
     * @param code      the ADR code, e.g. {@code ADR-1}
     * @return the updated decision, or the unchanged decision if it was already {@code ACCEPTED}
     * @throws de.hauschel.arknet.adr.domain.AdrNotFoundException if no decision with {@code code}
     *                                                           exists
     */
    AdrDetail accept(ProjectId projectId, AdrCode code);
}
