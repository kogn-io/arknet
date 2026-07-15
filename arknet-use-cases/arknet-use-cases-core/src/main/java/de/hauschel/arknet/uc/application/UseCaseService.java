package de.hauschel.arknet.uc.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * Application service implementing the use-case use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link UseCaseRepository}
 * driven port. The component is wired as a plain object (constructor injection) by
 * the composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity is assigned as {@code UCn}, where {@code n}
 * is one above the highest running number currently used in the target workspace
 * (numbering is independent per workspace, starting at 1).</p>
 */
public class UseCaseService implements AddUseCase, GetUseCase, ListUseCases {

    private static final String ID_PREFIX = "UC";

    private final UseCaseRepository repository;

    /**
     * Creates the service.
     *
     * @param repository the driven persistence port (must not be {@code null})
     */
    public UseCaseService(UseCaseRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public UseCase add(WorkspaceId workspaceId, NewUseCase command) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(command, "command");
        UseCaseId id = nextId(workspaceId);
        UseCase useCase = new UseCase(id, command.title(), command.goal(), command.scope(),
                command.trigger(), command.primaryActor(), command.supportingActors(),
                command.precondition(), command.postcondition(), command.steps(),
                command.extensions());
        repository.save(workspaceId, useCase);
        return useCase;
    }

    @Override
    public List<UseCase> list(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return repository.findAll(workspaceId);
    }

    @Override
    public Optional<UseCase> get(WorkspaceId workspaceId, UseCaseId id) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(id, "id");
        return repository.findById(workspaceId, id);
    }

    /**
     * Derives the next free identity in {@code workspaceId}: the highest running
     * number currently in use, plus one (starting at 1).
     */
    private UseCaseId nextId(WorkspaceId workspaceId) {
        int next = repository.findAll(workspaceId).stream()
                .mapToInt(uc -> runningNumber(uc.id()))
                .max()
                .orElse(0) + 1;
        return new UseCaseId(ID_PREFIX + next);
    }

    /** Parses the running number from an id such as {@code UC7} (0 if not parseable). */
    private static int runningNumber(UseCaseId id) {
        String value = id.value();
        int i = 0;
        while (i < value.length() && !Character.isDigit(value.charAt(i))) {
            i++;
        }
        if (i >= value.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
