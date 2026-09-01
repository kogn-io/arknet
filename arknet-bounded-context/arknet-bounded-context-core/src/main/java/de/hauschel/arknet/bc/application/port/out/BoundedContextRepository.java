// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextConcurrentlyModifiedException;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve bounded contexts"), not after any
 * technology. Implementations live in adapter modules (e.g. an RDF-backed adapter) and must not
 * leak their mechanism into this contract.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a bounded context
 * belongs to. A local single-user adapter may treat it as an implicit default; a remote/team
 * adapter uses it to address one of several projects.</p>
 *
 * <p><strong>Create vs. compare-and-set update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug. {@link #create} and
 * {@link #compareAndUpdate} therefore make that distinction explicit at the port - and there is
 * no unconditional update: every correction to an already-created bounded context goes through
 * the compare-and-set guard, so a guarded write path can never be bypassed by
 * accident.</p>
 */
public interface BoundedContextRepository {

    /**
     * Persists a brand-new bounded context whose identity does not yet exist in the project.
     *
     * @param projectId    the project (architecture model) to store the bounded context in
     * @param boundedContext the bounded context to create
     * @throws ResourceAlreadyExistsException         if a bounded context with this identity
     *                                                already exists
     * @throws DuplicateBoundedContextCodeException   if another bounded context already carries
     *                                                this bounded context's
     *                                                {@link BoundedContextCode} - identity
     *                                                collision and business-label collision are
     *                                                distinct failure modes
     * @throws RuntimeException if {@code boundedContext} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-bounded-context-core} must not depend on.
     */
    void create(ProjectId projectId, BoundedContext boundedContext);

    /**
     * Replaces an existing bounded context by identity, but only if its current concurrency token
     * still equals {@code expectedHead} - the compare-and-set guard against the lost-update race
     * (the same guard the requirements context got). A read-modify-write round trip
     * ({@code bc_link_term}) reads the current state and token together via
     * {@link #findCurrentByCode}, derives {@code updated}, and calls this method with the token it
     * observed - a mismatch means the read was already stale, and the caller must re-read and
     * retry rather than silently discard the concurrent change (a {@code usesTerms} edge a
     * concurrent {@code bc_link_term} had just added).
     *
     * <p><strong>The token guards writes made through this port, not edits that bypass it.</strong>
     * {@code expectedHead} only ever changes when a write goes through this port's own
     * {@code create}/{@code compareAndUpdate} (ADR-014); a direct store-first (ADR-005) edit to
     * this bounded context leaves the token untouched. Such an edit therefore passes this method's
     * compare-and-set check undetected, and the subsequent replace-by-identity write silently
     * overwrites it. The guard closes the lost-update window between two callers of this port, not
     * between a caller of this port and a store-first edit that bypassed it entirely.</p>
     *
     * <p><strong>Business-code uniqueness.</strong> If {@code updated.code()} differs from the
     * code currently stored under this identity, it is checked against every other bounded
     * context's {@code dcterms:identifier} in the project - the same collision {@link #create}
     * rejects for a brand-new identity, enforced here too rather than left to the fact that no
     * caller in this codebase currently changes the code on an update.</p>
     *
     * @param projectId  the project (architecture model) the bounded context lives in
     * @param expectedHead the {@link RevisionToken} the caller last observed for this bounded
     *                     context (from {@link #findCurrentByCode}), or {@code null} if the caller
     *                     expects no revision to exist yet
     * @param updated      the bounded context to store in place of the current one, if its head
     *                     still matches {@code expectedHead}
     * @throws BoundedContextNotFoundException             if no bounded context with this identity
     *                                                     exists at all
     * @throws BoundedContextConcurrentlyModifiedException if {@code expectedHead} no longer
     *                                                     matches the stored bounded context's
     *                                                     current head - a concurrent write raced
     *                                                     ahead
     * @throws DuplicateBoundedContextCodeException         if {@code updated.code()} already
     *                                                     labels a different bounded context in
     *                                                     the project
     * @throws RuntimeException if {@code updated} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-bounded-context-core} must not depend on.
     */
    void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, BoundedContext updated);

    /**
     * Finds a bounded context by its human-readable business code within a project.
     *
     * @param projectId the project (architecture model) to look up the bounded context in
     * @param code        the bounded-context code (e.g. {@code BC-1})
     * @return the bounded context if present, otherwise {@link Optional#empty()}
     */
    Optional<BoundedContext> findByCode(ProjectId projectId, BoundedContextCode code);

    /**
     * Reads a bounded context's current state together with its concurrency token (recorded by
     * the last write through this port, ADR-014). The core fields (name, domainVision, subdomain,
     * ownedBy) and the token itself come from one query call - one snapshot - which is the
     * load-bearing guarantee here, not an ordering of clauses within that query. {@code usesTerms},
     * in contrast, is deliberately filled in by a later, independent follow-up read; that is safe
     * precisely because a later read can only be fresher, never staler, than the token: a write
     * through this port that commits in between moves the token, so the subsequent
     * {@link #compareAndUpdate} then fails its comparison and the caller re-reads instead of
     * overwriting a state it never actually saw. The pairing is therefore conservative - state is
     * never older than the token it is paired with - never optimistic; it does not mean the whole
     * bounded context comes from a single read. Backs the read side of the read-modify-write round
     * trip {@link #compareAndUpdate} guards the write side of.
     *
     * @param projectId the project (architecture model) to look up the bounded context in
     * @param code        the bounded-context code (e.g. {@code BC-1})
     * @return the bounded context and its current head, or {@link Optional#empty()} if no bounded
     *         context with this code exists
     */
    Optional<CurrentBoundedContext> findCurrentByCode(ProjectId projectId, BoundedContextCode code);

    /**
     * A bounded context's state paired with its current concurrency token (the
     * {@link RevisionToken}, or {@code null} if no write has ever been recorded for this bounded
     * context), as read together by {@link #findCurrentByCode}.
     */
    record CurrentBoundedContext(BoundedContext value, RevisionToken head) {
    }

    /**
     * Returns all bounded contexts stored in a project.
     *
     * @param projectId the project (architecture model) to list bounded contexts from
     * @return all bounded contexts, never {@code null}
     */
    List<BoundedContext> findAll(ProjectId projectId);

    /**
     * Returns the business code of every bounded context recorded in a project, read independently
     * of whether that bounded context can currently be materialised into a {@link BoundedContext} -
     * unlike {@link #findAll}, which joins {@code arknet:name} and {@code arkddd:domainVision} as
     * mandatory and therefore never reports a store-first (ADR-005) context missing either of them,
     * even though its {@code BC-N} is every bit as taken as any other's.
     *
     * <p><strong>Why this exists (kogn-io/arknet#360).</strong>
     * {@link de.hauschel.arknet.bc.application.BoundedContextService#add} derives the next free
     * {@code BC-N} from the highest running number the project already uses. Derived from
     * {@link #findAll}, that maximum silently ignores a name-less or vision-less context - so once
     * such a context holds the project's highest number, the very next {@code bc_add} recomputes
     * that same number, {@link #create}'s in-transaction uniqueness guard rejects it with
     * {@link DuplicateBoundedContextCodeException}, and the retry recomputes it again: not a
     * transient collision two racing callers work their way out of, but a permanently dead
     * {@code bc_add} for that project. This method joins only the type triple and
     * {@code dcterms:identifier} - the pair a bounded context cannot lack and still be one - so the
     * number it feeds the counter never depends on how complete a context's remaining fields
     * happen to be.</p>
     *
     * <p>There is deliberately no companion for deleted contexts: this hexagon has no delete
     * operation at all, so a code recorded here is the only kind of code that was ever handed
     * out.</p>
     *
     * @param projectId the project (architecture model) to read codes from
     * @return every recorded bounded context's business code, never {@code null}
     */
    List<BoundedContextCode> findAllCodes(ProjectId projectId);

    /**
     * Finds every bounded context in a project whose identity is among {@code ids}, in one store
     * round-trip - backs {@link ResolveBoundedContexts}. This is a batch lookup, not a per-id
     * existence check: an id absent from the project is simply absent from the result, never an
     * error.
     *
     * <p>Returns the slim {@link ResolveBoundedContexts.ResolvedBoundedContext} projection, not the
     * full {@link BoundedContext} aggregate, for the same reason
     * {@code TermRepository#findByIds}/{@code RequirementRepository#findByIds} do: the only consumer
     * is {@link ResolveBoundedContexts}, which exists purely to answer "what code names this
     * identity" for display - joining fields such as {@code name}/{@code domainVision} the caller
     * never reads would needlessly exclude a store-first context that carries an identity and a code
     * but happens to miss one of them.</p>
     *
     * @param projectId the project (architecture model) to look up bounded contexts in
     * @param ids       the opaque identities to resolve; an empty list yields an empty result
     * @return the resolved bounded contexts found, in no particular order, never {@code null}
     */
    List<ResolveBoundedContexts.ResolvedBoundedContext> findByIds(ProjectId projectId, List<ResourceId> ids);
}
