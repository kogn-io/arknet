// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.domain.DuplicateRequirementCodeException;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementReadConflictException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.req.domain.UnsupportedRequirementStatusException;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve requirements"), not after
 * any technology. Implementations live in adapter modules (e.g. an RDF-backed
 * adapter) and must not leak their mechanism into this contract.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a
 * requirement belongs to. A local single-user adapter may treat it as an implicit
 * default; a remote/team adapter uses it to address one of several projects.</p>
 *
 * <p><strong>Create vs. update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug (writing to an id nobody
 * minted, or an id that was already used). {@link #create} and {@link #compareAndUpdate}
 * therefore make that distinction explicit at the port - there is no unconditional update: every
 * correction to an already-created requirement goes through the compare-and-set guard, so a
 * guarded write path can never be bypassed by accident.</p>
 */
public interface RequirementRepository {

    /**
     * Persists a brand-new requirement whose identity does not yet exist in the project.
     *
     * @param projectId the project (architecture model) to store the requirement in
     * @param requirement the requirement to create
     * @throws ResourceAlreadyExistsException   if a requirement with this identity already exists
     * @throws DuplicateRequirementCodeException if another requirement already carries this
     *                                            requirement's {@link RequirementCode} - identity
     *                                            collision and business-label collision are
     *                                            distinct failure modes
     * @throws RuntimeException if {@code requirement} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-requirements-core} must not depend on.
     */
    void create(ProjectId projectId, Requirement requirement);

    /**
     * Replaces an existing requirement by identity, but only if its current concurrency token
     * still equals {@code expectedHead}; otherwise the write is rejected and nothing is
     * persisted. A read-modify-write round trip (e.g. {@code req_link_term}, {@code
     * req_set_status}) reads the current state and head together via {@link #findCurrentByCode},
     * derives {@code updated}, and calls this method with the head it observed - a mismatch means
     * the read was already stale, and the caller must re-read and retry rather than silently
     * discard the concurrent change.
     *
     * <p><strong>Guards funnel writers, not store-first edits.</strong> {@code expectedHead} is
     * the {@code arkprov:head} revision recorded by the last write through the shared
     * {@code WriteFunnel} (ADR-014 compare-and-set guard against the lost-update race, degenerated
     * from a full-snapshot comparison to a head comparison). A
     * direct store-first (ADR-005) edit to this requirement's triples leaves the head untouched,
     * so such an edit passes this method's check undetected and the subsequent
     * replace-by-identity write silently overwrites it: the guard closes the lost-update window
     * between two funnel writers, not between a funnel writer and a write that bypassed the
     * funnel entirely.</p>
     *
     * @param projectId  the project (architecture model) the requirement lives in
     * @param expectedHead the {@link RevisionToken} the caller last observed for this requirement
     *                     (from {@link #findCurrentByCode}), or {@code null} if the caller expects
     *                     no revision to exist yet
     * @param updated      the requirement to store in place of the current one, if its head still
     *                     matches {@code expectedHead}
     * @throws RequirementNotFoundException              if no requirement with this identity
     *                                                    exists at all
     * @throws RequirementConcurrentlyModifiedException if {@code expectedHead} no longer matches
     *                                                    the stored requirement's current head - a
     *                                                    concurrent write raced ahead
     * @throws RuntimeException if {@code updated} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-requirements-core} must not depend on.
     */
    void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Requirement updated);

    /**
     * Finds a requirement by its human-readable business code within a project.
     *
     * <p><strong>One consistent snapshot.</strong> Unlike {@link #findCurrentByCode}, this
     * method pairs no concurrency token with its result that a caller compares before acting on
     * it - the returned {@link Requirement} is the caller's whole view of the store's state, so
     * every field on it, including {@code usesTerms} and {@code acceptanceCriteria}, is
     * guaranteed to come from one consistent snapshot of the store, never a combination of field
     * values that never coexisted at any single point in time.
     *
     * @param projectId the project (architecture model) to look up the requirement in
     * @param code        the requirement code (e.g. {@code FR-1})
     * @return the requirement if present, otherwise {@link Optional#empty()}
     * @throws UnsupportedRequirementStatusException if the found requirement's stored status is
     *                                                 SHACL-legal but not one of the MVP subset
     *                                                 {@link de.hauschel.arknet.req.domain.RequirementStatus}
     *                                                 implements (only reachable via a store-first,
     *                                                 ADR-005 edit)
     * @throws RequirementReadConflictException if a bounded, adapter-internal retry loop keeps
     *                                            losing the {@code SERIALIZABLE} race against
     *                                            concurrent writers of this project's requirements
     *                                            (a pathological, sustained contention case)
     */
    Optional<Requirement> findByCode(ProjectId projectId, RequirementCode code);

    /**
     * Reads a requirement's current state together with its concurrency token, backing the read
     * side of the read-modify-write round trip whose write side {@link #compareAndUpdate} guards.
     *
     * <p><strong>What "together" guarantees.</strong> The core fields (type, title, description,
     * status, priority, motivatedBy, qualityCategory) and the head itself (the
     * {@code arkprov:head} revision IRI recorded by the last funnel write, ADR-014) come from one
     * query call - one snapshot. {@code usesTerms} and {@code acceptanceCriteria}, in contrast,
     * are filled in by later, independent follow-up reads; that is safe because a later read can
     * only be fresher, never staler, than the head - a funnel write committing in between moves
     * the head, so the subsequent {@link #compareAndUpdate} fails its comparison and the caller
     * re-reads instead of overwriting state it never saw. The pairing is therefore conservative
     * (state is never older than its paired head), not a guarantee that the whole requirement
     * comes from a single read.</p>
     *
     * @param projectId the project (architecture model) to look up the requirement in
     * @param code        the requirement code (e.g. {@code FR-1})
     * @return the requirement and its current head, or {@link Optional#empty()} if no requirement
     *         with this code exists
     * @throws UnsupportedRequirementStatusException if the found requirement's stored status is
     *                                                 SHACL-legal but not one of the MVP subset
     *                                                 {@link de.hauschel.arknet.req.domain.RequirementStatus}
     *                                                 implements (only reachable via a store-first,
     *                                                 ADR-005 edit)
     */
    Optional<CurrentRequirement> findCurrentByCode(ProjectId projectId, RequirementCode code);

    /**
     * A requirement's state paired with its current concurrency token (the {@link RevisionToken},
     * or {@code null} if the requirement predates the funnel's revision recording), as read
     * together by {@link #findCurrentByCode}.
     *
     * @param value                          the requirement as currently read, with {@code
     *                                        acceptanceCriteriaIsSynthesized} explaining whether
     *                                        {@code value.acceptanceCriteria()} is a real store
     *                                        value or a read-time stand-in
     * @param head                           the concurrency token, or {@code null}
     * @param acceptanceCriteriaIsSynthesized whether {@code value.acceptanceCriteria()} is the
     *                                        adapter's fixed legacy-placeholder text rather than
     *                                        criteria actually recorded in the store - {@code
     *                                        true} exactly when the underlying subject carries no
     *                                        {@code arkreq:acceptanceCriterion} triple at all (a
     *                                        requirement that predates the mandatory-criterion
     *                                        invariant). A caller that carries this flag's {@code
     *                                        value} forward into a write (e.g. a read-modify-write
     *                                        round trip that leaves {@code acceptanceCriteria}
     *                                        untouched) would otherwise turn that read-time
     *                                        stand-in into a real, persisted literal - the bug
     *                                        this flag exists to let a caller reject instead.
     */
    record CurrentRequirement(Requirement value, RevisionToken head, boolean acceptanceCriteriaIsSynthesized) {
    }

    /**
     * Returns all requirements stored in a project.
     *
     * <p><strong>One consistent snapshot for the whole list.</strong> Same guarantee as
     * {@link #findByCode} - see that method's javadoc - but for every requirement in the project
     * at once: the whole result comes from one consistent snapshot of the store, so no returned
     * {@link Requirement} can combine field values that never coexisted at any single point in
     * time, and a funnel write landing while this call is in flight cannot tear one requirement
     * against another either.
     *
     * @param projectId the project (architecture model) to list requirements from
     * @return all requirements, never {@code null}
     * @throws UnsupportedRequirementStatusException if any requirement's stored status is
     *                                                 SHACL-legal but not one of the MVP subset
     *                                                 {@link de.hauschel.arknet.req.domain.RequirementStatus}
     *                                                 implements (only reachable via a store-first,
     *                                                 ADR-005 edit) - one such requirement aborts
     *                                                 the whole listing rather than being silently
     *                                                 dropped
     * @throws RequirementReadConflictException if a bounded, adapter-internal retry loop keeps
     *                                            losing the {@code SERIALIZABLE} race against
     *                                            concurrent writers of this project's requirements
     *                                            (a pathological, sustained contention case)
     */
    List<Requirement> findAll(ProjectId projectId);

    /**
     * Finds every requirement in a project whose identity is among {@code ids}, in one store
     * round-trip - backs {@link ResolveRequirements}. This is a batch lookup, not a
     * per-id existence check: an id absent from the project is simply absent from the result,
     * never an error.
     *
     * <p>Returns the slim {@link ResolveRequirements.ResolvedRequirement} projection, not the
     * full {@link Requirement} aggregate: the only consumer of this method is
     * {@link ResolveRequirements}, which exists purely to answer "what code names this identity"
     * for display - joining fields such as {@code title}/{@code description} the caller never
     * reads would needlessly exclude a store-first requirement that carries an identity and a
     * code but happens to miss one of them.</p>
     *
     * @param projectId the project (architecture model) to look up requirements in
     * @param ids         the opaque identities to resolve; an empty list yields an empty result
     * @return the resolved requirements found, in no particular order, never {@code null}
     */
    List<ResolveRequirements.ResolvedRequirement> findByIds(ProjectId projectId, List<ResourceId> ids);
}
