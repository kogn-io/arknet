// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.time.LocalDate;

import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: accept a proposed architecture decision.
 *
 * <p>Backs the tool {@code adr_set_status}. The legal transition is {@code PROPOSED -> ACCEPTED},
 * so this port takes no target status - a generic {@code AdrStatus status} parameter would only
 * ever legally resolve to {@code ACCEPTED} here, since {@link RejectAdr}/{@link DeprecateAdr} cover
 * this lifecycle's other transitions as their own, equally narrow ports. That is the shape the
 * requirements context settled on for the identical situation, where the former
 * {@code SetRequirementStatus} became {@code AcceptRequirement}; the tool keeps its
 * {@code adr_set_status} name and dispatches to whichever of the three ports the caller's target
 * status legally maps to. The transition rule itself lives on {@link Adr#accept(LocalDate)}, not
 * here and not in the implementing application service.</p>
 *
 * <p><strong>This transition is what sets the decision date (kogn-io/arknet#374).</strong> Accepting
 * a decision and dating it are the same event, so the date is stamped here rather than typed into
 * {@code adr_add}/{@code adr_update} beforehand - a {@code PROPOSED} decision is expressly not
 * decided yet, so any date carried before this point names a day that does not exist. {@code
 * decidedOn} exists for the one honest exception: a decision that was really made earlier and is
 * only now being entered.</p>
 *
 * <p><strong>{@code defaultLanguage} (issue #468).</strong> This call touches no language-tagged
 * field, but it still reads one to echo it back unchanged in the reply: without the project's own
 * default language, the read-modify-write round trip behind this call would fall back to the
 * process-wide configured language instead, so a project with {@code defaultLanguage: de} could
 * answer {@code adr_set_status} with the English name/context/decision and a directly following
 * {@code adr_get} with the German one - the same defect issue #456 fixed for {@code adr_update},
 * one call further.</p>
 */
public interface AcceptAdr {

    /**
     * Accepts the architecture decision identified by {@code code} within a project, transitioning
     * it from {@code PROPOSED} to {@code ACCEPTED}.
     *
     * @param projectId       the project (architecture model) the decision lives in
     * @param code            the ADR code, e.g. {@code ADR-1}
     * @param decidedOn       the day the decision was actually made, for a decision taken before it
     *                        was entered here; {@code null} stamps today's date, which is the
     *                        ordinary case
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - consulted only for the read this call
     *                        makes to echo an untouched field back, never for a write
     * @return the updated decision, or the unchanged decision if it was already {@code ACCEPTED} -
     *         which keeps the date it was accepted on rather than restamping it
     * @throws de.hauschel.arknet.adr.domain.AdrNotFoundException if no decision with {@code code}
     *                                                           exists
     * @throws IllegalStateException                              if the decision is
     *                                                             {@code REJECTED} or
     *                                                             {@code DEPRECATED}
     */
    AdrDetail accept(ProjectId projectId, AdrCode code, LocalDate decidedOn, String defaultLanguage);
}
