// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: reset an accepted requirement back to proposed.
 *
 * <p>Backs the tool {@code req_set_status}'s {@code PROPOSED} target (issue #291; the reverse
 * transition is an acceptance criterion of FR-5 in arknet's own store). The legal transition is
 * {@code ACCEPTED -> PROPOSED} - the same narrow shape {@link AcceptRequirement} takes for its own
 * single transition, mirroring how the adr context later split {@code adr_set_status} across
 * {@code AcceptAdr}/{@code RejectAdr}/ {@code DeprecateAdr}, one port per transition, none taking
 * a target status of its own; the caller-visible dispatch happens only in the driving adapter. The
 * transition rule itself lives on {@link Requirement#propose()}, not here and not in the
 * implementing application service.</p>
 *
 * <p><strong>{@code defaultLanguage} (issue #468).</strong> Same reasoning as
 * {@link AcceptRequirement}: this call touches no language-tagged field itself, but still needs
 * the project's own default language for the read behind it, so an untouched field is echoed back
 * (and, if this call is a no-op, compared) under the project's own language rather than the
 * process-wide configured one.</p>
 */
public interface ProposeRequirement {

    /**
     * Resets the requirement identified by {@code code} within a project, transitioning it from
     * {@code ACCEPTED} back to {@code PROPOSED}.
     *
     * @param projectId       the project (architecture model) the requirement lives in
     * @param code            the requirement code, e.g. {@code FR-1}
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - consulted only for the read this call
     *                        makes to echo an untouched field back, never for a write
     * @return the updated requirement, or the unchanged requirement if it was already
     *         {@code PROPOSED}
     */
    Requirement propose(ProjectId projectId, RequirementCode code, String defaultLanguage);
}
