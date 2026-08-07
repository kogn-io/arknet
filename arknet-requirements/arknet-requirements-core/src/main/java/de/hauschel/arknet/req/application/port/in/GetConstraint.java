// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;

/**
 * Driving port: fetch a single constraint by its business code.
 *
 * <p>Backs the MVP tool {@code constraint_get}.</p>
 */
public interface GetConstraint {

    /**
     * Looks up a constraint by its business code within a project.
     *
     * @param projectId     the project (architecture model) to look up the constraint in
     * @param code          the constraint code, e.g. {@code TCON-1}
     * @param displayLocale the BCP-47 language tag the caller wants {@code title}/
     *                      {@code statement} shown in (issue #313), or {@code null} to leave the
     *                      choice to the out-adapter's own configured display-language preference
     *                      and its fallback chain - mirrors {@link GetRequirement}'s own
     *                      {@code displayLocale}
     * @return the constraint if present, otherwise {@link Optional#empty()}
     */
    Optional<Constraint> get(ProjectId projectId, ConstraintCode code, String displayLocale);
}
