package de.hauschel.arknet.req.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.domain.DuplicateRequirementCodeException;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve requirements"), not after
 * any technology. Implementations live in adapter modules (e.g. an RDF-backed
 * adapter) and must not leak their mechanism into this contract.</p>
 *
 * <p>The {@link WorkspaceId} routing key identifies which architecture model a
 * requirement belongs to. A local single-user adapter may treat it as an implicit
 * default; a remote/team adapter uses it to address one of several workspaces.</p>
 *
 * <p><strong>Create vs. update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug (writing to an id nobody
 * minted, or an id that was already used). {@link #create} and {@link #update} therefore make
 * that distinction explicit at the port.</p>
 */
public interface RequirementRepository {

    /**
     * Persists a brand-new requirement whose identity does not yet exist in the workspace.
     *
     * @param workspaceId the workspace (architecture model) to store the requirement in
     * @param requirement the requirement to create
     * @throws ResourceAlreadyExistsException   if a requirement with this identity already exists
     * @throws DuplicateRequirementCodeException if another requirement already carries this
     *                                            requirement's {@link RequirementCode} - identity
     *                                            collision and business-label collision are
     *                                            distinct failure modes
     */
    void create(WorkspaceId workspaceId, Requirement requirement);

    /**
     * Replaces an existing requirement by identity.
     *
     * @param workspaceId the workspace (architecture model) the requirement lives in
     * @param requirement the requirement to store in place of the current one
     * @throws RequirementNotFoundException if no requirement with this identity exists
     */
    void update(WorkspaceId workspaceId, Requirement requirement);

    /**
     * Finds a requirement by its human-readable business code within a workspace.
     *
     * @param workspaceId the workspace (architecture model) to look up the requirement in
     * @param code        the requirement code (e.g. {@code FR-1})
     * @return the requirement if present, otherwise {@link Optional#empty()}
     */
    Optional<Requirement> findByCode(WorkspaceId workspaceId, RequirementCode code);

    /**
     * Returns all requirements stored in a workspace.
     *
     * @param workspaceId the workspace (architecture model) to list requirements from
     * @return all requirements, never {@code null}
     */
    List<Requirement> findAll(WorkspaceId workspaceId);
}
