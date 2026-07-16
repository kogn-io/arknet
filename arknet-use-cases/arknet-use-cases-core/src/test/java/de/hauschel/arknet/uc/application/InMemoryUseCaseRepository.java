package de.hauschel.arknet.uc.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * In-memory test double for {@link UseCaseRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores use cases, keyed by
 * workspace then opaque identity, so the service's policy can be exercised end-to-end.
 * Insertion order is preserved to make {@link #findAll(WorkspaceId)} assertions
 * deterministic.</p>
 */
final class InMemoryUseCaseRepository implements UseCaseRepository {

    private final Map<WorkspaceId, Map<UseCaseId, UseCase>> byWorkspace = new LinkedHashMap<>();

    @Override
    public void create(WorkspaceId workspaceId, UseCase useCase) {
        Map<UseCaseId, UseCase> useCases = byWorkspace.computeIfAbsent(workspaceId,
                k -> new LinkedHashMap<>());
        if (useCases.containsKey(useCase.id())) {
            throw new ResourceAlreadyExistsException(workspaceId, useCase.id().value());
        }
        boolean codeTaken = useCases.values().stream().anyMatch(uc -> uc.code().equals(useCase.code()));
        if (codeTaken) {
            throw new DuplicateUseCaseCodeException(workspaceId, useCase.code());
        }
        useCases.put(useCase.id(), useCase);
    }

    @Override
    public void update(WorkspaceId workspaceId, UseCase useCase) {
        Map<UseCaseId, UseCase> useCases = byWorkspace.getOrDefault(workspaceId, Map.of());
        if (!useCases.containsKey(useCase.id())) {
            throw new UseCaseNotFoundException(workspaceId, useCase.code());
        }
        useCases.put(useCase.id(), useCase);
    }

    @Override
    public Optional<UseCase> findByCode(WorkspaceId workspaceId, UseCaseCode code) {
        return byWorkspace.getOrDefault(workspaceId, Map.of()).values().stream()
                .filter(uc -> uc.code().equals(code))
                .findFirst();
    }

    @Override
    public List<UseCase> findAll(WorkspaceId workspaceId) {
        return List.copyOf(byWorkspace.getOrDefault(workspaceId, Map.of()).values());
    }
}
