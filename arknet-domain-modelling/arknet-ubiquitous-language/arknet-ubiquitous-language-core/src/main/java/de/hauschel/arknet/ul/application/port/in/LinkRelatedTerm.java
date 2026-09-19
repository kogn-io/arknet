// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * Driving port: add a single {@code skos:related} edge between two already-existing glossary
 * terms.
 *
 * <p>Backs the tool {@code term_link_related}, the counterpart {@code term_update}'s wholesale
 * {@code related} argument was missing (kogn-io/arknet#598): asserting one more peer meant
 * restating the whole set, and a set restated from memory silently drops whatever it forgets.</p>
 *
 * <p><strong>Direction-aware idempotency (kogn-io/arknet#420/#598).</strong> {@code skos:related}
 * is an {@code owl:SymmetricProperty} whose stored direction carries no meaning - only one
 * direction is ever asserted as a triple, and it may be either term's own. Calling this port when
 * {@code code} already points at {@code peerCode} is an idempotent no-op, exactly like {@code
 * LinkTerm} elsewhere in this codebase; calling it when {@code peerCode} already points at {@code
 * code} instead - the edge exists, just asserted from the other side - is an idempotent no-op too,
 * never a second, redundant forward edge. Either way nothing is written and the term's current,
 * merged state is returned unchanged.</p>
 */
public interface LinkRelatedTerm {

    /**
     * Links glossary term {@code code} to glossary term {@code peerCode} via {@code skos:related},
     * unless the two are already related in either direction.
     *
     * @param projectId the project (architecture model) both terms live in
     * @param code      the term the edge is requested from, e.g. {@code TERM-1}
     * @param peerCode  the peer term's business code, e.g. {@code TERM-2}
     * @return {@code code}'s term, its {@code related} list carrying the merged view of both
     *         directions
     * @throws TermNotFoundException   if {@code code} or {@code peerCode} does not resolve to an
     *                                  existing term in the project
     * @throws IllegalArgumentException if {@code peerCode} names {@code code} itself
     */
    Term linkRelated(ProjectId projectId, TermCode code, TermCode peerCode);
}
