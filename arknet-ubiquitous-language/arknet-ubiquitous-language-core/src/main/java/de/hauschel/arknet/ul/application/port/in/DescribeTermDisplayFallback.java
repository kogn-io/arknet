// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.in;

import java.util.Map;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermDisplayFallback;

/**
 * Driving port: for every term of a project whose {@code prefLabel}/{@code definition} had to
 * fall back past the requested/project-default display language, the tag of the variant actually
 * shown (kogn-io/arknet#475). Backs the fallback-visibility line {@code term_list} appends to a
 * term whose gap would otherwise be invisible.
 *
 * <p>Deliberately a separate, single-method port rather than a second method on {@link
 * ListTerms}: {@code ListTerms} is constructed inline as a lambda by several report-building
 * tests in {@code arknet-mcp} ({@code ModelViewsTest} among them), and adding a second abstract
 * method there would break every one of them - the same reason {@code CountSkippedAdrs} exists
 * as its own port in {@code arknet-adr} instead of a second method on {@code ListAdrs}.</p>
 */
public interface DescribeTermDisplayFallback {

    /**
     * @param projectId     the project (architecture model) to list terms from
     * @param displayLocale the BCP-47 language tag {@code term_list} resolved for this call
     *                      (explicit tool argument, else the project's own configured default),
     *                      or {@code null}
     * @return a term's code maps to a non-{@linkplain TermDisplayFallback#isEmpty() empty}
     *         fallback only when at least one of its two fields actually fell back; a term
     *         showing both fields in the requested language is simply absent from the map
     */
    Map<TermCode, TermDisplayFallback> describe(ProjectId projectId, String displayLocale);
}
