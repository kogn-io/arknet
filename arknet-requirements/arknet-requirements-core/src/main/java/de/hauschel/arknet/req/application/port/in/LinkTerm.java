// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: link a requirement to a glossary term of the ubiquitous language.
 *
 * <p>Backs the MVP tool {@code req_link_term}. The edge ({@code arkreq:usesTerm}) is owned
 * by the requirements bounded context - that is what keeps the dependency direction
 * requirements -&gt; ubiquitous-language rather than the other way round.</p>
 *
 * <p>{@code termCode} is exactly what a human types (e.g. {@code TERM-1}), never an IRI - the
 * MCP boundary never surfaces the store-internal identity. Resolving it to the term's opaque
 * {@link de.hauschel.arknet.req.domain.TermRef} identity - and rejecting an unknown or
 * ambiguous code - happens in the application service via a dedicated driven lookup port
 * ({@code TermLookup}), not here and not in the driving (MCP) adapter, which has no store
 * access of its own.</p>
 */
public interface LinkTerm {

    /**
     * Links the glossary term identified by {@code termCode} to the requirement {@code code}.
     * Linking an already-linked term is an idempotent no-op.
     *
     * @param code     the requirement code, e.g. {@code FR-1}
     * @param termCode the term's human-readable business code, e.g. {@code TERM-1}
     * @return the requirement including the link
     */
    Requirement linkTerm(ProjectId projectId, RequirementCode code, String termCode);
}
