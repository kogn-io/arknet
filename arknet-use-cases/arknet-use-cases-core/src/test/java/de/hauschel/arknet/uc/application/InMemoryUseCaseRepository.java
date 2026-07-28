// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.kernel.ProjectId;
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
 * Insertion order is preserved to make {@link #findAll(ProjectId)} assertions
 * deterministic.</p>
 */
final class InMemoryUseCaseRepository implements UseCaseRepository {

    private final Map<ProjectId, Map<UseCaseId, UseCase>> byWorkspace = new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, UseCase useCase) {
        Map<UseCaseId, UseCase> useCases = byWorkspace.computeIfAbsent(projectId,
                k -> new LinkedHashMap<>());
        if (useCases.containsKey(useCase.id())) {
            throw new ResourceAlreadyExistsException(projectId, useCase.id().value());
        }
        boolean codeTaken = useCases.values().stream().anyMatch(uc -> uc.code().equals(useCase.code()));
        if (codeTaken) {
            throw new DuplicateUseCaseCodeException(projectId, useCase.code());
        }
        useCases.put(useCase.id(), useCase);
    }

    @Override
    public void update(ProjectId projectId, UseCase useCase) {
        Map<UseCaseId, UseCase> useCases = byWorkspace.getOrDefault(projectId, Map.of());
        if (!useCases.containsKey(useCase.id())) {
            throw new UseCaseNotFoundException(projectId, useCase.code());
        }
        useCases.put(useCase.id(), useCase);
    }

    @Override
    public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code) {
        return byWorkspace.getOrDefault(projectId, Map.of()).values().stream()
                .filter(uc -> uc.code().equals(code))
                .findFirst();
    }

    @Override
    public List<UseCase> findAll(ProjectId projectId) {
        return List.copyOf(byWorkspace.getOrDefault(projectId, Map.of()).values());
    }
}
