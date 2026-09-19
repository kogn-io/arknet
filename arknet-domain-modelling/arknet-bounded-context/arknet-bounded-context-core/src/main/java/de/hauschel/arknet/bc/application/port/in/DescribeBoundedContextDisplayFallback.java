// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import java.util.Map;

import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextDisplayFallback;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: for every bounded context of a project whose {@code name}/{@code domainVision} had
 * to fall back past the requested/project-default display language, the tag of the variant actually
 * shown (kogn-io/arknet#520). Backs the fallback-visibility line {@code bc_list} appends to a
 * bounded context whose gap would otherwise be invisible - mirrors
 * {@code DescribeRoleDisplayFallback}/{@code DescribeConstraintDisplayFallback} exactly.
 *
 * <p>Deliberately a separate, single-method port rather than a second method on
 * {@link ListBoundedContexts}, for the same reason {@code DescribeConstraintDisplayFallback} is
 * kept out of {@code ListConstraints}.</p>
 */
public interface DescribeBoundedContextDisplayFallback {

    /**
     * @param projectId     the project (architecture model) to list bounded contexts from
     * @param displayLocale the BCP-47 language tag {@code bc_list} resolved for this call (explicit
     *                      tool argument, else the project's own configured default), or
     *                      {@code null}
     * @return a bounded context's code maps to a non-{@linkplain
     *         BoundedContextDisplayFallback#isEmpty() empty} fallback only when at least one of its
     *         two fields actually fell back; a context showing both fields in the requested
     *         language is simply absent from the map
     */
    Map<BoundedContextCode, BoundedContextDisplayFallback> describe(ProjectId projectId, String displayLocale);
}
