// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.application.port.out.ActorLookup;
import de.hauschel.arknet.uc.application.port.out.RequirementLookup;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
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
 * the highest running number currently used in the target project (numbering is independent
 * per workspace, starting at 1).</p>
 *
 * <p><strong>Reference resolution (issue #89).</strong> {@code NewUseCase}'s actor/requirement
 * fields are raw human-typed strings, not domain refs - resolving them to the referenced
 * resources' opaque identities is this service's job, via the driven {@link ActorLookup}/
 * {@link RequirementLookup} ports, once per {@link #add}, before the real {@link UseCase} and its
 * {@link Step}s are constructed. An unknown or ambiguous reference propagates as a didactic
 * runtime exception from the lookup, rejecting the write; nothing is persisted.</p>
 *
 * <p><strong>Concurrency (issue #144).</strong> {@link #add} recomputes its next code against a
 * fresh read whenever a concurrent {@code uc_add} claims the same {@code UCn} first, via
 * {@link CodeAssignment#createRetryingOnCodeCollision}; the race is invisible to a well-formed
 * caller. Parallel sessions of one user against one local store are the normal case, not a remote/
 * multi-writer concern (ADR-001).</p>
 */
public class UseCaseService implements AddUseCase, GetUseCase, ListUseCases {

    private static final String CODE_PREFIX = "UC";

    private final UseCaseRepository repository;
    private final ResourceIdFactory resourceIdFactory;
    private final RequirementLookup requirementLookup;
    private final ActorLookup actorLookup;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added use case (must not be
     *                          {@code null})
     * @param requirementLookup resolves a human-typed requirement code to its opaque identity
     *                          (must not be {@code null})
     * @param actorLookup       resolves a human-typed actor name to its opaque identity (must
     *                          not be {@code null})
     */
    public UseCaseService(UseCaseRepository repository, ResourceIdFactory resourceIdFactory,
            RequirementLookup requirementLookup, ActorLookup actorLookup) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.requirementLookup = Objects.requireNonNull(requirementLookup, "requirementLookup");
        this.actorLookup = Objects.requireNonNull(actorLookup, "actorLookup");
    }

    @Override
    public UseCase add(ProjectId projectId, NewUseCase command) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry. Reference
        // resolution likewise happens once, before the retry: an unknown/ambiguous actor or
        // requirement must fail immediately and is not a code collision to retry on. Only the
        // business code is recomputed when a concurrent uc_add claims the same candidate first
        // (issue #144) - see CodeAssignment for why that race exists.
        UseCaseId id = new UseCaseId(resourceIdFactory.newId());
        ActorRef primaryActor = new ActorRef(actorLookup.resolveByName(projectId, command.primaryActor()));
        List<ActorRef> supportingActors = command.supportingActors() == null
                ? List.of()
                : command.supportingActors().stream()
                        .map(name -> new ActorRef(actorLookup.resolveByName(projectId, name)))
                        .toList();
        List<Step> steps = command.steps() == null
                ? List.of()
                : command.steps().stream()
                        .map(step -> toStep(projectId, step))
                        .toList();
        return CodeAssignment.createRetryingOnCodeCollision(DuplicateUseCaseCodeException.class, () -> {
            UseCaseCode code = nextCode(projectId);
            UseCase useCase = new UseCase(id, code, command.title(), command.goal(), command.scope(),
                    command.trigger(), primaryActor, supportingActors,
                    command.precondition(), command.postcondition(), steps,
                    command.extensions());
            repository.create(projectId, useCase);
            return useCase;
        });
    }

    private Step toStep(ProjectId projectId, NewStep step) {
        List<RequirementRef> realises = step.realises() == null
                ? List.of()
                : step.realises().stream()
                        .map(code -> new RequirementRef(requirementLookup.resolveByCode(projectId, code)))
                        .toList();
        return new Step(step.position(), step.text(), realises);
    }

    @Override
    public List<UseCase> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId);
    }

    @Override
    public Optional<UseCase> get(ProjectId projectId, UseCaseCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code);
    }

    /**
     * Derives the next free business code in {@code projectId}: the highest running number
     * currently in use, plus one (starting at 1).
     */
    private UseCaseCode nextCode(ProjectId projectId) {
        int next = repository.findAll(projectId).stream()
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
