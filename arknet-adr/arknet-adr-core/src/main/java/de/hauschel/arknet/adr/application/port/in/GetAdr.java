// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: fetch a single architecture decision by its business code.
 *
 * <p>Backs the tool {@code adr_get}.</p>
 */
public interface GetAdr {

    /**
     * Looks up an architecture decision by its business code within a project.
     *
     * @param projectId the project (architecture model) to look up the decision in
     * @param code      the ADR code, e.g. {@code ADR-1}
     * @return the decision if present, otherwise {@link Optional#empty()}
     */
    Optional<AdrDetail> get(ProjectId projectId, AdrCode code);
}
