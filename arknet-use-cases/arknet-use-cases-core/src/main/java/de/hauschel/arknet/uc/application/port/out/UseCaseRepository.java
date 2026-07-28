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
 * default; a remote/team adapter uses it to address one of several workspaces.</p>
 *
 * <p><strong>Create vs. update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug (writing to an id nobody
 * minted, or an id that was already used). {@link #create} and {@link #update} therefore make
 * that distinction explicit at the port.</p>
 */
public interface UseCaseRepository {

    /**
     * Persists a brand-new use case whose identity does not yet exist in the workspace.
     *
     * @param projectId the workspace (architecture model) to store the use case in
     * @param useCase     the use case to create
     * @throws ResourceAlreadyExistsException  if a use case with this identity already exists
     * @throws DuplicateUseCaseCodeException   if another use case already carries this use
     *                                          case's {@link UseCaseCode} - identity collision
     *                                          and business-label collision are distinct
     *                                          failure modes
     */
    void create(ProjectId projectId, UseCase useCase);

    /**
     * Replaces an existing use case by identity (including all its derived step resources).
     *
     * @param projectId the workspace (architecture model) the use case lives in
     * @param useCase     the use case to store in place of the current one
     * @throws UseCaseNotFoundException if no use case with this identity exists
     */
    void update(ProjectId projectId, UseCase useCase);

    /**
     * Finds a use case by its human-readable business code within a workspace.
     *
     * @param projectId the workspace (architecture model) to look up the use case in
     * @param code        the use-case code (e.g. {@code UC1})
     * @return the use case if present, otherwise {@link Optional#empty()}
     */
    Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code);

    /**
     * Returns all use cases stored in a workspace.
     *
     * @param projectId the workspace (architecture model) to list use cases from
     * @return all use cases, never {@code null}
     */
    List<UseCase> findAll(ProjectId projectId);
}
