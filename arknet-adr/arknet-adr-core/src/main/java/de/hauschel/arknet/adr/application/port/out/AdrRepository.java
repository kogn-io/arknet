// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve architecture decisions"), not after any
 * technology. Implementations live in adapter modules (e.g. an RDF-backed adapter) and must not leak
 * their mechanism into this contract.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a decision belongs to. A
 * local single-user adapter may treat it as an implicit default; a remote/team adapter uses it to
 * address one of several projects.</p>
 *
 * <p><strong>Create vs. compare-and-set update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is not a
 * coherent single operation: an identity either already exists (an update) or it does not (a
 * create), and conflating the two would hide a caller bug. {@link #create} and
 * {@link #compareAndUpdate} therefore make that distinction explicit at the port - and there is no
 * unconditional update: every correction to an already-recorded decision goes through the
 * compare-and-set guard, which is what the bounded-context context had to retrofit later
 * and what this one is built with from the start.</p>
 */
public interface AdrRepository {

    /**
     * Persists a brand-new decision whose identity does not yet exist in the project.
     *
     * @param projectId the project (architecture model) to store the decision in
     * @param adr       the decision to create
     * @throws ResourceAlreadyExistsException if a decision with this identity already exists
     * @throws DuplicateAdrCodeException      if another decision already carries this decision's
     *                                        {@link AdrCode} - identity collision and business-label
     *                                        collision are distinct failure modes
     */
    void create(ProjectId projectId, Adr adr);

    /**
     * Replaces an existing decision by identity, but only if its current concurrency token (the
     * {@code arkprov:head} revision recorded by the last funnel write, ADR-014) still equals
     * {@code expectedHead} - the compare-and-set guard against the lost-update race. A
     * read-modify-write round trip ({@code adr_set_status}, {@code adr_supersede}) reads the current
     * state and head together via {@link #findCurrentByCode}, derives {@code updated}, and calls
     * this method with the head it observed; a mismatch means the read was already stale, and the
     * caller must re-read and retry rather than silently discard the concurrent change (a
     * {@code supersedes} edge a concurrent {@code adr_supersede} had just added).
     *
     * <p><strong>The token guards funnel writers, not store-first edits.</strong>
     * {@code expectedHead} only ever changes when a write goes through the shared
     * {@code WriteFunnel} (ADR-014); a direct store-first (ADR-005) edit to this decision's triples
     * leaves the head untouched. Such an edit therefore passes this method's compare-and-set check
     * undetected, and the subsequent replace-by-identity write silently overwrites it. The guard
     * closes the lost-update window between two funnel writers, not between a funnel writer and a
     * write that bypassed the funnel entirely.</p>
     *
     * @param projectId    the project (architecture model) the decision lives in
     * @param expectedHead the {@code arkprov:head} revision IRI the caller last observed for this
     *                     decision (from {@link #findCurrentByCode}), or {@code null} if the caller
     *                     expects no revision to exist yet
     * @param updated      the decision to store in place of the current one, if its head still
     *                     matches {@code expectedHead}
     * @throws AdrNotFoundException              if no decision with this identity exists at all
     * @throws AdrConcurrentlyModifiedException if {@code expectedHead} no longer matches the stored
     *                                          decision's current head - a concurrent write raced
     *                                          ahead
     */
    void compareAndUpdate(ProjectId projectId, String expectedHead, Adr updated);

    /**
     * Finds a decision by its human-readable business code within a project.
     *
     * @param projectId the project (architecture model) to look up the decision in
     * @param code      the ADR code (e.g. {@code ADR-1})
     * @return the decision if present, otherwise {@link Optional#empty()}
     */
    Optional<Adr> findByCode(ProjectId projectId, AdrCode code);

    /**
     * Reads a decision's current state together with its concurrency token (the
     * {@code arkprov:head} revision IRI recorded by the last funnel write, ADR-014). The scalar
     * fields and the head itself come from one query call - one snapshot - which is the load-bearing
     * guarantee here, not an ordering of clauses within that query. The multi-valued edges are
     * deliberately filled in by later, independent follow-up reads; that is safe precisely because a
     * later read can only be fresher, never staler, than the head: a funnel write that commits in
     * between moves the head, so the subsequent {@link #compareAndUpdate} then fails its comparison
     * and the caller re-reads instead of overwriting a state it never actually saw. The pairing is
     * therefore conservative - state is never older than the head it is paired with - never
     * optimistic. Backs the read side of the read-modify-write round trip
     * {@link #compareAndUpdate} guards the write side of.
     *
     * @param projectId the project (architecture model) to look up the decision in
     * @param code      the ADR code (e.g. {@code ADR-1})
     * @return the decision and its current head, or {@link Optional#empty()} if no decision with
     *         this code exists
     */
    Optional<CurrentAdr> findCurrentByCode(ProjectId projectId, AdrCode code);

    /**
     * A decision's state paired with its current concurrency token (the {@code arkprov:head}
     * revision IRI, or {@code null} if the decision predates the funnel's revision recording), as
     * read together by {@link #findCurrentByCode}.
     */
    record CurrentAdr(Adr value, String head) {
    }

    /**
     * Returns all decisions stored in a project.
     *
     * @param projectId the project (architecture model) to list decisions from
     * @return all decisions, never {@code null}
     */
    List<Adr> findAll(ProjectId projectId);

    /**
     * Resolves opaque decision identities to their business codes, in one store round-trip (not one
     * per id) - what turns {@link Adr#supersedes()}'s bare identities into something a human can
     * re-type.
     *
     * <p>Never rejects: an id that resolves to nothing in the project is simply absent from the
     * result, the same contract a sibling hexagon's {@code ResolveTerms}/{@code ResolveRequirements}
     * driving port promises. The caller decides whether "missing" matters.</p>
     *
     * @param projectId the project (architecture model) to resolve identities in
     * @param ids       the opaque identities to resolve; an empty collection yields an empty map
     * @return the resolved identity-to-code mapping, never {@code null}
     */
    Map<AdrId, AdrCode> findCodesByIds(ProjectId projectId, Collection<AdrId> ids);

    /**
     * Reads the backward direction of {@code arkarch:supersedes}: the business codes of every
     * decision whose {@code supersedes} edge points at {@code supersededId}.
     *
     * <p>This exists because the ontology's {@code arkarch:supersededBy} is <em>not</em> written as a
     * second physical triple (see {@link de.hauschel.arknet.adr.application.port.in.SupersedeAdr}).
     * A reverse read is the honest way to answer it - the same backward traversal
     * {@code arknet-mcp}'s {@code TraceabilityGraph#dependents} performs over the very same
     * predicate.</p>
     *
     * @param projectId    the project (architecture model) to read in
     * @param supersededId the identity of the decision to find the successors of
     * @return the superseding decisions' codes, sorted, never {@code null}
     */
    List<AdrCode> findSupersedingCodes(ProjectId projectId, AdrId supersededId);
}
