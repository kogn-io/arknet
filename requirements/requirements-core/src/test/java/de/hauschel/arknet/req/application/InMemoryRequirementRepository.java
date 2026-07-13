package de.hauschel.arknet.req.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.WorkspaceId;

/**
 * In-memory test double for {@link RequirementRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores requirements, keyed by
 * workspace then identity, so the service's policy can be exercised end-to-end.
 * Insertion order is preserved to make {@link #findAll(WorkspaceId)} assertions
 * deterministic.</p>
 */
final class InMemoryRequirementRepository implements RequirementRepository {

    private final Map<WorkspaceId, Map<RequirementId, Requirement>> byWorkspace = new LinkedHashMap<>();

    @Override
    public void save(WorkspaceId workspaceId, Requirement requirement) {
        byWorkspace.computeIfAbsent(workspaceId, k -> new LinkedHashMap<>())
                .put(requirement.id(), requirement);
    }

    @Override
    public Optional<Requirement> findById(WorkspaceId workspaceId, RequirementId id) {
        return Optional.ofNullable(byWorkspace.getOrDefault(workspaceId, Map.of()).get(id));
    }

    @Override
    public List<Requirement> findAll(WorkspaceId workspaceId) {
        return List.copyOf(byWorkspace.getOrDefault(workspaceId, Map.of()).values());
    }
}
