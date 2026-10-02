// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.TermRelation;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: link a bounded context to a glossary term of the ubiquitous language it names.
 *
 * <p>Backs the MVP tool {@code bc_link_term}. The edge
 * ({@code arkddd:ubiquitousLanguageTerm}) is owned by the bounded-context component - that is
 * what keeps the dependency direction bounded-context -&gt; ubiquitous-language rather than the
 * other way round, structurally the same choice requirements made for
 * {@code arkreq:usesTerm}.</p>
 *
 * <p>{@code termCode} is exactly what a human types (e.g. {@code TERM-1}), never an IRI - the
 * MCP boundary never surfaces the store-internal identity. Resolving it to the term's opaque
 * {@link de.hauschel.arknet.kernel.ResourceId} identity - and rejecting an unknown or ambiguous
 * code - happens in the application service via a dedicated driven lookup port
 * ({@code TermLookup}), not here and not in the driving (MCP) adapter, which has no store access
 * of its own.</p>
 *
 * <p>The same port records the second term edge, {@code arkddd:delimitsTerm}
 * ({@link TermRelation#DELIMITS}): a term the context names only to draw its boundary against it.
 * One port with a relation argument rather than a second port, because both edges share the
 * resolution, the idempotency and the read-modify-write; the three-argument form keeps
 * meaning {@link TermRelation#USES}.</p>
 */
public interface LinkTerm {

    /**
     * Links the glossary term identified by {@code termCode} to the bounded context {@code code}.
     * Linking an already-linked term is an idempotent no-op.
     *
     * @param projectId the project (architecture model) the bounded context lives in
     * @param code        the bounded-context code, e.g. {@code BC-1}
     * @param termCode    the term's human-readable business code, e.g. {@code TERM-1}
     * @return the bounded context including the link
     */
    default BoundedContext linkTerm(ProjectId projectId, BoundedContextCode code, String termCode) {
        return linkTerm(projectId, code, termCode, TermRelation.USES);
    }

    /**
     * Records the glossary term identified by {@code termCode} under {@code relation} on the
     * bounded context {@code code}: {@link TermRelation#USES} as part of its language
     * ({@code arkddd:ubiquitousLanguageTerm}), {@link TermRelation#DELIMITS} as a term it names
     * only to draw its boundary against ({@code arkddd:delimitsTerm}). Recording a term under the
     * relation it already carries is an idempotent no-op.
     *
     * @param projectId the project (architecture model) the bounded context lives in
     * @param code      the bounded-context code, e.g. {@code BC-1}
     * @param termCode  the term's human-readable business code, e.g. {@code TERM-1}
     * @param relation  which edge to record
     * @return the bounded context including the edge
     * @throws de.hauschel.arknet.bc.domain.TermRelationConflictException if the term already
     *         carries the other relation
     */
    BoundedContext linkTerm(ProjectId projectId, BoundedContextCode code, String termCode, TermRelation relation);
}
