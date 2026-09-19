// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import java.util.Map;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseDisplayFallback;

/**
 * Driving port: for every use case of a project whose {@code title}/{@code goal} had to fall
 * back past the requested/project-default display language, the tag of the variant actually
 * shown (kogn-io/arknet#475). Backs the fallback-visibility line {@code uc_list} appends to a
 * use case whose gap would otherwise be invisible.
 *
 * <p>Deliberately a separate, single-method port rather than a second method on {@link
 * ListUseCases}: {@code ListUseCases} is constructed inline as a lambda by several
 * report-building tests in {@code arknet-mcp}, and adding a second abstract method there would
 * break every one of them - the same reason {@code CountSkippedAdrs} exists as its own port in
 * {@code arknet-adr} instead of a second method on {@code ListAdrs}.</p>
 */
public interface DescribeUseCaseDisplayFallback {

    /**
     * @param projectId     the project (architecture model) to list use cases from
     * @param displayLocale the BCP-47 language tag {@code uc_list} resolved for this call
     *                      (explicit tool argument, else the project's own configured default),
     *                      or {@code null}
     * @return a use case's code maps to a non-{@linkplain UseCaseDisplayFallback#isEmpty()
     *         empty} fallback only when at least one of its two shown fields actually fell back;
     *         a use case showing both fields in the requested language is simply absent from the
     *         map
     */
    Map<UseCaseCode, UseCaseDisplayFallback> describe(ProjectId projectId, String displayLocale);
}
