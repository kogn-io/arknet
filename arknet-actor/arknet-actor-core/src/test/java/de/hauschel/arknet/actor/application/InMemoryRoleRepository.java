// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.actor.domain.DuplicateRoleCodeException;
import de.hauschel.arknet.actor.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.RoleDisplayFallback;
import de.hauschel.arknet.actor.domain.RoleId;
import de.hauschel.arknet.actor.domain.RoleNotFoundException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * In-memory test double for {@link RoleRepository} - mirrors {@code InMemoryConstraintRepository}
 * exactly, including its "one value per field, not a per-language set" simplification: this fake
 * exercises {@code RoleService}'s per-field language resolution, not the real adapter's
 * capture-preserve-reattach of <em>other</em> language variants, which
 * {@code KognioRdfRoleRepositoryMultilingualTest} pins against a real store instead.
 */
final class InMemoryRoleRepository implements RoleRepository {

    private final Map<ProjectId, Map<RoleId, Role>> byProject = new LinkedHashMap<>();
    private final Map<RoleId, RevisionToken> headByIdentity = new LinkedHashMap<>();
    private final Map<RoleId, String> nameLanguageByIdentity = new LinkedHashMap<>();
    private final Map<RoleId, String> descriptionLanguageByIdentity = new LinkedHashMap<>();
    private final Map<ProjectId, List<RoleCode>> retainedByProject = new LinkedHashMap<>();
    /** Mirrors {@code InMemoryActorRepository#unmaterialisableByProject} exactly. */
    private final Map<ProjectId, List<RoleCode>> unmaterialisableByProject = new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, Role role, String language) {
        Map<RoleId, Role> roles = byProject.computeIfAbsent(projectId, k -> new LinkedHashMap<>());
        if (roles.containsKey(role.id())) {
            throw new ResourceAlreadyExistsException(projectId, role.id().value());
        }
        boolean codeTaken = roles.values().stream().anyMatch(r -> r.code().equals(role.code()));
        if (codeTaken) {
            throw new DuplicateRoleCodeException(projectId, role.code());
        }
        roles.put(role.id(), role);
        headByIdentity.put(role.id(), new RevisionToken(UUID.randomUUID().toString()));
        nameLanguageByIdentity.put(role.id(), language);
        descriptionLanguageByIdentity.put(role.id(), language);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Role updated,
            String nameLanguage, String descriptionLanguage, String defaultLanguage) {
        // Nothing multi-valued to sweep in this fake - defaultLanguage only matters to the real
        // out-adapter's language-variant preservation (see the class javadoc).
        Map<RoleId, Role> roles = byProject.getOrDefault(projectId, Map.of());
        if (!roles.containsKey(updated.id())) {
            throw new RoleNotFoundException(projectId, updated.code());
        }
        if (!Objects.equals(headByIdentity.get(updated.id()), expectedHead)) {
            throw new RoleConcurrentlyModifiedException(projectId, updated.code());
        }
        boolean codeTakenByAnotherIdentity = roles.values().stream()
                .anyMatch(r -> !r.id().equals(updated.id()) && r.code().equals(updated.code()));
        if (codeTakenByAnotherIdentity) {
            throw new DuplicateRoleCodeException(projectId, updated.code());
        }
        roles.put(updated.id(), updated);
        headByIdentity.put(updated.id(), new RevisionToken(UUID.randomUUID().toString()));
        nameLanguageByIdentity.put(updated.id(), nameLanguage);
        descriptionLanguageByIdentity.put(updated.id(), descriptionLanguage);
    }

    @Override
    public Optional<Role> findByCode(ProjectId projectId, RoleCode code, String displayLocale) {
        // Nothing multi-valued to select a language variant from in this plain in-memory fake -
        // displayLocale is accepted and ignored.
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(r -> r.code().equals(code))
                .findFirst();
    }

    @Override
    public Optional<CurrentRole> findCurrentByCode(ProjectId projectId, RoleCode code, String defaultLanguage) {
        return findByCode(projectId, code, null)
                .map(role -> new CurrentRole(role, headByIdentity.get(role.id()),
                        nameLanguageByIdentity.get(role.id()), descriptionLanguageByIdentity.get(role.id())));
    }

    @Override
    public List<Role> findAll(ProjectId projectId, String displayLocale) {
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }

    /** Nothing multi-valued to fall back among in this plain in-memory fake - always empty. */
    @Override
    public Map<RoleCode, RoleDisplayFallback> findAllDisplayFallback(ProjectId projectId, String displayLocale) {
        return Map.of();
    }

    @Override
    public List<RoleCode> findAllCodes(ProjectId projectId) {
        List<RoleCode> codes = new ArrayList<>(
                byProject.getOrDefault(projectId, Map.of()).values().stream().map(Role::code).toList());
        codes.addAll(unmaterialisableByProject.getOrDefault(projectId, List.of()));
        return List.copyOf(codes);
    }

    /** Mirrors {@code InMemoryActorRepository#seedUnmaterialisableCode} exactly. */
    void seedUnmaterialisableCode(ProjectId projectId, RoleCode code) {
        unmaterialisableByProject.computeIfAbsent(projectId, key -> new ArrayList<>()).add(code);
    }

    @Override
    public void delete(ProjectId projectId, RoleCode code) {
        // The cross-BC reference check is the real out-adapter's concern - this fake only
        // exercises RoleService's own pass-through and the not-found case.
        Map<RoleId, Role> roles = byProject.getOrDefault(projectId, Map.of());
        RoleId id = roles.values().stream()
                .filter(r -> r.code().equals(code))
                .findFirst()
                .map(Role::id)
                .orElseThrow(() -> new RoleNotFoundException(projectId, code));
        roles.remove(id);
        headByIdentity.remove(id);
        nameLanguageByIdentity.remove(id);
        descriptionLanguageByIdentity.remove(id);
        retainedByProject.computeIfAbsent(projectId, key -> new ArrayList<>()).add(code);
    }

    @Override
    public List<RoleCode> findRetainedCodes(ProjectId projectId) {
        return List.copyOf(retainedByProject.getOrDefault(projectId, List.of()));
    }
}
