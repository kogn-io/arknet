// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.ConstraintNotLinkedException;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;

/**
 * Driving port: remove a single {@code oslc_rm:constrainedBy} edge between a use case and a
 * constraint.
 *
 * <p>Backs the tool {@code uc_unlink_constraint}, the counterpart {@link LinkConstraint} was
 * missing (kogn-io/arknet#598), mirroring {@link UnlinkTerm} exactly for the constraint edge.
 * Unlike {@code uc_update}'s {@code usesTermCodes}, {@code constrainedBy} has no wholesale
 * correction field on {@code uc_update} at all - this port and its {@link LinkConstraint}
 * counterpart are the only way to correct it.</p>
 *
 * <p><strong>Never a silent no-op</strong>, for the same reason {@link UnlinkTerm} is not:
 * unlinking a constraint that is not linked is a caller mistake, and quietly reporting success
 * would leave the caller believing an edge is gone that is still there. Linking an
 * already-linked constraint stays idempotent.</p>
 */
public interface UnlinkConstraint {

    /**
     * Removes the link from use case {@code code} to the constraint identified by
     * {@code constraintCode}.
     *
     * @param projectId       the project (architecture model) the use case lives in
     * @param code            the use-case code, e.g. {@code UC1}
     * @param constraintCode  the constraint's human-readable business code, e.g. {@code TCON-1}
     * @param defaultLanguage the target project's configured default language, or {@code null} if
     *                        it has none - consulted only for the read this call makes to echo an
     *                        untouched field back, never for a write (mirrors {@link LinkConstraint})
     * @return the use case without the link
     * @throws de.hauschel.arknet.uc.domain.UseCaseNotFoundException if {@code code} is unknown
     * @throws ConstraintNotLinkedException if the constraint is not currently linked to this use
     *                                       case
     */
    UseCase unlinkConstraint(ProjectId projectId, UseCaseCode code, String constraintCode, String defaultLanguage);
}
