// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.TermNotLinkedException;

/**
 * Driving port: remove a single {@code arkreq:usesTerm} edge between a requirement and a glossary
 * term.
 *
 * <p>Backs the tool {@code req_unlink_term}, the counterpart {@link LinkTerm} was missing
 * (kogn-io/arknet#598): dropping one link meant restating the whole set through {@code
 * req_update}'s {@code usesTermCodes}, and a set restated from memory silently unlinks whatever it
 * forgets. The edge is addressed by the same term code {@link LinkTerm#linkTerm} took to create
 * it, never by the store-internal identity behind it.</p>
 *
 * <p><strong>Never a silent no-op</strong>: unlinking a term that is not linked is a caller
 * mistake - a typo in the code, or the wrong requirement - and quietly reporting success would
 * leave the caller believing an edge is gone that is still there. Linking an already-linked term
 * stays idempotent; the asymmetry is deliberate.</p>
 *
 * <p><strong>{@code defaultLanguage}</strong>, mirroring {@link LinkTerm}: unlinking a term
 * touches no language-tagged field itself, but the read-modify-write round trip behind this call
 * still needs the project's own default language so an untouched field is echoed back under the
 * project's own language rather than the process-wide configured one.</p>
 */
public interface UnlinkTerm {

    /**
     * Removes the link from requirement {@code code} to the glossary term identified by
     * {@code termCode}.
     *
     * @param code            the requirement code, e.g. {@code FR-1}
     * @param termCode        the term's human-readable business code, e.g. {@code TERM-1}
     * @param defaultLanguage the target project's configured default language, or {@code null} if
     *                        it has none - consulted only for the read this call makes to echo an
     *                        untouched field back, never for a write
     * @return the requirement without the link
     * @throws de.hauschel.arknet.req.domain.RequirementNotFoundException if {@code code} is unknown
     * @throws TermNotLinkedException if the term is not currently linked to this requirement
     */
    Requirement unlinkTerm(ProjectId projectId, RequirementCode code, String termCode, String defaultLanguage);
}
