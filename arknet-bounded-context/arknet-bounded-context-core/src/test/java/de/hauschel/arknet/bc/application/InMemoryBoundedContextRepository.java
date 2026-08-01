// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.RevisionToken;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextConcurrentlyModifiedException;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * In-memory test double for {@link BoundedContextRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores bounded contexts, keyed by project
 * then opaque identity, so the service's policy can be exercised end-to-end. Insertion order is
 * preserved to make {@link #findAll(ProjectId)} assertions deterministic. {@link #create}
 * mirrors the real out-adapter's in-transaction guards: an identity collision rejects with
 * {@link ResourceAlreadyExistsException}, a business-code collision with
 * {@link DuplicateBoundedContextCodeException}.</p>
 *
 * <p><strong>Concurrency token.</strong> Mirrors the real {@link
 * de.hauschel.arknet.persistence.WriteFunnel}'s head, minimally: a fresh opaque marker minted on
 * every {@link #create}/{@link #compareAndUpdate}, tracked per identity - {@link
 * #findCurrentByCode} hands it out alongside the bounded context, {@link #compareAndUpdate}
 * rejects a stale one, exactly the CAS contract the real adapter enforces via
 * {@code arkprov:head}.</p>
 */
final class InMemoryBoundedContextRepository implements BoundedContextRepository {

    private final Map<ProjectId, Map<BoundedContextId, BoundedContext>> byProject = new LinkedHashMap<>();
    private final Map<BoundedContextId, RevisionToken> headByIdentity = new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, BoundedContext boundedContext) {
        Map<BoundedContextId, BoundedContext> contexts = byProject.computeIfAbsent(projectId,
                k -> new LinkedHashMap<>());
        if (contexts.containsKey(boundedContext.id())) {
            throw new ResourceAlreadyExistsException(projectId, boundedContext.id().value());
        }
        boolean codeTaken = contexts.values().stream().anyMatch(bc -> bc.code().equals(boundedContext.code()));
        if (codeTaken) {
            throw new DuplicateBoundedContextCodeException(projectId, boundedContext.code());
        }
        contexts.put(boundedContext.id(), boundedContext);
        headByIdentity.put(boundedContext.id(), new RevisionToken(UUID.randomUUID().toString()));
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, BoundedContext updated) {
        Map<BoundedContextId, BoundedContext> contexts = byProject.getOrDefault(projectId, Map.of());
        if (!contexts.containsKey(updated.id())) {
            throw new BoundedContextNotFoundException(projectId, updated.code());
        }
        if (!Objects.equals(headByIdentity.get(updated.id()), expectedHead)) {
            throw new BoundedContextConcurrentlyModifiedException(projectId, updated.code());
        }
        contexts.put(updated.id(), updated);
        headByIdentity.put(updated.id(), new RevisionToken(UUID.randomUUID().toString()));
    }

    @Override
    public Optional<BoundedContext> findByCode(ProjectId projectId, BoundedContextCode code) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(bc -> bc.code().equals(code))
                .findFirst();
    }

    @Override
    public Optional<CurrentBoundedContext> findCurrentByCode(ProjectId projectId, BoundedContextCode code) {
        return findByCode(projectId, code)
                .map(boundedContext ->
                        new CurrentBoundedContext(boundedContext, headByIdentity.get(boundedContext.id())));
    }

    @Override
    public List<BoundedContext> findAll(ProjectId projectId) {
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }

    @Override
    public List<ResolveBoundedContexts.ResolvedBoundedContext> findByIds(
            ProjectId projectId, List<ResourceId> ids) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(bc -> ids.contains(bc.id().value()))
                .map(bc -> new ResolveBoundedContexts.ResolvedBoundedContext(bc.id().value(), bc.code()))
                .toList();
    }
}
