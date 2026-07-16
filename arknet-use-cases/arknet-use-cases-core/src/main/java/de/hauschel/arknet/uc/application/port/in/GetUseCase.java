package de.hauschel.arknet.uc.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;

/**
 * Driving port: fetch a single use case by its human-readable business code.
 */
public interface GetUseCase {

    /**
     * Looks up a use case by its business code within a workspace.
     *
     * @param workspaceId the workspace (architecture model) to look up the use case in
     * @param code        the use-case code (e.g. {@code UC1}) - what a human types
     * @return the use case if present, otherwise {@link Optional#empty()}
     */
    Optional<UseCase> get(WorkspaceId workspaceId, UseCaseCode code);
}
