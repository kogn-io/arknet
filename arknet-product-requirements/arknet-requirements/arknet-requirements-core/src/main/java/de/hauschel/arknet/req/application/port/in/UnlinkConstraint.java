// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.ConstraintNotLinkedException;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.pr.shared.RequirementCode;

/**
 * Driving port: remove a single {@code oslc_rm:constrainedBy} edge between a requirement and a
 * constraint.
 *
 * <p>Backs the tool {@code req_unlink_constraint}, the counterpart {@link LinkConstraint} was
 * missing (kogn-io/arknet#598). Mirrors {@link UnlinkTerm} exactly, one hexagon closer to home:
 * {@link de.hauschel.arknet.req.domain.Constraint} lives in this same bounded context, so
 * resolving {@code constraintCode} is a direct, same-module lookup rather than a cross-BC driven
 * lookup port.</p>
 *
 * <p><strong>Never a silent no-op</strong>, for the same reason {@link UnlinkTerm} is not:
 * unlinking a constraint that is not linked is a caller mistake, and quietly reporting success
 * would leave the caller believing an edge is gone that is still there. Linking an already-linked
 * constraint stays idempotent; the asymmetry is deliberate.</p>
 *
 * <p><strong>{@code defaultLanguage}</strong>, mirroring {@link LinkConstraint}: unlinking a
 * constraint touches no language-tagged field itself, but the read-modify-write round trip behind
 * this call still needs the project's own default language so an untouched field is echoed back
 * under the project's own language rather than the process-wide configured one.</p>
 */
public interface UnlinkConstraint {

    /**
     * Removes the link from requirement {@code code} to the constraint identified by
     * {@code constraintCode}.
     *
     * @param code            the requirement code, e.g. {@code FR-1}
     * @param constraintCode  the constraint's human-readable business code, e.g. {@code TCON-1}
     * @param defaultLanguage the target project's configured default language, or {@code null} if
     *                        it has none - consulted only for the read this call makes to echo an
     *                        untouched field back, never for a write
     * @return the requirement without the link
     * @throws de.hauschel.arknet.req.domain.RequirementNotFoundException if {@code code} is unknown
     * @throws de.hauschel.arknet.req.domain.ConstraintNotFoundException if {@code constraintCode}
     *                        names no known constraint
     * @throws ConstraintNotLinkedException if the constraint is not currently linked to this
     *                        requirement
     */
    Requirement unlinkConstraint(
            ProjectId projectId, RequirementCode code, String constraintCode, String defaultLanguage);
}
