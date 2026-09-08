// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: list all managed bounded contexts, each together with every
 * {@code arkddd:ContextRelationship} it carries.
 *
 * <p>Backs the MVP tool {@code bc_list}. {@code displayLocale} selects which language variant of
 * each context's {@code name}/{@code domainVision} is shown, mirroring {@code ListRoles} exactly
 * (kogn-io/arknet#520) - see {@link DescribeBoundedContextDisplayFallback} for the companion port
 * backing the fallback-visibility line. Returns {@link BoundedContextDetail} rather than the bare
 * {@code BoundedContext} since kogn-io/arknet#565 - see that record's javadoc for why the
 * relationships live in a projection rather than on the aggregate.</p>
 */
public interface ListBoundedContexts {

    /**
     * Returns all bounded contexts currently under management in the given project, each paired
     * with the context relationships it carries.
     *
     * @param projectId     the project (architecture model) to list bounded contexts from
     * @param displayLocale the BCP-47 language tag the caller wants each context's {@code name}/
     *                      {@code domainVision} shown in, overriding this repository's own
     *                      configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged
     * @return all bounded contexts, never {@code null}
     */
    List<BoundedContextDetail> list(ProjectId projectId, String displayLocale);
}
