// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorDisplayFallback;
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
    public void create(ProjectId projectId, Actor actor, String language) {
        delegate.create(projectId, actor, language);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Actor updated,
            String nameLanguage, String descriptionLanguage, String defaultLanguage) {
        delegate.compareAndUpdate(projectId, expectedHead, updated, nameLanguage, descriptionLanguage,
                defaultLanguage);
    }

    @Override
    public Optional<Actor> findByCode(ProjectId projectId, ActorCode code, String displayLocale) {
        return delegate.findByCode(projectId, code, displayLocale);
    }

    @Override
    public Optional<CurrentActor> findCurrentByCode(ProjectId projectId, ActorCode code, String defaultLanguage) {
        return delegate.findCurrentByCode(projectId, code, defaultLanguage);
    }

    @Override
    public List<Actor> findAll(ProjectId projectId, String displayLocale) {
        return delegate.findAll(projectId, displayLocale);
    }

    @Override
    public Map<ActorCode, ActorDisplayFallback> findAllDisplayFallback(ProjectId projectId, String displayLocale) {
        return delegate.findAllDisplayFallback(projectId, displayLocale);
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
