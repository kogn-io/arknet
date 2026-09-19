// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.TermNotLinkedException;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;

/**
 * Driving port: remove a single {@code arkreq:usesTerm} edge between a use case and a glossary
 * term.
 *
 * <p>Backs the tool {@code uc_unlink_term}, the counterpart {@link LinkTerm} was missing
 * (kogn-io/arknet#598): dropping one link meant restating the whole set through
 * {@code uc_update}'s {@code usesTermCodes}, and a set restated from memory silently unlinks
 * whatever it forgets. Mirrors {@code de.hauschel.arknet.bc.application.port.in.UnlinkTerm} in
 * the bounded-context BC in every respect - the edge is addressed by the same term code
 * {@link LinkTerm#linkTerm} took to create it, never by the store-internal identity behind it.</p>
 *
 * <p><strong>Never a silent no-op.</strong> Unlinking a term that is not linked is a caller
 * mistake - a typo in the code, or the wrong use case - and quietly reporting success would leave
 * the caller believing an edge is gone that is still there. Linking an already-linked term stays
 * idempotent; the asymmetry is deliberate, mirroring {@code UnlinkContext}/{@code LinkContext}
 * elsewhere in the codebase.</p>
 */
public interface UnlinkTerm {

    /**
     * Removes the link from use case {@code code} to the glossary term identified by
     * {@code termCode}.
     *
     * @param projectId       the project (architecture model) the use case lives in
     * @param code            the use-case code, e.g. {@code UC1}
     * @param termCode        the term's human-readable business code, e.g. {@code TERM-1}
     * @param defaultLanguage the target project's configured default language, or {@code null} if
     *                        it has none - consulted only for the read this call makes to echo an
     *                        untouched field back, never for a write (mirrors {@link LinkTerm})
     * @return the use case without the link
     * @throws de.hauschel.arknet.uc.domain.UseCaseNotFoundException if {@code code} is unknown
     * @throws TermNotLinkedException if the term is not currently linked to this use case
     */
    UseCase unlinkTerm(ProjectId projectId, UseCaseCode code, String termCode, String defaultLanguage);
}
