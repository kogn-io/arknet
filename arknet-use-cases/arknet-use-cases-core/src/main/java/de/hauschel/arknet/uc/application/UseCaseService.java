package de.hauschel.arknet.uc.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * Application service implementing the use-case use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link UseCaseRepository}
 * driven port. The component is wired as a plain object (constructor injection) by
 * the composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link UseCaseId}) is opaque and minted once per use
 * case via {@link ResourceIdFactory}; it never changes. The human-readable business code
 * ({@link UseCaseCode}, {@code UCn}) is assigned independently, where {@code n} is one above
 * the highest running number currently used in the target workspace (numbering is independent
 * per workspace, starting at 1).</p>
 */
public class UseCaseService implements AddUseCase, GetUseCase, ListUseCases {

    private static final String CODE_PREFIX = "UC";

    private final UseCaseRepository repository;
    private final ResourceIdFactory resourceIdFactory;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added use case (must not be
     *                          {@code null})
     */
    public UseCaseService(UseCaseRepository repository, ResourceIdFactory resourceIdFactory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
    }

    @Override
    public UseCase add(WorkspaceId workspaceId, NewUseCase command) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(command, "command");
        UseCaseId id = new UseCaseId(resourceIdFactory.newId());
        UseCaseCode code = nextCode(workspaceId);
        UseCase useCase = new UseCase(id, code, command.title(), command.goal(), command.scope(),
                command.trigger(), command.primaryActor(), command.supportingActors(),
                command.precondition(), command.postcondition(), command.steps(),
                command.extensions());
        repository.create(workspaceId, useCase);
        return useCase;
    }

    @Override
    public List<UseCase> list(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return repository.findAll(workspaceId);
    }

    @Override
    public Optional<UseCase> get(WorkspaceId workspaceId, UseCaseCode code) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(workspaceId, code);
    }

    /**
     * Derives the next free business code in {@code workspaceId}: the highest running number
     * currently in use, plus one (starting at 1).
     */
    private UseCaseCode nextCode(WorkspaceId workspaceId) {
        int next = repository.findAll(workspaceId).stream()
                .mapToInt(uc -> runningNumber(uc.code()))
                .max()
                .orElse(0) + 1;
        return new UseCaseCode(CODE_PREFIX + next);
    }

    /** Parses the running number from a code such as {@code UC7} (0 if not parseable). */
    private static int runningNumber(UseCaseCode code) {
        String value = code.value();
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
