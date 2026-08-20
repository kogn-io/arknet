// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.DuplicateActorCodeException;
import de.hauschel.arknet.actor.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * In-memory test double for {@link ActorRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores actors, keyed by project then opaque
 * identity, so the service's policy can be exercised end-to-end. Insertion order is preserved to
 * make {@link #findAll(ProjectId)} assertions deterministic. {@link #create} mirrors the real
 * out-adapter's in-transaction guards: an identity collision rejects with
 * {@link ResourceAlreadyExistsException}, a business-code collision with
 * {@link DuplicateActorCodeException}. {@link #compareAndUpdate} mirrors the same business-code
 * guard: a code change that collides with a <em>different</em> identity's code rejects the same
 * way, while updating to the identity's own already-held code (the only case any caller today,
 * {@code actor_update}, exercises) does not.</p>
 *
 * <p><strong>Concurrency token.</strong> Mirrors the real {@code WriteFunnel}'s head, minimally: a
 * fresh opaque marker minted on every {@link #create}/{@link #compareAndUpdate}, tracked per
 * identity - {@link #findCurrentByCode} hands it out alongside the actor,
 * {@link #compareAndUpdate} rejects a stale one, exactly the CAS contract the real adapter enforces
 * via {@code arkprov:head}.</p>
 */
final class InMemoryActorRepository implements ActorRepository {

    private final Map<ProjectId, Map<ActorId, Actor>> byProject = new LinkedHashMap<>();
    private final Map<ActorId, RevisionToken> headByIdentity = new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, Actor actor) {
        Map<ActorId, Actor> actors = byProject.computeIfAbsent(projectId, k -> new LinkedHashMap<>());
        if (actors.containsKey(actor.id())) {
            throw new ResourceAlreadyExistsException(projectId, actor.id().value());
        }
        boolean codeTaken = actors.values().stream().anyMatch(a -> a.code().equals(actor.code()));
        if (codeTaken) {
            throw new DuplicateActorCodeException(projectId, actor.code());
        }
        actors.put(actor.id(), actor);
        headByIdentity.put(actor.id(), new RevisionToken(UUID.randomUUID().toString()));
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Actor updated) {
        Map<ActorId, Actor> actors = byProject.getOrDefault(projectId, Map.of());
        if (!actors.containsKey(updated.id())) {
            throw new ActorNotFoundException(projectId, updated.code());
        }
        if (!Objects.equals(headByIdentity.get(updated.id()), expectedHead)) {
            throw new ActorConcurrentlyModifiedException(projectId, updated.code());
        }
        boolean codeTakenByAnotherIdentity = actors.values().stream()
                .anyMatch(a -> !a.id().equals(updated.id()) && a.code().equals(updated.code()));
        if (codeTakenByAnotherIdentity) {
            throw new DuplicateActorCodeException(projectId, updated.code());
        }
        actors.put(updated.id(), updated);
        headByIdentity.put(updated.id(), new RevisionToken(UUID.randomUUID().toString()));
    }

    @Override
    public Optional<Actor> findByCode(ProjectId projectId, ActorCode code) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(a -> a.code().equals(code))
                .findFirst();
    }

    @Override
    public Optional<CurrentActor> findCurrentByCode(ProjectId projectId, ActorCode code) {
        return findByCode(projectId, code)
                .map(actor -> new CurrentActor(actor, headByIdentity.get(actor.id())));
    }

    @Override
    public List<Actor> findAll(ProjectId projectId) {
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }

    @Override
    public void delete(ProjectId projectId, ActorCode code) {
        // The cross-BC reference check (issue #335) is the real out-adapter's concern - this fake
        // only exercises ActorService's own pass-through and the not-found case.
        Map<ActorId, Actor> actors = byProject.getOrDefault(projectId, Map.of());
        ActorId id = actors.values().stream()
                .filter(a -> a.code().equals(code))
                .findFirst()
                .map(Actor::id)
                .orElseThrow(() -> new ActorNotFoundException(projectId, code));
        actors.remove(id);
        headByIdentity.remove(id);
    }
}
