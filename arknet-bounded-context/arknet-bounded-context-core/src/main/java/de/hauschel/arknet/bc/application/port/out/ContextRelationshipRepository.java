// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.out;

import java.util.List;

import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipNotFoundException;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: persistence capability the component needs to record, read and remove a
 * {@link ContextRelationship}.
 *
 * <p>Named after the capability ("store and retrieve context relationships"), not after any
 * technology. Implementations live in adapter modules (e.g. an RDF-backed adapter) and must not
 * leak their mechanism into this contract.</p>
 *
 * <p><strong>Idempotent create, not pure create (issue #565).</strong> {@link #createIfAbsent}
 * replaced this port's original pure {@code create}: the triple (upstream, downstream,
 * relationshipType) is now the uniqueness rule a repeated {@code bc_link_context} call is checked
 * against, and that check must run <em>inside</em> the same write transaction as the write itself -
 * a separate {@code findByEdge} read beforehand would leave a check-then-act race between the read
 * and the write, so this port deliberately offers no such read method. Two different relationship
 * types between the same pair remain two distinct resources; only an exact triple match is treated
 * as "already recorded".</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a relationship belongs
 * to, exactly as it does for {@link BoundedContextRepository}.</p>
 */
public interface ContextRelationshipRepository {

    /**
     * Persists {@code relationship} unless a relationship with the exact same
     * (upstream, downstream, relationshipType) triple is already recorded, in which case that
     * already-recorded relationship is returned instead and nothing is written - the in-transaction
     * check the class javadoc's "idempotent create" note requires.
     *
     * @param projectId    the project (architecture model) to store the relationship in
     * @param relationship the relationship to create if absent, already carrying its minted identity
     * @return the persisted relationship - {@code relationship} itself if it was newly created, or
     *         the pre-existing relationship for this triple otherwise (a different
     *         {@link ContextRelationship#id()} than {@code relationship}'s own)
     */
    ContextRelationship createIfAbsent(ProjectId projectId, ContextRelationship relationship);

    /**
     * Removes every relationship carrying the exact (upstream, downstream, relationshipType)
     * triple - normally at most one, but {@code createIfAbsent} only started guarding this triple
     * with #565; a triple recorded earlier under the pre-#565 pure {@code create} may still have
     * more than one resource (tracked for cleanup as issue #573), and leaving one behind would let
     * {@code bc_get}/{@code impact_analysis} keep showing an edge {@code bc_unlink_context} just
     * reported removed.
     *
     * @param projectId        the project (architecture model) the relationship lives in
     * @param upstream         the upstream bounded context's opaque identity
     * @param downstream       the downstream bounded context's opaque identity
     * @param relationshipType the DDD context-mapping pattern classifying the relationship to remove
     * @throws ContextRelationshipNotFoundException if no relationship with this exact triple is
     *                                               currently recorded
     */
    void deleteByEdge(ProjectId projectId, BoundedContextId upstream, BoundedContextId downstream,
            RelationshipType relationshipType);

    /**
     * Finds every relationship {@code context} carries, in either direction (as upstream or as
     * downstream) - backs {@link de.hauschel.arknet.bc.application.port.in.GetBoundedContext} and
     * {@link de.hauschel.arknet.bc.application.port.in.ListBoundedContexts} since issue #565.
     *
     * @param projectId the project (architecture model) to read from
     * @param context   the bounded context's opaque identity to find relationships for
     * @return every relationship naming {@code context} as either upstream or downstream, ordered
     *         by the relationship's own opaque identity, never {@code null}
     */
    List<ContextRelationship> findByContext(ProjectId projectId, BoundedContextId context);

    /**
     * Finds every relationship recorded in a project - the bulk counterpart of
     * {@link #findByContext}, backing {@code bc_list}'s single read of every context's
     * relationships at once rather than one lookup per context.
     *
     * @param projectId the project (architecture model) to read from
     * @return every relationship recorded in {@code projectId}, ordered by the relationship's own
     *         opaque identity, never {@code null}
     */
    List<ContextRelationship> findAll(ProjectId projectId);
}
