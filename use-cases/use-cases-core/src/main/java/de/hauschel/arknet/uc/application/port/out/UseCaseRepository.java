package de.hauschel.arknet.uc.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve use cases"), not after any
 * technology. Implementations live in adapter modules (e.g. an RDF-backed
 * adapter) and must not leak their mechanism into this contract.</p>
 *
 * <p>The {@link WorkspaceId} routing key identifies which architecture model a
 * use case belongs to. A local single-user adapter may treat it as an implicit
 * default; a remote/team adapter uses it to address one of several workspaces.</p>
 */
public interface UseCaseRepository {

    /**
     * Persists the given use case, inserting or replacing by identity.
     *
     * @param workspaceId the workspace (architecture model) to store the use case in
     * @param useCase     the use case to store
     */
    void save(WorkspaceId workspaceId, UseCase useCase);

    /**
     * Finds a use case by its identity within a workspace.
     *
     * @param workspaceId the workspace (architecture model) to look up the use case in
     * @param id          the use-case identity
     * @return the use case if present, otherwise {@link Optional#empty()}
     */
    Optional<UseCase> findById(WorkspaceId workspaceId, UseCaseId id);

    /**
     * Returns all use cases stored in a workspace.
     *
     * @param workspaceId the workspace (architecture model) to list use cases from
     * @return all use cases, never {@code null}
     */
    List<UseCase> findAll(WorkspaceId workspaceId);
}
