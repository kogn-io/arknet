// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;

/**
 * Driving port: link a use case to a glossary term of the ubiquitous language it uses.
 *
 * <p>Backs the tool {@code uc_link_term}. Mirrors the sibling requirements bounded context's own
 * {@code LinkTerm} exactly: {@code arkreq:usesTerm}'s domain was widened from {@code
 * arkreq:Requirement} alone to a union of {@code arkreq:Requirement} and {@code arkreq:UseCase}
 * (issue #329), because a use-case-only project (legal since issue #327's Cockburn decision - a
 * {@link UseCase} needs no {@code oslc_rm:satisfies} requirement at all) had no way to anchor a
 * glossary term to anything it actually used, leaving real usage invisible to {@code
 * orphan_check}. The edge still belongs to the requirements bounded context (both classes share
 * the {@code arkreq:} namespace), never to ubiquitous-language - only the set of legal subjects
 * grew.</p>
 *
 * <p>{@code termCode} is exactly what a human types (e.g. {@code TERM-1}), never an IRI - the
 * MCP boundary never surfaces the store-internal identity. Resolving it to the term's opaque
 * {@link de.hauschel.arknet.uc.domain.TermRef} identity - and rejecting an unknown or ambiguous
 * code - happens in the application service via a dedicated driven lookup port
 * ({@code TermLookup}), not here and not in the driving (MCP) adapter, which has no store
 * access of its own.</p>
 */
public interface LinkTerm {

    /**
     * Links the glossary term identified by {@code termCode} to the use case {@code code}.
     * Linking an already-linked term is an idempotent no-op.
     *
     * @param code     the use-case code, e.g. {@code UC1}
     * @param termCode the term's human-readable business code, e.g. {@code TERM-1}
     * @return the use case including the link
     */
    UseCase linkTerm(ProjectId projectId, UseCaseCode code, String termCode);
}
