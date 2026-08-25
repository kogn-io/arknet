// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.actor.application.port.in.ResolveActors;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorReferencedException;
import de.hauschel.arknet.actor.domain.DuplicateActorCodeException;
import de.hauschel.arknet.actor.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve actors"), not after any technology.
 * Implementations live in adapter modules (e.g. an RDF-backed adapter) and must not leak their
 * mechanism into this contract.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model an actor belongs to. A
 * local single-user adapter may treat it as an implicit default; a remote/team adapter uses it to
 * address one of several projects.</p>
 *
 * <p><strong>Create vs. compare-and-set update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does not
 * (a create), and conflating the two would hide a caller bug. {@link #create} and
 * {@link #compareAndUpdate} therefore make that distinction explicit at the port - and there is no
 * unconditional update: every correction to an already-created actor goes through the
 * compare-and-set guard, so a guarded write path can never be bypassed by accident.</p>
 */
public interface ActorRepository {

    /**
     * Persists a brand-new actor whose identity does not yet exist in the project.
     *
     * @param projectId the project (architecture model) to store the actor in
     * @param actor     the actor to create
     * @throws ResourceAlreadyExistsException if an actor with this identity already exists
     * @throws DuplicateActorCodeException    if another actor already carries this actor's
     *                                        {@link ActorCode} - identity collision and
     *                                        business-label collision are distinct failure modes
     * @throws RuntimeException if {@code actor} violates a SHACL write constraint. The concrete
     *                          signal type is deliberately not fixed by this port: a real
     *                          implementation's {@code WriteConstraintViolationException} lives in
     *                          {@code arknet-persistence-support}, a module
     *                          {@code arknet-actor-core} must not depend on.
     */
    void create(ProjectId projectId, Actor actor);

    /**
     * Replaces an existing actor by identity, but only if its current concurrency token still
     * equals {@code expectedHead} - the compare-and-set guard against the lost-update race. A
     * read-modify-write round trip ({@code actor_update}) reads the current state and token
     * together via {@link #findCurrentByCode}, derives {@code updated}, and calls this method with
     * the token it observed - a mismatch means the read was already stale, and the caller must
     * re-read and retry rather than silently discard the concurrent change.
     *
     * <p><strong>The token guards writes made through this port, not edits that bypass it.</strong>
     * {@code expectedHead} only ever changes when a write goes through this port's own
     * {@code create}/{@code compareAndUpdate} (ADR-014); a direct store-first (ADR-005) edit to
     * this actor leaves the token untouched. Such an edit therefore passes this method's
     * compare-and-set check undetected, and the subsequent replace-by-identity write silently
     * overwrites it. The guard closes the lost-update window between two callers of this port, not
     * between a caller of this port and a store-first edit that bypassed it entirely.</p>
     *
     * <p><strong>Business-code uniqueness.</strong> If {@code updated.code()} differs from the code
     * currently stored under this identity, it is checked against every other actor's
     * {@code dcterms:identifier} in the project - the same collision {@link #create} rejects for a
     * brand-new identity, enforced here too rather than left to the fact that no caller in this
     * codebase currently changes the code on an update.</p>
     *
     * @param projectId    the project (architecture model) the actor lives in
     * @param expectedHead the {@link RevisionToken} the caller last observed for this actor (from
     *                     {@link #findCurrentByCode}), or {@code null} if the caller expects no
     *                     revision to exist yet
     * @param updated      the actor to store in place of the current one, if its head still matches
     *                     {@code expectedHead}
     * @throws ActorNotFoundException              if no actor with this identity exists at all
     * @throws ActorConcurrentlyModifiedException  if {@code expectedHead} no longer matches the
     *                                             stored actor's current head - a concurrent write
     *                                             raced ahead
     * @throws DuplicateActorCodeException         if {@code updated.code()} already labels a
     *                                             different actor in the project
     * @throws RuntimeException if {@code updated} violates a SHACL write constraint (see
     *                          {@link #create} for why the type is not fixed here).
     */
    void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Actor updated);

    /**
     * Finds an actor by its human-readable business code within a project.
     *
     * @param projectId the project (architecture model) to look up the actor in
     * @param code      the actor code (e.g. {@code ACTOR-1})
     * @return the actor if present, otherwise {@link Optional#empty()}
     */
    Optional<Actor> findByCode(ProjectId projectId, ActorCode code);

    /**
     * Reads an actor's current state together with its concurrency token (recorded by the last
     * write through this port, ADR-014). State and token come from one query call - one snapshot -
     * which is the load-bearing guarantee here, not an ordering of clauses within that query. Backs
     * the read side of the read-modify-write round trip {@link #compareAndUpdate} guards the write
     * side of.
     *
     * @param projectId the project (architecture model) to look up the actor in
     * @param code      the actor code (e.g. {@code ACTOR-1})
     * @return the actor and its current head, or {@link Optional#empty()} if no actor with this
     *         code exists
     */
    Optional<CurrentActor> findCurrentByCode(ProjectId projectId, ActorCode code);

    /**
     * An actor's state paired with its current concurrency token (the {@link RevisionToken}, or
     * {@code null} if no write has ever been recorded for this actor), as read together by
     * {@link #findCurrentByCode}.
     */
    record CurrentActor(Actor value, RevisionToken head) {
    }

    /**
     * Returns all actors stored in a project.
     *
     * @param projectId the project (architecture model) to list actors from
     * @return all actors, never {@code null}
     */
    List<Actor> findAll(ProjectId projectId);

    /**
     * Deletes the actor identified by {@code code}, and every triple it carries in this
     * hexagon's own named graph, from the project (issue #335). Rejects outright, without deleting
     * anything, if anything else in the project still references the actor - see
     * {@link ActorReferencedException}.
     *
     * @param projectId the project (architecture model) the actor lives in
     * @param code      the actor code, e.g. {@code ACTOR-1}
     * @throws ActorNotFoundException   if no actor with this identity exists
     * @throws ActorReferencedException if anything else in the project still references the actor
     */
    void delete(ProjectId projectId, ActorCode code);

    /**
     * Returns the business codes of actors that were deleted from the project and are kept out of
     * circulation - what {@link #delete} retains so a code can never name two different actors over
     * a project's lifetime (issue #350). Read together with {@link #findAll} whenever the next free
     * code is derived; the two sets are disjoint, since a retained code belongs to an actor that no
     * longer exists.
     *
     * <p>Never rejects and never reports a code twice. An actor deleted <em>without</em> the
     * implementation being able to retain its code is simply absent - the contract is "every code
     * this port could keep", not "every code ever used", and the one implementation-side gap this
     * leaves is documented where it arises.</p>
     *
     * @param projectId the project (architecture model) to read the retained codes of
     * @return the retained codes, never {@code null}
     */
    List<ActorCode> findRetainedCodes(ProjectId projectId);

    /**
     * Finds every actor in a project whose identity is among {@code ids}, in one store
     * round-trip - backs {@link ResolveActors}. This is a batch lookup, not a per-id existence
     * check: an id absent from the project is simply absent from the result, never an error.
     *
     * <p>Returns the slim {@link ResolveActors.ResolvedActor} projection, not the full
     * {@link Actor} aggregate: the only consumer of this method is {@link ResolveActors}, which
     * exists purely to answer "what code names this identity" for display.</p>
     *
     * @param projectId the project (architecture model) to look up actors in
     * @param ids       the opaque identities to resolve; an empty list yields an empty result
     * @return the resolved actors found, in no particular order, never {@code null}
     */
    List<ResolveActors.ResolvedActor> findByIds(ProjectId projectId, List<ResourceId> ids);
}
