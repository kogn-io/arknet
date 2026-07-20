// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve bounded contexts"), not after any
 * technology. Implementations live in adapter modules (e.g. an RDF-backed adapter) and must not
 * leak their mechanism into this contract.</p>
 *
 * <p>The {@link WorkspaceId} routing key identifies which architecture model a bounded context
 * belongs to. A local single-user adapter may treat it as an implicit default; a remote/team
 * adapter uses it to address one of several workspaces.</p>
 *
 * <p><strong>Create vs. update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug. {@link #create} and
 * {@link #update} therefore make that distinction explicit at the port.</p>
 */
public interface BoundedContextRepository {

    /**
     * Persists a brand-new bounded context whose identity does not yet exist in the workspace.
     *
     * @param workspaceId    the workspace (architecture model) to store the bounded context in
     * @param boundedContext the bounded context to create
     * @throws ResourceAlreadyExistsException         if a bounded context with this identity
     *                                                already exists
     * @throws DuplicateBoundedContextCodeException   if another bounded context already carries
     *                                                this bounded context's
     *                                                {@link BoundedContextCode} - identity
     *                                                collision and business-label collision are
     *                                                distinct failure modes
     */
    void create(WorkspaceId workspaceId, BoundedContext boundedContext);

    /**
     * Replaces an existing bounded context by identity.
     *
     * @param workspaceId    the workspace (architecture model) the bounded context lives in
     * @param boundedContext the bounded context to store in place of the current one
     * @throws BoundedContextNotFoundException if no bounded context with this identity exists
     */
    void update(WorkspaceId workspaceId, BoundedContext boundedContext);

    /**
     * Finds a bounded context by its human-readable business code within a workspace.
     *
     * @param workspaceId the workspace (architecture model) to look up the bounded context in
     * @param code        the bounded-context code (e.g. {@code BC-1})
     * @return the bounded context if present, otherwise {@link Optional#empty()}
     */
    Optional<BoundedContext> findByCode(WorkspaceId workspaceId, BoundedContextCode code);

    /**
     * Returns all bounded contexts stored in a workspace.
     *
     * @param workspaceId the workspace (architecture model) to list bounded contexts from
     * @return all bounded contexts, never {@code null}
     */
    List<BoundedContext> findAll(WorkspaceId workspaceId);
}
