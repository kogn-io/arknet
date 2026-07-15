package de.hauschel.arknet.uc.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * In-memory test double for {@link UseCaseRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores use cases, keyed by
 * workspace then identity, so the service's policy can be exercised end-to-end.
 * Insertion order is preserved to make {@link #findAll(WorkspaceId)} assertions
 * deterministic.</p>
 */
final class InMemoryUseCaseRepository implements UseCaseRepository {

    private final Map<WorkspaceId, Map<UseCaseId, UseCase>> byWorkspace = new LinkedHashMap<>();

    @Override
    public void save(WorkspaceId workspaceId, UseCase useCase) {
        byWorkspace.computeIfAbsent(workspaceId, k -> new LinkedHashMap<>())
                .put(useCase.id(), useCase);
    }

    @Override
    public Optional<UseCase> findById(WorkspaceId workspaceId, UseCaseId id) {
        return Optional.ofNullable(byWorkspace.getOrDefault(workspaceId, Map.of()).get(id));
    }

    @Override
    public List<UseCase> findAll(WorkspaceId workspaceId) {
        return List.copyOf(byWorkspace.getOrDefault(workspaceId, Map.of()).values());
    }
}
