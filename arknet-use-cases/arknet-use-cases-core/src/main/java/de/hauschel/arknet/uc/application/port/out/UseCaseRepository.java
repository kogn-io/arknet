// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseConcurrentlyModifiedException;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve use cases"), not after any
 * technology. Implementations live in adapter modules (e.g. an RDF-backed
 * adapter) and must not leak their mechanism into this contract.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a
 * use case belongs to. A local single-user adapter may treat it as an implicit
 * default; a remote/team adapter uses it to address one of several projects.</p>
 *
 * <p><strong>Create vs. update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug (writing to an id nobody
 * minted, or an id that was already used). {@link #create} and {@link #compareAndUpdate}
 * therefore make that distinction explicit at the port - there is no unconditional update: every
 * correction to an already-created use case goes through the compare-and-set guard, mirroring
 * the requirements/bounded-context bounded contexts, so a guarded write path can never
 * be bypassed by accident.</p>
 */
public interface UseCaseRepository {

    /**
     * Persists a brand-new use case whose identity does not yet exist in the project.
     *
     * @param projectId the project (architecture model) to store the use case in
     * @param useCase     the use case to create
     * @throws ResourceAlreadyExistsException  if a use case with this identity already exists
     * @throws DuplicateUseCaseCodeException   if another use case already carries this use
     *                                          case's {@link UseCaseCode} - identity collision
     *                                          and business-label collision are distinct
     *                                          failure modes
     */
    void create(ProjectId projectId, UseCase useCase);

    /**
     * Replaces an existing use case by identity (including all its derived step resources), but
     * only if its current concurrency token (the {@code arkprov:head} revision recorded by the
     * last funnel write, ADR-014) still equals {@code expectedHead} - the compare-and-set guard
     * against the lost-update race (mirroring {@code RequirementRepository#compareAndUpdate}). A
     * read-modify-write round trip (e.g. {@code uc_update}) reads the current state and head
     * together via {@link #findCurrentByCode}, derives {@code updated}, and calls this method with
     * the head it observed - a mismatch means the read was already stale, and the caller must
     * re-read and retry rather than silently discard the concurrent change.
     *
     * @param projectId    the project (architecture model) the use case lives in
     * @param expectedHead the {@link RevisionToken} the caller last observed for this use case
     *                     (from {@link #findCurrentByCode}), or {@code null} if the caller expects
     *                     no revision to exist yet
     * @param updated      the use case to store in place of the current one, if its head still
     *                     matches {@code expectedHead}
     * @throws UseCaseNotFoundException              if no use case with this identity exists at
     *                                                all
     * @throws UseCaseConcurrentlyModifiedException if {@code expectedHead} no longer matches the
     *                                                stored use case's current head - a
     *                                                concurrent write raced ahead
     */
    void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, UseCase updated);

    /**
     * Finds a use case by its human-readable business code within a project.
     *
     * @param projectId the project (architecture model) to look up the use case in
     * @param code        the use-case code (e.g. {@code UC1})
     * @return the use case if present, otherwise {@link Optional#empty()}
     */
    Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code);

    /**
     * Reads a use case's current state together with its concurrency token (the
     * {@code arkprov:head} revision IRI recorded by the last funnel write, ADR-014). Backs the
     * read side of the read-modify-write round trip {@link #compareAndUpdate} guards the write
     * side of - mirrors {@code RequirementRepository#findCurrentByCode}.
     *
     * @param projectId the project (architecture model) to look up the use case in
     * @param code        the use-case code (e.g. {@code UC1})
     * @return the use case and its current head, or {@link Optional#empty()} if no use case with
     *         this code exists
     */
    Optional<CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code);

    /**
     * A use case's state paired with its current concurrency token (the {@link RevisionToken}, or
     * {@code null} if the use case predates the funnel's revision recording), as read together by
     * {@link #findCurrentByCode}.
     */
    record CurrentUseCase(UseCase value, RevisionToken head) {
    }

    /**
     * Returns all use cases stored in a project.
     *
     * @param projectId the project (architecture model) to list use cases from
     * @return all use cases, never {@code null}
     */
    List<UseCase> findAll(ProjectId projectId);
}
