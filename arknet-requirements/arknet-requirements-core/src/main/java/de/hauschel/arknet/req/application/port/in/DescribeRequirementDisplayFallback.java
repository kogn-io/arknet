// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.Map;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementDisplayFallback;

/**
 * Driving port: for every requirement of a project whose {@code title}/{@code description} had
 * to fall back past the requested/project-default display language, the tag of the variant
 * actually shown (kogn-io/arknet#475). Backs the fallback-visibility line {@code req_list}
 * appends to a requirement whose gap would otherwise be invisible.
 *
 * <p>Deliberately a separate, single-method port rather than a second method on {@link
 * ListRequirements}: {@code ListRequirements} is constructed inline as a lambda by several
 * report-building tests in {@code arknet-mcp}, and adding a second abstract method there would
 * break every one of them - the same reason {@code CountSkippedAdrs} exists as its own port in
 * {@code arknet-adr} instead of a second method on {@code ListAdrs}.</p>
 */
public interface DescribeRequirementDisplayFallback {

    /**
     * @param projectId     the project (architecture model) to list requirements from
     * @param displayLocale the BCP-47 language tag {@code req_list} resolved for this call
     *                      (explicit tool argument, else the project's own configured default),
     *                      or {@code null}
     * @return a requirement's code maps to a non-{@linkplain RequirementDisplayFallback#isEmpty()
     *         empty} fallback only when at least one of its two fields actually fell back; a
     *         requirement showing both fields in the requested language is simply absent from the
     *         map
     */
    Map<RequirementCode, RequirementDisplayFallback> describe(ProjectId projectId, String displayLocale);
}
