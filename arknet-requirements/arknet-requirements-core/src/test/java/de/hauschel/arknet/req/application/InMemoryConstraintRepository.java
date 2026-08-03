// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.DuplicateConstraintCodeException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;

/**
 * In-memory test double for {@link ConstraintRepository}.
 *
 * <p>A hand-rolled fake (not a mock), mirroring {@code InMemoryRequirementRepository} but far
 * simpler: a {@link Constraint} is immutable once created in this scope, so this fake needs no
 * concurrency token and no update path at all.</p>
 */
public final class InMemoryConstraintRepository implements ConstraintRepository {

    private final Map<ProjectId, Map<ConstraintId, Constraint>> byProject = new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, Constraint constraint) {
        Map<ConstraintId, Constraint> constraints = byProject.computeIfAbsent(projectId, k -> new LinkedHashMap<>());
        if (constraints.containsKey(constraint.id())) {
            throw new ResourceAlreadyExistsException(projectId, constraint.id().value());
        }
        boolean codeTaken = constraints.values().stream().anyMatch(c -> c.code().equals(constraint.code()));
        if (codeTaken) {
            throw new DuplicateConstraintCodeException(projectId, constraint.code());
        }
        constraints.put(constraint.id(), constraint);
    }

    @Override
    public Optional<Constraint> findByCode(ProjectId projectId, ConstraintCode code) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(c -> c.code().equals(code))
                .findFirst();
    }

    @Override
    public List<Constraint> findAll(ProjectId projectId) {
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }

    @Override
    public List<ResolveConstraints.ResolvedConstraint> findByIds(ProjectId projectId, List<ResourceId> ids) {
        Set<ResourceId> wanted = Set.copyOf(ids);
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(c -> wanted.contains(c.id().value()))
                .map(c -> new ResolveConstraints.ResolvedConstraint(c.id().value(), c.code()))
                .toList();
    }
}
