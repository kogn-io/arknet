// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Forwards every {@link ActorRepository} method to a {@code delegate} unchanged - a base class for
 * concurrency-test decorators ({@code ActorServiceConcurrencyTest}) that each intercept exactly one
 * or two calls. Extending this instead of implementing {@link ActorRepository} directly lets a
 * decorator state only the call it changes rather than restating this port's full method list, the
 * way all three decorators used to.
 */
abstract class ForwardingActorRepository implements ActorRepository {

    protected final ActorRepository delegate;

    ForwardingActorRepository(ActorRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public void create(ProjectId projectId, Actor actor) {
        delegate.create(projectId, actor);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Actor updated) {
        delegate.compareAndUpdate(projectId, expectedHead, updated);
    }

    @Override
    public Optional<Actor> findByCode(ProjectId projectId, ActorCode code) {
        return delegate.findByCode(projectId, code);
    }

    @Override
    public Optional<CurrentActor> findCurrentByCode(ProjectId projectId, ActorCode code) {
        return delegate.findCurrentByCode(projectId, code);
    }

    @Override
    public List<Actor> findAll(ProjectId projectId) {
        return delegate.findAll(projectId);
    }

    @Override
    public void delete(ProjectId projectId, ActorCode code) {
        delegate.delete(projectId, code);
    }

    @Override
    public List<ActorCode> findAllCodes(ProjectId projectId) {
        return delegate.findAllCodes(projectId);
    }

    @Override
    public List<ActorCode> findRetainedCodes(ProjectId projectId) {
        return delegate.findRetainedCodes(projectId);
    }

    @Override
    public List<Actor> findAllByIds(ProjectId projectId, List<ResourceId> ids) {
        return delegate.findAllByIds(projectId, ids);
    }
}
