// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.ul.domain.TermNotFoundException;
import de.hauschel.arknet.ul.domain.TermNotRelatedException;

/**
 * Driving port: remove a single {@code skos:related} edge between two glossary terms.
 *
 * <p>Backs the tool {@code term_unlink_related}, the counterpart {@link LinkRelatedTerm} was
 * missing (kogn-io/arknet#598): dropping one peer meant restating the whole set through {@code
 * term_update}'s {@code related}, and a set restated from memory silently unlinks whatever it
 * forgets.</p>
 *
 * <p><strong>Removes the edge regardless of which side stores it (kogn-io/arknet#598, defect
 * (a)).</strong> {@code skos:related} is symmetric, but only one direction is ever asserted as a
 * triple - {@code term_list} shows the merged view without saying which side holds it. This port
 * checks both: whichever of {@code code}/{@code peerCode} currently names the other as its own
 * forward peer loses that one triple; the other term's own edges are untouched.</p>
 *
 * <p><strong>Never a silent no-op</strong>, for the same reason {@code UnlinkTerm} (bounded
 * context) and {@code UnlinkContext} are not: unlinking two terms that are not currently related
 * is a caller mistake, and quietly reporting success would leave the caller believing an edge is
 * gone that never existed. Linking an already-related pair stays idempotent; the asymmetry is
 * deliberate.</p>
 */
public interface UnlinkRelatedTerm {

    /**
     * Removes the {@code skos:related} edge between {@code code} and {@code peerCode}, from
     * whichever side currently asserts it.
     *
     * @param projectId the project (architecture model) both terms live in
     * @param code      the term the removal is requested from, e.g. {@code TERM-1}
     * @param peerCode  the peer term's business code, e.g. {@code TERM-2}
     * @return {@code code}'s term, its {@code related} list carrying the merged view of both
     *         directions
     * @throws TermNotFoundException     if {@code code} does not resolve to an existing term in
     *                                    the project
     * @throws TermNotRelatedException   if {@code code} and {@code peerCode} are not currently
     *                                    related in either direction
     */
    Term unlinkRelated(ProjectId projectId, TermCode code, TermCode peerCode);
}
