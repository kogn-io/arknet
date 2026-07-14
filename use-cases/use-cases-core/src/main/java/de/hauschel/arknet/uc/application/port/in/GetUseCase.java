package de.hauschel.arknet.uc.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * Driving port: fetch a single use case by identity.
 */
public interface GetUseCase {

    /**
     * Looks up a use case by its identity within a workspace.
     *
     * @param workspaceId the workspace (architecture model) to look up the use case in
     * @param id          the use-case identity
     * @return the use case if present, otherwise {@link Optional#empty()}
     */
    Optional<UseCase> get(WorkspaceId workspaceId, UseCaseId id);
}
