// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.out.RevisionToken;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseConcurrentlyModifiedException;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * In-memory test double for {@link UseCaseRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores use cases, keyed by
 * project then opaque identity, so the service's policy can be exercised end-to-end.
 * Insertion order is preserved to make {@link #findAll(ProjectId)} assertions
 * deterministic.</p>
 *
 * <p><strong>Concurrency token.</strong> Mirrors the real {@link
 * de.hauschel.arknet.persistence.WriteFunnel}'s head, minimally: a fresh opaque marker minted on
 * every {@link #create}/{@link #compareAndUpdate}, tracked per identity - {@link
 * #findCurrentByCode} hands it out alongside the use case, {@link #compareAndUpdate} rejects a
 * stale one, exactly the CAS contract the real adapter enforces via {@code arkprov:head}.</p>
 */
final class InMemoryUseCaseRepository implements UseCaseRepository {

    private final Map<ProjectId, Map<UseCaseId, UseCase>> byProject = new LinkedHashMap<>();
    private final Map<UseCaseId, RevisionToken> headByIdentity = new LinkedHashMap<>();
    private final Map<UseCaseId, String> titleLanguageByIdentity = new LinkedHashMap<>();
    private final Map<UseCaseId, String> goalLanguageByIdentity = new LinkedHashMap<>();
    private final Map<UseCaseId, String> scopeLanguageByIdentity = new LinkedHashMap<>();
    private final Map<UseCaseId, String> triggerLanguageByIdentity = new LinkedHashMap<>();
    private final Map<UseCaseId, String> preconditionLanguageByIdentity = new LinkedHashMap<>();
    private final Map<UseCaseId, String> postconditionLanguageByIdentity = new LinkedHashMap<>();
    private final Map<UseCaseId, Map<Integer, String>> stepTextLanguageByIdentity = new LinkedHashMap<>();
    private final Map<UseCaseId, Map<Integer, String>> extensionTextLanguageByIdentity = new LinkedHashMap<>();
    private Integer lastStableExtensionPrefixLength;

    /**
     * The {@code stableExtensionPrefixLength} {@link UseCaseService} passed on the most recent
     * {@link #compareAndUpdate} call - lets a test assert on the service's own safe-to-preserve
     * computation without needing the real out-adapter's RDF store. {@code null} before any
     * {@code compareAndUpdate} call.
     */
    Integer lastStableExtensionPrefixLength() {
        return lastStableExtensionPrefixLength;
    }

    @Override
    public void create(ProjectId projectId, UseCase useCase, String language) {
        Map<UseCaseId, UseCase> useCases = byProject.computeIfAbsent(projectId,
                k -> new LinkedHashMap<>());
        if (useCases.containsKey(useCase.id())) {
            throw new ResourceAlreadyExistsException(projectId, useCase.id().value());
        }
        boolean codeTaken = useCases.values().stream().anyMatch(uc -> uc.code().equals(useCase.code()));
        if (codeTaken) {
            throw new DuplicateUseCaseCodeException(projectId, useCase.code());
        }
        useCases.put(useCase.id(), useCase);
        headByIdentity.put(useCase.id(), new RevisionToken(UUID.randomUUID().toString()));
        titleLanguageByIdentity.put(useCase.id(), language);
        goalLanguageByIdentity.put(useCase.id(), language);
        scopeLanguageByIdentity.put(useCase.id(), language);
        triggerLanguageByIdentity.put(useCase.id(), language);
        preconditionLanguageByIdentity.put(useCase.id(), language);
        postconditionLanguageByIdentity.put(useCase.id(), language);
        Map<Integer, String> stepLanguages = new LinkedHashMap<>();
        useCase.steps().forEach(step -> stepLanguages.put(step.position(), language));
        stepTextLanguageByIdentity.put(useCase.id(), stepLanguages);
        Map<Integer, String> extensionLanguages = new LinkedHashMap<>();
        for (int position = 1; position <= useCase.extensions().size(); position++) {
            extensionLanguages.put(position, language);
        }
        extensionTextLanguageByIdentity.put(useCase.id(), extensionLanguages);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, UseCase updated,
            String titleLanguage, String goalLanguage, String scopeLanguage, String triggerLanguage,
            String preconditionLanguage, String postconditionLanguage,
            Map<Integer, String> stepTextLanguageByPosition, Map<Integer, String> extensionTextLanguageByPosition,
            String defaultLanguage, int stableExtensionPrefixLength) {
        // This fake stores a single title/goal/scope/trigger/precondition/postcondition/step-text/
        // extension-text value per identity (no multi-valued literals), so there is nothing for it
        // to sweep - defaultLanguage only matters to the real out-adapter's language-variant
        // preservation, exercised by KognioRdfUseCaseRepositoryMultilingualTest instead.
        // stableExtensionPrefixLength is recorded, not acted on, so UseCaseServiceTest can assert on
        // the service's own computation of it (see lastStableExtensionPrefixLength()).
        lastStableExtensionPrefixLength = stableExtensionPrefixLength;
        Map<UseCaseId, UseCase> useCases = byProject.getOrDefault(projectId, Map.of());
        UseCase current = useCases.get(updated.id());
        if (current == null) {
            throw new UseCaseNotFoundException(projectId, updated.code());
        }
        if (!Objects.equals(headByIdentity.get(updated.id()), expectedHead)) {
            throw new UseCaseConcurrentlyModifiedException(projectId, updated.code());
        }
        useCases.put(updated.id(), updated);
        headByIdentity.put(updated.id(), new RevisionToken(UUID.randomUUID().toString()));
        titleLanguageByIdentity.put(updated.id(), titleLanguage);
        goalLanguageByIdentity.put(updated.id(), goalLanguage);
        scopeLanguageByIdentity.put(updated.id(), scopeLanguage);
        triggerLanguageByIdentity.put(updated.id(), triggerLanguage);
        preconditionLanguageByIdentity.put(updated.id(), preconditionLanguage);
        postconditionLanguageByIdentity.put(updated.id(), postconditionLanguage);
        stepTextLanguageByIdentity.put(updated.id(), new LinkedHashMap<>(stepTextLanguageByPosition));
        extensionTextLanguageByIdentity.put(updated.id(), new LinkedHashMap<>(extensionTextLanguageByPosition));
    }

    @Override
    public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code, String displayLocale) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(uc -> uc.code().equals(code))
                .findFirst();
    }

    @Override
    public Optional<CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code) {
        return findByCode(projectId, code, null)
                .map(useCase -> new CurrentUseCase(useCase, headByIdentity.get(useCase.id()),
                        titleLanguageByIdentity.get(useCase.id()), goalLanguageByIdentity.get(useCase.id()),
                        scopeLanguageByIdentity.get(useCase.id()), triggerLanguageByIdentity.get(useCase.id()),
                        preconditionLanguageByIdentity.get(useCase.id()),
                        postconditionLanguageByIdentity.get(useCase.id()),
                        stepTextLanguageByIdentity.getOrDefault(useCase.id(), Map.of()),
                        extensionTextLanguageByIdentity.getOrDefault(useCase.id(), Map.of())));
    }

    @Override
    public List<UseCase> findAll(ProjectId projectId, String displayLocale) {
        // Nothing multi-valued to select a language variant from in this plain in-memory fake -
        // displayLocale is accepted and ignored.
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }
}
