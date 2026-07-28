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
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;

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
 * the compare-and-set guard (issue #176), so a guarded write path can never be bypassed by
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
     */
    void create(ProjectId projectId, BoundedContext boundedContext);

    /**
     * Replaces an existing bounded context by identity, but only if its current concurrency token
     * (the {@code arkprov:head} revision recorded by the last funnel write, ADR-014) still equals
     * {@code expectedHead} - the compare-and-set guard against the lost-update race (issue #176,
     * the same guard the requirements context got in issue #108/#167). A read-modify-write round
     * trip ({@code bc_link_term}) reads the current state and head together via
     * {@link #findCurrentByCode}, derives {@code updated}, and calls this method with the head it
     * observed - a mismatch means the read was already stale, and the caller must re-read and
     * retry rather than silently discard the concurrent change (an
     * {@code arknet:ubiquitousLanguageTerm} edge a concurrent {@code bc_link_term} had just
     * added).
     *
     * <p><strong>The token guards funnel writers, not store-first edits.</strong>
     * {@code expectedHead} only ever changes when a write goes through the shared
     * {@code WriteFunnel} (ADR-014); a direct store-first (ADR-005) edit to this bounded
     * context's triples leaves the head untouched. Such an edit therefore passes this method's
     * compare-and-set check undetected, and the subsequent replace-by-identity write silently
     * overwrites it. The guard closes the lost-update window between two funnel writers, not
     * between a funnel writer and a write that bypassed the funnel entirely.</p>
     *
     * @param projectId  the project (architecture model) the bounded context lives in
     * @param expectedHead the {@code arkprov:head} revision IRI the caller last observed for this
     *                     bounded context (from {@link #findCurrentByCode}), or {@code null} if
     *                     the caller expects no revision to exist yet
     * @param updated      the bounded context to store in place of the current one, if its head
     *                     still matches {@code expectedHead}
     * @throws BoundedContextNotFoundException             if no bounded context with this identity
     *                                                     exists at all
     * @throws BoundedContextConcurrentlyModifiedException if {@code expectedHead} no longer
     *                                                     matches the stored bounded context's
     *                                                     current head - a concurrent write raced
     *                                                     ahead
     */
    void compareAndUpdate(ProjectId projectId, String expectedHead, BoundedContext updated);

    /**
     * Finds a bounded context by its human-readable business code within a project.
     *
     * @param projectId the project (architecture model) to look up the bounded context in
     * @param code        the bounded-context code (e.g. {@code BC-1})
     * @return the bounded context if present, otherwise {@link Optional#empty()}
     */
    Optional<BoundedContext> findByCode(ProjectId projectId, BoundedContextCode code);

    /**
     * Reads a bounded context's current state together with its concurrency token (the
     * {@code arkprov:head} revision IRI recorded by the last funnel write, ADR-014). The core
     * fields (name, domainVision, subdomain, ownedBy) and the head itself come from one query
     * call - one snapshot - which is the load-bearing guarantee here, not an ordering of clauses
     * within that query. {@code usesTerms}, in contrast, is deliberately filled in by a later,
     * independent follow-up read; that is safe precisely because a later read can only be
     * fresher, never staler, than the head: a funnel write that commits in between moves the
     * head, so the subsequent {@link #compareAndUpdate} then fails its comparison and the caller
     * re-reads instead of overwriting a state it never actually saw. The pairing is therefore
     * conservative - state is never older than the head it is paired with - never optimistic; it
     * does not mean the whole bounded context comes from a single read. Backs the read side of
     * the read-modify-write round trip {@link #compareAndUpdate} guards the write side of.
     *
     * @param projectId the project (architecture model) to look up the bounded context in
     * @param code        the bounded-context code (e.g. {@code BC-1})
     * @return the bounded context and its current head, or {@link Optional#empty()} if no bounded
     *         context with this code exists
     */
    Optional<CurrentBoundedContext> findCurrentByCode(ProjectId projectId, BoundedContextCode code);

    /**
     * A bounded context's state paired with its current concurrency token (the
     * {@code arkprov:head} revision IRI, or {@code null} if the bounded context predates the
     * funnel's revision recording), as read together by {@link #findCurrentByCode}.
     */
    record CurrentBoundedContext(BoundedContext value, String head) {
    }

    /**
     * Returns all bounded contexts stored in a project.
     *
     * @param projectId the project (architecture model) to list bounded contexts from
     * @return all bounded contexts, never {@code null}
     */
    List<BoundedContext> findAll(ProjectId projectId);
}
