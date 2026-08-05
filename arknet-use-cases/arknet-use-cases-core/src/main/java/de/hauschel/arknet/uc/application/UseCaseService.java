// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase;
import de.hauschel.arknet.uc.application.port.out.ActorLookup;
import de.hauschel.arknet.uc.application.port.out.RequirementLookup;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.StepTextPatch;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseConcurrentlyModifiedException;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Application service implementing the use-case use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link UseCaseRepository}
 * driven port. The component is wired as a plain object (constructor injection) by
 * the composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link UseCaseId}) is opaque and minted once per use
 * case via {@link ResourceIdFactory}; it never changes. The human-readable business code
 * ({@link UseCaseCode}, {@code UCn}) is assigned independently, where {@code n} is one above
 * the highest running number currently used in the target project (numbering is independent
 * per project, starting at 1).</p>
 *
 * <p><strong>Reference resolution.</strong> {@code NewUseCase}'s actor/requirement
 * fields are raw human-typed strings, not domain refs - resolving them to the referenced
 * resources' opaque identities is this service's job, via the driven {@link ActorLookup}/
 * {@link RequirementLookup} ports, once per {@link #add}, before the real {@link UseCase} and its
 * {@link Step}s are constructed. An unknown or ambiguous reference propagates as a didactic
 * runtime exception from the lookup, rejecting the write; nothing is persisted.</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} recomputes its next code against a
 * fresh read whenever a concurrent {@code uc_add} claims the same {@code UCn} first, via
 * {@link CodeAssignment#createRetryingOnCodeCollision}; the race is invisible to a well-formed
 * caller. Parallel sessions of one user against one local store are the normal case, not a remote/
 * multi-writer concern (ADR-001). {@link #update} shares that same concern: read-modify-write
 * round trips retry via {@link UseCaseRepository#compareAndUpdate} whenever a concurrent writer
 * commits in between - see {@link #updateWithOptimisticRetry} (mirrors
 * {@code RequirementService}).</p>
 *
 * <p><strong>Correction.</strong> {@link #update} lets a caller correct a use case's
 * goal-level fields and/or an individual step's text and/or realises references after the fact,
 * without the delete-and-recreate round trip through {@code uc_add} that would risk a new
 * {@link UseCaseCode} and orphaned {@code realises}/{@code extensions} references. Every scalar
 * argument is optional ({@code null} leaves it unchanged); {@code stepTextPatches} corrects only
 * the {@code text} of existing main-flow steps by position, never their {@code realises}
 * references, while the separate, independent {@code stepRealisesPatches} corrects only a named
 * step's {@code realises} set - replacing it wholesale, with an empty list explicitly clearing it
 * (issue #255). Neither mechanism adds, removes or reorders steps. {@code primaryActor},
 * {@code supportingActors} and full step-list restructuring stay out of this port's scope - see
 * {@link UpdateUseCase}.</p>
 */
public class UseCaseService implements AddUseCase, GetUseCase, ListUseCases, UpdateUseCase {

    private static final String CODE_PREFIX = "UC";

    /**
     * Bound on {@link #updateWithOptimisticRetry}'s retry loop, mirroring
     * {@code RequirementService#MAX_RETRY_ATTEMPTS}: a pathological, sustained storm
     * of concurrent writers against the very same use case fails loudly instead of looping
     * forever, rather than guarding against a race expected to resolve within a single retry.
     */
    static final int MAX_RETRY_ATTEMPTS = 20;

    private final UseCaseRepository repository;
    private final ResourceIdFactory resourceIdFactory;
    private final RequirementLookup requirementLookup;
    private final ActorLookup actorLookup;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added use case (must not be
     *                          {@code null})
     * @param requirementLookup resolves a human-typed requirement code to its opaque identity
     *                          (must not be {@code null})
     * @param actorLookup       resolves a human-typed actor name to its opaque identity (must
     *                          not be {@code null})
     */
    public UseCaseService(UseCaseRepository repository, ResourceIdFactory resourceIdFactory,
            RequirementLookup requirementLookup, ActorLookup actorLookup) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.requirementLookup = Objects.requireNonNull(requirementLookup, "requirementLookup");
        this.actorLookup = Objects.requireNonNull(actorLookup, "actorLookup");
    }

    @Override
    public UseCase add(ProjectId projectId, NewUseCase command, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry. Reference
        // resolution likewise happens once, before the retry: an unknown/ambiguous actor or
        // requirement must fail immediately and is not a code collision to retry on. Only the
        // business code is recomputed when a concurrent uc_add claims the same candidate first -
        // see CodeAssignment for why that race exists.
        UseCaseId id = new UseCaseId(resourceIdFactory.newId());
        // Resolved once, outside the retry, same as RequirementService#add: the language a fresh
        // use case is written under does not depend on which code candidate ultimately wins, and a
        // missing default must reject the call before any reference is even resolved (issue #258).
        String language = LanguageTag.resolveWriteLanguage(command.language(), defaultLanguage);
        ActorRef primaryActor = new ActorRef(actorLookup.resolveByName(projectId, command.primaryActor()));
        List<ActorRef> supportingActors = command.supportingActors() == null
                ? List.of()
                : command.supportingActors().stream()
                        .map(name -> new ActorRef(actorLookup.resolveByName(projectId, name)))
                        .toList();
        List<Step> steps = command.steps() == null
                ? List.of()
                : command.steps().stream()
                        .map(step -> toStep(projectId, step))
                        .toList();
        return CodeAssignment.createRetryingOnCodeCollision(DuplicateUseCaseCodeException.class, () -> {
            UseCaseCode code = nextCode(projectId);
            UseCase useCase = new UseCase(id, code, command.title(), command.goal(), command.scope(),
                    command.trigger(), primaryActor, supportingActors,
                    command.precondition(), command.postcondition(), steps,
                    command.extensions());
            repository.create(projectId, useCase, language);
            return useCase;
        });
    }

    private Step toStep(ProjectId projectId, NewStep step) {
        List<RequirementRef> realises = step.realises() == null
                ? List.of()
                : step.realises().stream()
                        .map(code -> new RequirementRef(requirementLookup.resolveByCode(projectId, code)))
                        .toList();
        return new Step(step.position(), step.text(), realises);
    }

    @Override
    public List<UseCase> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId);
    }

    @Override
    public Optional<UseCase> get(ProjectId projectId, UseCaseCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code, displayLocale);
    }

    @Override
    public UseCase update(ProjectId projectId, UseCaseCode code, String title, String goal, String scope,
            String trigger, String precondition, String postcondition, List<String> extensions,
            List<StepTextPatch> stepTextPatches, List<UpdateUseCase.StepRealisesPatch> stepRealisesPatches,
            String language, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // Reference resolution happens once, before the retry, mirroring add(): an unresolvable
        // requirement code must fail immediately and is not a code-collision race to retry on.
        Map<Integer, List<RequirementRef>> realisesByPosition = stepRealisesPatches == null
                ? null : toRealisesByPosition(projectId, stepRealisesPatches);
        return updateWithOptimisticRetry(projectId, code, language, defaultLanguage, current -> {
            UseCase base = new UseCase(
                    current.id(), current.code(),
                    title != null ? title : current.title(),
                    goal != null ? goal : current.goal(),
                    scope != null ? scope : current.scope(),
                    trigger != null ? trigger : current.trigger(),
                    current.primaryActor(), current.supportingActors(),
                    precondition != null ? precondition : current.precondition(),
                    postcondition != null ? postcondition : current.postcondition(),
                    current.steps(),
                    extensions != null ? List.copyOf(extensions) : current.extensions());
            base = stepTextPatches != null ? base.withStepTextPatches(projectId, stepTextPatches) : base;
            return realisesByPosition != null ? base.withStepRealisesPatches(projectId, realisesByPosition) : base;
        });
    }

    private Map<Integer, List<RequirementRef>> toRealisesByPosition(
            ProjectId projectId, List<UpdateUseCase.StepRealisesPatch> patches) {
        Map<Integer, List<RequirementRef>> byPosition = new LinkedHashMap<>();
        for (UpdateUseCase.StepRealisesPatch patch : patches) {
            List<RequirementRef> resolved = patch.realises() == null
                    ? List.of()
                    : patch.realises().stream()
                            .map(code -> new RequirementRef(requirementLookup.resolveByCode(projectId, code)))
                            .toList();
            byPosition.put(patch.position(), resolved);
        }
        return byPosition;
    }

    /**
     * Read-modify-write helper backing {@link #update}: reads the current use case and its
     * concurrency token together via {@link UseCaseRepository#findCurrentByCode}, derives the
     * next state via {@code mutation}, and writes it back via
     * {@link UseCaseRepository#compareAndUpdate} - retrying with a fresh read whenever a
     * concurrent writer commits a change in between. Mirrors
     * {@code RequirementService#updateWithOptimisticRetry}.
     *
     * <p>{@code mutation} returning its input unchanged (by {@link Object#equals}) is treated as
     * a no-op and skips the write entirely.</p>
     *
     * @throws UseCaseNotFoundException              if no use case with {@code code} exists
     * @throws UseCaseConcurrentlyModifiedException if the write keeps losing the race across
     *                                                every retry attempt
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if {@code mutation}
     *                                                actually changes {@code title}, {@code goal},
     *                                                {@code scope}, {@code trigger},
     *                                                {@code precondition}, {@code postcondition},
     *                                                any step's {@code text} or any extension's
     *                                                text and neither {@code language} nor
     *                                                {@code defaultLanguage} is given
     */
    private UseCase updateWithOptimisticRetry(ProjectId projectId, UseCaseCode code, String language,
            String defaultLanguage, UnaryOperator<UseCase> mutation) {
        UseCaseConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(projectId, code)
                    .orElseThrow(() -> new UseCaseNotFoundException(projectId, code));
            UseCase updated = mutation.apply(current.value());
            if (updated.equals(current.value())) {
                return current.value();
            }
            // title/goal/scope/trigger/precondition/postcondition/each step's text/each
            // extension's text each get their own language: a field, step or extension this
            // mutation left byte-for-byte unchanged must round-trip under the exact tag it was
            // read under (a scoped no-op), never under `language`/`defaultLanguage` - those only
            // ever apply to whatever this call is actually changing (mirrors RequirementService's
            // identical per-field distinction). Resolving here, lazily, rather than eagerly in
            // update(), means a malformed/missing language argument only ever throws when this
            // call is actually changing a language-tagged field, step or extension under it
            // (issue #258).
            String titleLanguage = updated.title().equals(current.value().title())
                    ? current.titleLanguage() : LanguageTag.resolveWriteLanguage(language, defaultLanguage);
            String goalLanguage = updated.goal().equals(current.value().goal())
                    ? current.goalLanguage() : LanguageTag.resolveWriteLanguage(language, defaultLanguage);
            String scopeLanguage = Objects.equals(updated.scope(), current.value().scope())
                    ? current.scopeLanguage() : LanguageTag.resolveWriteLanguage(language, defaultLanguage);
            String triggerLanguage = Objects.equals(updated.trigger(), current.value().trigger())
                    ? current.triggerLanguage() : LanguageTag.resolveWriteLanguage(language, defaultLanguage);
            String preconditionLanguage = Objects.equals(updated.precondition(), current.value().precondition())
                    ? current.preconditionLanguage() : LanguageTag.resolveWriteLanguage(language, defaultLanguage);
            String postconditionLanguage = Objects.equals(updated.postcondition(), current.value().postcondition())
                    ? current.postconditionLanguage() : LanguageTag.resolveWriteLanguage(language, defaultLanguage);
            Map<Integer, String> stepTextLanguageByPosition = new LinkedHashMap<>();
            List<Step> currentSteps = current.value().steps();
            List<Step> updatedSteps = updated.steps();
            for (int i = 0; i < updatedSteps.size(); i++) {
                Step updatedStep = updatedSteps.get(i);
                Step currentStep = currentSteps.get(i);
                String stepLanguage = updatedStep.text().equals(currentStep.text())
                        ? current.stepTextLanguageByPosition().get(updatedStep.position())
                        : LanguageTag.resolveWriteLanguage(language, defaultLanguage);
                stepTextLanguageByPosition.put(updatedStep.position(), stepLanguage);
            }
            Map<Integer, String> extensionTextLanguageByPosition = new LinkedHashMap<>();
            List<String> currentExtensions = current.value().extensions();
            List<String> updatedExtensions = updated.extensions();
            for (int i = 0; i < updatedExtensions.size(); i++) {
                int position = i + 1;
                boolean unchanged = i < currentExtensions.size()
                        && updatedExtensions.get(i).equals(currentExtensions.get(i));
                String extensionLanguage = unchanged
                        ? current.extensionTextLanguageByPosition().get(position)
                        : LanguageTag.resolveWriteLanguage(language, defaultLanguage);
                extensionTextLanguageByPosition.put(position, extensionLanguage);
            }
            // A position is only a stable identity across this call if it survives inside the
            // longest leading prefix the old and new lists still share - beyond that prefix, a
            // wholesale extensions replace may have inserted, removed or reordered items, and the
            // position numbering no longer refers to "the same" extension on both sides. More than
            // one position diverging past that prefix (on either side) is the signature of such a
            // restructure, as opposed to a single in-place edit (a translation of the trailing
            // position, or a plain tail append/truncate) - see compareAndUpdate's
            // extensionsRestructured javadoc for why that distinction matters.
            int commonExtensionPrefixLength = 0;
            while (commonExtensionPrefixLength < currentExtensions.size()
                    && commonExtensionPrefixLength < updatedExtensions.size()
                    && currentExtensions.get(commonExtensionPrefixLength)
                            .equals(updatedExtensions.get(commonExtensionPrefixLength))) {
                commonExtensionPrefixLength++;
            }
            boolean extensionsRestructured =
                    currentExtensions.size() - commonExtensionPrefixLength > 1
                            || updatedExtensions.size() - commonExtensionPrefixLength > 1;
            try {
                repository.compareAndUpdate(projectId, current.head(), updated,
                        titleLanguage, goalLanguage, scopeLanguage, triggerLanguage,
                        preconditionLanguage, postconditionLanguage,
                        stepTextLanguageByPosition, extensionTextLanguageByPosition, defaultLanguage,
                        extensionsRestructured);
                return updated;
            } catch (UseCaseConcurrentlyModifiedException e) {
                // A concurrent writer replaced the use case between our read and our write -
                // retry against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * Derives the next free business code in {@code projectId}: the highest running number
     * currently in use, plus one (starting at 1).
     */
    private UseCaseCode nextCode(ProjectId projectId) {
        int next = repository.findAll(projectId).stream()
                .mapToInt(uc -> runningNumber(uc.code()))
                .max()
                .orElse(0) + 1;
        return new UseCaseCode(CODE_PREFIX + next);
    }

    /** Parses the running number from a code such as {@code UC7} (0 if not parseable). */
    private static int runningNumber(UseCaseCode code) {
        String value = code.value();
        int i = 0;
        while (i < value.length() && !Character.isDigit(value.charAt(i))) {
            i++;
        }
        if (i >= value.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
