package de.hauschel.arknet.req.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;

/**
 * In-memory test double for {@link RequirementRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores requirements, keyed by
 * workspace then opaque identity, so the service's policy can be exercised end-to-end.
 * Insertion order is preserved to make {@link #findAll(WorkspaceId)} assertions
 * deterministic.</p>
 */
final class InMemoryRequirementRepository implements RequirementRepository {

    private final Map<WorkspaceId, Map<RequirementId, Requirement>> byWorkspace = new LinkedHashMap<>();

    @Override
    public void create(WorkspaceId workspaceId, Requirement requirement) {
        Map<RequirementId, Requirement> requirements = byWorkspace.computeIfAbsent(workspaceId,
                k -> new LinkedHashMap<>());
        if (requirements.containsKey(requirement.id())) {
            throw new ResourceAlreadyExistsException(workspaceId, requirement.id().value());
        }
        requirements.put(requirement.id(), requirement);
    }

    @Override
    public void update(WorkspaceId workspaceId, Requirement requirement) {
        Map<RequirementId, Requirement> requirements = byWorkspace.getOrDefault(workspaceId, Map.of());
        if (!requirements.containsKey(requirement.id())) {
            throw new RequirementNotFoundException(workspaceId, requirement.code());
        }
        requirements.put(requirement.id(), requirement);
    }

    @Override
    public Optional<Requirement> findByCode(WorkspaceId workspaceId, RequirementCode code) {
        return byWorkspace.getOrDefault(workspaceId, Map.of()).values().stream()
                .filter(r -> r.code().equals(code))
                .findFirst();
    }

    @Override
    public List<Requirement> findAll(WorkspaceId workspaceId) {
        return List.copyOf(byWorkspace.getOrDefault(workspaceId, Map.of()).values());
    }

    @Override
    public List<ResolveRequirements.ResolvedRequirement> findByIds(WorkspaceId workspaceId, List<ResourceId> ids) {
        Set<ResourceId> wanted = Set.copyOf(ids);
        return byWorkspace.getOrDefault(workspaceId, Map.of()).values().stream()
                .filter(r -> wanted.contains(r.id().value()))
                .map(r -> new ResolveRequirements.ResolvedRequirement(r.id().value(), r.code()))
                .toList();
    }
}
