// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.TermNotLinkedException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: remove a single {@code arkddd:ubiquitousLanguageTerm} edge between a bounded
 * context and a glossary term.
 *
 * <p>Backs the tool {@code bc_unlink_term}, the counterpart {@link LinkTerm} was missing
 * (kogn-io/arknet#598): dropping one link meant restating the whole set through
 * {@code bc_update}'s {@code terms}, and a set restated from memory silently unlinks whatever it
 * forgets. Symmetric to {@link LinkContext}/{@link UnlinkContext} in every respect - the edge is
 * addressed by the same term code {@link LinkTerm#linkTerm} took to create it, never by the
 * store-internal identity behind it.</p>
 *
 * <p><strong>Never a silent no-op</strong>, for the same reason {@link UnlinkContext} is not:
 * unlinking a term that is not linked is a caller mistake - a typo in the code, or the wrong
 * bounded context - and quietly reporting success would leave the caller believing an edge is gone
 * that is still there. Linking an already-linked term stays idempotent; the asymmetry is
 * deliberate and the same one {@link LinkContext}/{@link UnlinkContext} carry.</p>
 */
public interface UnlinkTerm {

    /**
     * Removes the link from bounded context {@code code} to the glossary term identified by
     * {@code termCode}.
     *
     * @param projectId the project (architecture model) the bounded context lives in
     * @param code      the bounded-context code, e.g. {@code BC-1}
     * @param termCode  the term's human-readable business code, e.g. {@code TERM-1}
     * @return the bounded context without the link
     * @throws de.hauschel.arknet.bc.domain.BoundedContextNotFoundException if {@code code} is unknown
     * @throws TermNotLinkedException if the term is not currently linked to this bounded context
     */
    BoundedContext unlinkTerm(ProjectId projectId, BoundedContextCode code, String termCode);
}
