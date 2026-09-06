// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.Map;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintDisplayFallback;

/**
 * Driving port: for every constraint of a project whose {@code title}/{@code statement} had to
 * fall back past the requested/project-default display language, the tag of the variant actually
 * shown (kogn-io/arknet#475). Backs the fallback-visibility line {@code constraint_list} appends
 * to a constraint whose gap would otherwise be invisible.
 *
 * <p>Deliberately a separate, single-method port rather than a second method on {@link
 * ListConstraints}, for the same reason {@link DescribeRequirementDisplayFallback} is kept out of
 * {@link ListRequirements}: several report-building tests in {@code arknet-mcp} construct {@code
 * ListConstraints} inline as a lambda.</p>
 */
public interface DescribeConstraintDisplayFallback {

    /**
     * @param projectId     the project (architecture model) to list constraints from
     * @param displayLocale the BCP-47 language tag {@code constraint_list} resolved for this call
     *                      (explicit tool argument, else the project's own configured default),
     *                      or {@code null}
     * @return a constraint's code maps to a non-{@linkplain ConstraintDisplayFallback#isEmpty()
     *         empty} fallback only when at least one of its two fields actually fell back; a
     *         constraint showing both fields in the requested language is simply absent from the
     *         map
     */
    Map<ConstraintCode, ConstraintDisplayFallback> describe(ProjectId projectId, String displayLocale);
}
