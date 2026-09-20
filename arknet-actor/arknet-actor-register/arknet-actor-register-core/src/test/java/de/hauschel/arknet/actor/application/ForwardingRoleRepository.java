// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.actor.application.port.in.ResolveRoles;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleDisplayFallback;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Forwards every {@link RoleRepository} method to a {@code delegate} unchanged - mirrors
 * {@link ForwardingActorRepository} exactly, for the same reason: a base class for
 * {@code RoleServiceConcurrencyTest}'s decorators, each intercepting exactly one or two calls,
 * so a decorator states only the call it changes rather than restating this port's full method
 * list.
 */
abstract class ForwardingRoleRepository implements RoleRepository {

    protected final RoleRepository delegate;

    ForwardingRoleRepository(RoleRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public void create(ProjectId projectId, Role role, String language) {
        delegate.create(projectId, role, language);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Role updated,
            String nameLanguage, String descriptionLanguage, String defaultLanguage) {
        delegate.compareAndUpdate(projectId, expectedHead, updated, nameLanguage, descriptionLanguage,
                defaultLanguage);
    }

    @Override
    public Optional<Role> findByCode(ProjectId projectId, RoleCode code, String displayLocale) {
        return delegate.findByCode(projectId, code, displayLocale);
    }

    @Override
    public Optional<CurrentRole> findCurrentByCode(ProjectId projectId, RoleCode code, String defaultLanguage) {
        return delegate.findCurrentByCode(projectId, code, defaultLanguage);
    }

    @Override
    public List<Role> findAll(ProjectId projectId, String displayLocale) {
        return delegate.findAll(projectId, displayLocale);
    }

    @Override
    public Map<RoleCode, RoleDisplayFallback> findAllDisplayFallback(ProjectId projectId, String displayLocale) {
        return delegate.findAllDisplayFallback(projectId, displayLocale);
    }

    @Override
    public void delete(ProjectId projectId, RoleCode code) {
        delegate.delete(projectId, code);
    }

    @Override
    public List<RoleCode> findAllCodes(ProjectId projectId) {
        return delegate.findAllCodes(projectId);
    }

    @Override
    public List<RoleCode> findRetainedCodes(ProjectId projectId) {
        return delegate.findRetainedCodes(projectId);
    }

    @Override
    public List<ResolveRoles.ResolvedRole> findByIds(ProjectId projectId, String displayLocale,
            List<ResourceId> ids) {
        return delegate.findByIds(projectId, displayLocale, ids);
    }
}
