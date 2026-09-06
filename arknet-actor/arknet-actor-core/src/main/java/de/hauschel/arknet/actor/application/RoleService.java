// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.actor.application.port.in.AddRole;
import de.hauschel.arknet.actor.application.port.in.DeleteRole;
import de.hauschel.arknet.actor.application.port.in.DescribeRoleDisplayFallback;
import de.hauschel.arknet.actor.application.port.in.GetRole;
import de.hauschel.arknet.actor.application.port.in.ListRoles;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.application.port.in.RoleDetail.FilledByActor;
import de.hauschel.arknet.actor.application.port.in.UpdateRole;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.DuplicateRoleCodeException;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.RoleDisplayFallback;
import de.hauschel.arknet.actor.domain.RoleId;
import de.hauschel.arknet.actor.domain.RoleNotFoundException;
import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.CodeCounter;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Application service implementing the role use cases - the second resource type of the actor
 * hexagon (ADR-37/kogn-io/arknet#405).
 *
 * <p><strong>Multilingual, mirroring {@code ConstraintService}'s policy - not {@link
 * ActorService}'s.</strong> {@code name}/{@code description} are language-tagged (see
 * {@link Role}'s own javadoc for why this hexagon's two resource types disagree); this service
 * resolves and passes through the BCP-47 tags exactly the way {@code ConstraintService} does for
 * {@code title}/{@code statement}, including the "changing a field's language alone is a real
 * write" rule - see {@link #resolveTouchedLanguage}.</p>
 *
 * <p><strong>No gateway to resolve {@code filledBy}.</strong> {@code filledByActorCodes} arrives as
 * human-typed {@code ACTOR-N} codes and must resolve to {@link ActorId}s before a {@link Role} can
 * be built. Since {@link Actor} lives in this very same bounded context ({@code arknet-actor-core}),
 * this service depends on {@link ActorRepository} directly rather than going through a
 * {@code ActorLookup}-style driven port: ADR-008's "no cross-context orchestration without a
 * gateway" binds Bounded Contexts, and Role and Actor are two resource types of one Bounded
 * Context, not two contexts. An unresolvable code is rejected with the very same
 * {@link ActorNotFoundException} {@link ActorService} itself raises for an unknown
 * {@link ActorCode} - one didactic message regardless of which resource type asked. Also used to
 * resolve a {@code filledBy} occupant's business code and name for display, batched via
 * {@link ActorRepository#findAllByIds} in {@link #toDetails}.</p>
 *
 * <p><strong>{@code ROLE-N} is its own counter</strong>, entirely independent of {@code ACTOR-N} -
 * see {@link RoleCode}'s own javadoc.</p>
 */
public class RoleService
        implements AddRole, ListRoles, DescribeRoleDisplayFallback, GetRole, UpdateRole, DeleteRole {

    private static final String CODE_PREFIX = "ROLE";

    /** Mirrors {@link ActorService#MAX_RETRY_ATTEMPTS} exactly. */
    static final int MAX_RETRY_ATTEMPTS = CodeAssignment.DEFAULT_MAX_ATTEMPTS;

    private final RoleRepository repository;
    private final ActorRepository actorRepository;
    private final ResourceIdFactory resourceIdFactory;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port for roles (must not be {@code null})
     * @param actorRepository   the actor hexagon's own persistence port, used to resolve
     *                          {@code filledBy} actor codes and to read back occupants' names for
     *                          display (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added role (must not be
     *                          {@code null})
     */
    public RoleService(RoleRepository repository, ActorRepository actorRepository,
            ResourceIdFactory resourceIdFactory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.actorRepository = Objects.requireNonNull(actorRepository, "actorRepository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
    }

    @Override
    public RoleDetail add(ProjectId projectId, NewRole command, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Resolved once, before the retry loop and before anything is written: an unknown actor
        // code must reject the whole call rather than surface mid-retry.
        List<ActorId> filledBy = resolveActorCodes(projectId, command.filledByActorCodes());
        String language = LanguageTag.resolveWriteLanguage(command.language(), defaultLanguage);
        RoleId id = new RoleId(resourceIdFactory.newId());
        Role role = CodeAssignment.createRetryingOnCodeCollision(MAX_RETRY_ATTEMPTS,
                DuplicateRoleCodeException.class, () -> {
                    RoleCode code = nextCode(projectId);
                    Role candidate = new Role(id, code, command.name(), command.description(), filledBy);
                    repository.create(projectId, candidate, language);
                    return candidate;
                });
        return toDetail(projectId, role);
    }

    @Override
    public List<RoleDetail> list(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        return toDetails(projectId, repository.findAll(projectId, displayLocale));
    }

    @Override
    public Map<RoleCode, RoleDisplayFallback> describe(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAllDisplayFallback(projectId, displayLocale);
    }

    @Override
    public Optional<RoleDetail> get(ProjectId projectId, RoleCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code, displayLocale).map(role -> toDetail(projectId, role));
    }

    @Override
    public RoleDetail update(ProjectId projectId, RoleCode code, String name, String description,
            List<String> filledByActorCodes, String language, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Role role = updateWithOptimisticRetry(projectId, code, name, description, filledByActorCodes, language,
                defaultLanguage);
        return toDetail(projectId, role);
    }

    @Override
    public void delete(ProjectId projectId, RoleCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // The reference check is the out-adapter's business, mirroring ActorService#delete.
        repository.delete(projectId, code);
    }

    /**
     * Read-modify-write helper behind {@link #update}, mirroring
     * {@code ConstraintService#updateWithOptimisticRetry} for the language handling and
     * {@link ActorService#updateWithOptimisticRetry} for the retry shape, plus the tri-state
     * {@code filledByActorCodes} resolution: resolved <strong>once</strong>, outside the retry loop
     * - a code list is either valid for this call or it is not, and re-resolving it on every retry
     * would let the same call reject on one attempt and succeed on the next depending on unrelated
     * timing.
     *
     * <p>A call that changes neither text, neither field's language tag, nor the occupancy is a
     * no-op: it returns the role as read without writing - the same "naming a field with its
     * already-current text but an explicit, different language is still a write" rule
     * {@code ConstraintService} states, which is why {@link Role#equals}-equality alone is not the
     * whole test here, unlike {@link ActorService}.</p>
     */
    private Role updateWithOptimisticRetry(ProjectId projectId, RoleCode code, String name, String description,
            List<String> filledByActorCodes, String language, String defaultLanguage) {
        List<ActorId> resolvedFilledBy =
                filledByActorCodes == null ? null : resolveActorCodes(projectId, filledByActorCodes);
        RoleConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            RoleRepository.CurrentRole current = repository.findCurrentByCode(projectId, code, defaultLanguage)
                    .orElseThrow(() -> new RoleNotFoundException(projectId, code));
            Role updated = new Role(current.value().id(), current.value().code(),
                    name != null ? name : current.value().name(),
                    description != null ? description : current.value().description(),
                    resolvedFilledBy != null ? resolvedFilledBy : current.value().filledBy());
            // name/description each get their own language: a field this call did not name
            // round-trips under the exact tag it was read under (a scoped no-op), never under
            // `language`/`defaultLanguage`. Resolved lazily, per field, mirroring
            // ConstraintService#resolveTouchedLanguage exactly.
            String nameLanguage = resolveTouchedLanguage(name != null, current.value().name(), updated.name(),
                    current.nameLanguage(), language, defaultLanguage);
            String descriptionLanguage = resolveTouchedLanguage(description != null, current.value().description(),
                    updated.description(), current.descriptionLanguage(), language, defaultLanguage);
            if (updated.equals(current.value())
                    && Objects.equals(nameLanguage, current.nameLanguage())
                    && Objects.equals(descriptionLanguage, current.descriptionLanguage())) {
                return current.value();
            }
            try {
                repository.compareAndUpdate(projectId, current.head(), updated, nameLanguage, descriptionLanguage,
                        defaultLanguage);
                return updated;
            } catch (RoleConcurrentlyModifiedException e) {
                // A concurrent writer replaced the role between our read and our write - retry
                // against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * The BCP-47 language tag a single field ({@code name}/{@code description}) is written under -
     * mirrors {@code ConstraintService#resolveTouchedLanguage} exactly.
     */
    private static String resolveTouchedLanguage(boolean touched, String currentText, String updatedText,
            String currentLanguage, String language, String defaultLanguage) {
        boolean languageTouched = touched && (language != null || !Objects.equals(updatedText, currentText));
        return languageTouched
                ? LanguageTag.resolveWriteLanguage(language, defaultLanguage)
                : currentLanguage;
    }

    /**
     * Resolves human-typed {@code ACTOR-N} codes to their current {@link ActorId}s, rejecting the
     * whole call with {@link ActorNotFoundException} the moment one code names no actor in
     * {@code projectId} - before any of {@code role_add}/{@code role_update}'s own write happens.
     *
     * @return the resolved identities, empty if {@code codes} is {@code null}/empty (an unfilled
     *         role)
     */
    private List<ActorId> resolveActorCodes(ProjectId projectId, List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<ActorId> resolved = new ArrayList<>();
        for (String rawCode : codes) {
            ActorCode actorCode = new ActorCode(rawCode);
            Actor actor = actorRepository.findByCode(projectId, actorCode)
                    .orElseThrow(() -> new ActorNotFoundException(projectId, actorCode));
            resolved.add(actor.id());
        }
        return resolved;
    }

    private RoleDetail toDetail(ProjectId projectId, Role role) {
        return toDetails(projectId, List.of(role)).get(0);
    }

    /**
     * Resolves every {@code filledBy} occupant across {@code roles} in one batch round-trip via
     * {@link ActorRepository#findAllByIds} - a role can be filled by several actors and a listing
     * can hold several roles, so this collects the union of every occupant identity across all of
     * them rather than issuing one lookup per role.
     */
    private List<RoleDetail> toDetails(ProjectId projectId, List<Role> roles) {
        if (roles.isEmpty()) {
            return List.of();
        }
        List<ResourceId> allActorIds = roles.stream()
                .flatMap(role -> role.filledBy().stream())
                .map(ActorId::value)
                .distinct()
                .toList();
        Map<ResourceId, Actor> actorsById = new LinkedHashMap<>();
        for (Actor actor : actorRepository.findAllByIds(projectId, allActorIds)) {
            actorsById.put(actor.id().value(), actor);
        }
        return roles.stream()
                .map(role -> new RoleDetail(role, role.filledBy().stream()
                        .map(actorId -> actorsById.get(actorId.value()))
                        .filter(Objects::nonNull)
                        .map(actor -> new FilledByActor(actor.code(), actor.name()))
                        .toList()))
                .toList();
    }

    /**
     * Derives the next free {@code ROLE-N} - own counter, unrelated to {@code ACTOR-N} - mirroring
     * {@link ActorService#nextCode} exactly, including its reliance on {@link RoleRepository
     * #findAllCodes} plus {@link RoleRepository#findRetainedCodes} rather than {@link RoleRepository
     * #findAll} alone (kogn-io/arknet#360's reasoning, ported here).
     */
    private RoleCode nextCode(ProjectId projectId) {
        String prefix = CODE_PREFIX + "-";
        int highestLiving = CodeCounter.highestRunningNumber(prefix,
                repository.findAllCodes(projectId), RoleCode::value);
        int highestRetained = CodeCounter.highestRunningNumber(prefix,
                repository.findRetainedCodes(projectId), RoleCode::value);
        return new RoleCode(prefix + (Math.max(highestLiving, highestRetained) + 1));
    }
}
