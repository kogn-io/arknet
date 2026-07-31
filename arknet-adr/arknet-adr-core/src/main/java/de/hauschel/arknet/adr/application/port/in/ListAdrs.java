// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: list all recorded architecture decisions.
 *
 * <p>Backs the tool {@code adr_list}.</p>
 */
public interface ListAdrs {

    /**
     * Returns all architecture decisions currently recorded in the given project.
     *
     * <p>Both directions of {@code supersedes} come for free here: with every decision in hand, the
     * implementing service inverts the forward edges in memory rather than issuing the reverse query
     * {@link GetAdr} needs for a single decision.</p>
     *
     * @param projectId the project (architecture model) to list decisions from
     * @return all decisions, never {@code null}
     */
    List<AdrDetail> list(ProjectId projectId);
}
