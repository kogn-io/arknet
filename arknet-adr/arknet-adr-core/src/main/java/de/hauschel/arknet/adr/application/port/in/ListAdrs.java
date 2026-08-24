// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: list all recorded architecture decisions.
 *
 * <p>Backs the tool {@code adr_list}. Deliberately a single-method functional interface: several
 * report-building tests in {@code arknet-mcp} construct one inline as a lambda, and
 * {@link CountSkippedAdrs} carries the one additional concern {@code adr_list} needs beyond this
 * (kogn-io/arknet#359) as its own port instead, precisely to keep this one that way.</p>
 */
public interface ListAdrs {

    /**
     * Returns all architecture decisions currently recorded in the given project.
     *
     * <p>Both current-model supersession directions come for free here: with every decision in hand,
     * the implementing service inverts each one's {@code supersededBy} field in memory rather than
     * issuing the reverse query {@link GetAdr} needs for a single decision - plus one bulk read of
     * any pre-#357 legacy {@code arkarch:supersedes} edge still present, folded into the same two
     * directions.</p>
     *
     * <p>Silently shorter than the project's true decision count whenever {@link CountSkippedAdrs}
     * reports a nonzero value for the same project - see there for why.</p>
     *
     * @param projectId the project (architecture model) to list decisions from
     * @return all decisions, never {@code null}
     */
    List<AdrDetail> list(ProjectId projectId);
}
