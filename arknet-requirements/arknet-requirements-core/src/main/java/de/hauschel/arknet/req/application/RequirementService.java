package de.hauschel.arknet.req.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.LinkTerm;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.SetRequirementStatus;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Application service implementing the requirement use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link RequirementRepository}
 * driven port. The component is wired as a plain object (constructor injection) by the
 * composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity is assigned per type as {@code FR-N}/{@code NFR-N},
 * where {@code N} is one above the highest running number currently used by that type in
 * the target workspace (numbering is independent per type and per workspace). New
 * requirements start {@link RequirementStatus#PROPOSED}. The only advancing status
 * transition is {@code PROPOSED -> ACCEPTED}; setting the status a requirement already
 * has is a no-op, and reverting an accepted requirement is rejected. Linking a glossary
 * term is idempotent and independent of the status lifecycle - terms may be linked to a
 * requirement in any status.</p>
 */
public class RequirementService
        implements AddRequirement, ListRequirements, GetRequirement, SetRequirementStatus, LinkTerm {

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
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(command, "command");
        RequirementId id = nextId(workspaceId, command.type());
        Requirement requirement = new Requirement(id, command.title(), command.description(),
                command.type(), RequirementStatus.PROPOSED, command.priority(), command.motivatedBy(),
                command.qualityCategory(), List.of());
        repository.save(workspaceId, requirement);
        return requirement;
    }

    @Override
    public List<Requirement> list(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return repository.findAll(workspaceId);
    }

    @Override
    public Optional<Requirement> get(WorkspaceId workspaceId, RequirementId id) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(id, "id");
        return repository.findById(workspaceId, id);
    }

    @Override
    public Requirement setStatus(WorkspaceId workspaceId, RequirementId id, RequirementStatus status) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Requirement current = repository.findById(workspaceId, id)
                .orElseThrow(() -> new RequirementNotFoundException(workspaceId, id));
        if (current.status() == status) {
            return current;
        }
        requireLegalTransition(current.status(), status);
        Requirement updated = new Requirement(current.id(), current.title(), current.description(),
                current.type(), status, current.priority(), current.motivatedBy(), current.qualityCategory(),
                current.usesTerms());
        repository.save(workspaceId, updated);
        return updated;
    }

    @Override
    public Requirement linkTerm(WorkspaceId workspaceId, RequirementId id, TermRef term) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(term, "term");
        Requirement current = repository.findById(workspaceId, id)
                .orElseThrow(() -> new RequirementNotFoundException(workspaceId, id));
        if (current.usesTerms().contains(term)) {
            return current;
        }
        List<TermRef> linked = new ArrayList<>(current.usesTerms());
        linked.add(term);
        Requirement updated = new Requirement(current.id(), current.title(), current.description(),
                current.type(), current.status(), current.priority(), current.motivatedBy(),
                current.qualityCategory(), linked);
        repository.save(workspaceId, updated);
        return updated;
    }

    /**
     * Derives the next free identity for {@code type} in {@code workspaceId}: the highest
     * running number currently used by that type, plus one (starting at 1).
     */
    private RequirementId nextId(WorkspaceId workspaceId, RequirementType type) {
        int next = repository.findAll(workspaceId).stream()
                .filter(r -> r.type() == type)
                .mapToInt(r -> runningNumber(r.id()))
                .max()
                .orElse(0) + 1;
        return new RequirementId(type.idPrefix() + "-" + next);
    }

    /** Parses the running number from an id such as {@code FR-7} (0 if not parseable). */
    private static int runningNumber(RequirementId id) {
        String value = id.value();
        int dash = value.lastIndexOf('-');
        if (dash < 0 || dash == value.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void requireLegalTransition(RequirementStatus from, RequirementStatus to) {
        boolean legal = from == RequirementStatus.PROPOSED && to == RequirementStatus.ACCEPTED;
        if (!legal) {
            throw new IllegalStateException(
                    "illegal status transition " + from + " -> " + to);
        }
    }
}
