// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.time.LocalDate;

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
 * transition rule itself lives on {@link Adr#reject(LocalDate)}, not here and not in the
 * implementing application service.</p>
 *
 * <p><strong>Stamps the decision date exactly as {@link AcceptAdr} does (kogn-io/arknet#374).</strong>
 * Turning an option down is a decision made on a day, not the absence of one - which is the whole
 * reason a rejected record is worth keeping.</p>
 *
 * <p><strong>{@code defaultLanguage} (issue #468).</strong> Same reasoning as {@link AcceptAdr}:
 * this call touches no language-tagged field itself, but still needs the project's own default
 * language for the read behind it, so an untouched field is echoed back under the project's own
 * language rather than the process-wide configured one.</p>
 */
public interface RejectAdr {

    /**
     * Rejects the architecture decision identified by {@code code} within a project, transitioning
     * it from {@code PROPOSED} to {@code REJECTED}.
     *
     * @param projectId       the project (architecture model) the decision lives in
     * @param code            the ADR code, e.g. {@code ADR-1}
     * @param decidedOn       the day the decision to reject was actually made, for one taken before
     *                        it was entered here; {@code null} stamps today's date, which is the
     *                        ordinary case
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - consulted only for the read this call
     *                        makes to echo an untouched field back, never for a write
     * @return the updated decision, or the unchanged decision if it was already {@code REJECTED} -
     *         which keeps the date it was rejected on rather than restamping it
     * @throws de.hauschel.arknet.adr.domain.AdrNotFoundException if no decision with {@code code}
     *                                                           exists
     * @throws IllegalStateException                              if the decision is
     *                                                             {@code ACCEPTED} or
     *                                                             {@code DEPRECATED}
     */
    AdrDetail reject(ProjectId projectId, AdrCode code, LocalDate decidedOn, String defaultLanguage);
}
