// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.util.Map;

import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrDisplayFallback;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: for every decision of a project whose {@code name} had to fall back past the
 * requested/project-default display language, the tag of the variant actually shown
 * (kogn-io/arknet#475). Backs the fallback-visibility line {@code adr_list} appends to a
 * decision whose gap would otherwise be invisible.
 *
 * <p>Deliberately a separate, single-method port rather than a second method on {@link
 * ListAdrs} - the same reason {@link CountSkippedAdrs} is its own port: several report-building
 * tests in {@code arknet-mcp} construct {@code ListAdrs} inline as a lambda, and a second
 * abstract method there would break every one of them.</p>
 */
public interface DescribeAdrDisplayFallback {

    /**
     * @param projectId     the project (architecture model) to list decisions from
     * @param displayLocale the BCP-47 language tag {@code adr_list} resolved for this call
     *                      (explicit tool argument, else the project's own configured default),
     *                      or {@code null}
     * @return a decision's code maps to a non-{@linkplain AdrDisplayFallback#isEmpty() empty}
     *         fallback only when its {@code name} actually fell back; a decision showing its
     *         name in the requested language is simply absent from the map
     */
    Map<AdrCode, AdrDisplayFallback> describe(ProjectId projectId, String displayLocale);
}
