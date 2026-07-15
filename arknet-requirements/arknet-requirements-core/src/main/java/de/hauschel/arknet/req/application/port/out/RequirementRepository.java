package de.hauschel.arknet.req.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.kernel.WorkspaceId;

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
 */
public interface RequirementRepository {

    /**
     * Persists the given requirement, inserting or replacing by identity.
     *
     * @param workspaceId the workspace (architecture model) to store the requirement in
     * @param requirement the requirement to store
     */
    void save(WorkspaceId workspaceId, Requirement requirement);

    /**
     * Finds a requirement by its identity within a workspace.
     *
     * @param workspaceId the workspace (architecture model) to look up the requirement in
     * @param id          the requirement identity
     * @return the requirement if present, otherwise {@link Optional#empty()}
     */
    Optional<Requirement> findById(WorkspaceId workspaceId, RequirementId id);

    /**
     * Returns all requirements stored in a workspace.
     *
     * @param workspaceId the workspace (architecture model) to list requirements from
     * @return all requirements, never {@code null}
     */
    List<Requirement> findAll(WorkspaceId workspaceId);
}
