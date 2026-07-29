// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.req.domain.RequirementSchemaTerm;

/**
 * Driving port: self-describes the {@code arkreq:} requirement vocabulary as data - the terms,
 * ontology-sourced definitions and accepted values an MCP client needs before calling
 * {@code req_add}/{@code req_set_status}, instead of having to guess them (issue #31).
 *
 * <p>Unlike every other requirements in-port, this deliberately takes no
 * {@link de.hauschel.arknet.kernel.ProjectId}: the vocabulary is static tool self-description,
 * not project instance data - it does not vary per architecture model.</p>
 *
 * <p>Backs the read-only MVP tool {@code req_schema}.</p>
 */
public interface GetRequirementSchema {

    /**
     * Returns the requirement vocabulary as data: for each term, its ontology-sourced
     * definition and the exact values {@code req_add}/{@code req_set_status} accept.
     *
     * @return the schema terms, never {@code null} or empty
     */
    List<RequirementSchemaTerm> schema();
}
