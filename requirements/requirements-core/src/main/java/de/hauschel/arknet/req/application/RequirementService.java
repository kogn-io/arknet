package de.hauschel.arknet.req.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.SetRequirementStatus;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.WorkspaceId;

/**
 * Application service implementing the requirement use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link RequirementRepository}
 * driven port. The component is wired as a plain object (constructor injection) by the
 * composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Scaffold:</strong> the use-case bodies are intentionally not implemented
 * yet. Identity generation ({@code FR-N}/{@code NFR-N}), status-transition rules and
 * validation are policy to be added later; each method currently throws
 * {@link UnsupportedOperationException}.</p>
 */
public class RequirementService
        implements AddRequirement, ListRequirements, GetRequirement, SetRequirementStatus {

    private final RequirementRepository repository;

    /**
     * Creates the service.
     *
     * @param repository the driven persistence port (must not be {@code null})
     */
    public RequirementService(RequirementRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Requirement add(WorkspaceId workspaceId, NewRequirement command) {
        throw new UnsupportedOperationException("scaffold: add not yet implemented");
    }

    @Override
    public List<Requirement> list(WorkspaceId workspaceId) {
        throw new UnsupportedOperationException("scaffold: list not yet implemented");
    }

    @Override
    public Optional<Requirement> get(WorkspaceId workspaceId, RequirementId id) {
        throw new UnsupportedOperationException("scaffold: get not yet implemented");
    }

    @Override
    public Requirement setStatus(WorkspaceId workspaceId, RequirementId id, RequirementStatus status) {
        throw new UnsupportedOperationException("scaffold: setStatus not yet implemented");
    }
}
