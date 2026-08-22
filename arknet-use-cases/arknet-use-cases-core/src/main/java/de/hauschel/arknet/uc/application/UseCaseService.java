// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.LinkConstraint;
import de.hauschel.arknet.uc.application.port.in.LinkTerm;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase;
import de.hauschel.arknet.uc.application.port.out.ActorLookup;
import de.hauschel.arknet.uc.application.port.out.ConstraintLookup;
import de.hauschel.arknet.uc.application.port.out.RequirementLookup;
import de.hauschel.arknet.uc.application.port.out.TermLookup;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.ConstraintRef;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.StepTextPatch;
import de.hauschel.arknet.uc.domain.TermRef;
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
 * multi-writer concern (ADR-001). {@link #update}/{@link #linkTerm}/{@link #linkConstraint} share
 * that same concern: read-modify-write round trips retry via
 * {@link UseCaseRepository#compareAndUpdate} whenever a concurrent writer commits in between -
 * see {@link #updateWithOptimisticRetry} (mirrors {@code RequirementService}).</p>
 *
 * <p><strong>Correction.</strong> {@link #update} lets a caller correct a use case's
 * goal-level fields and/or an individual step's text and/or realises references after the fact,
 * without the delete-and-recreate round trip through {@code uc_add} that would risk a new
 * {@link UseCaseCode} and orphaned {@code realises}/{@code extensions} references. Every scalar
 * argument is optional ({@code null} leaves it unchanged); {@code stepTextPatches} corrects only
 * the {@code text} of existing main-flow steps by position, never their {@code realises}
 * references, while the separate, independent {@code stepRealisesPatches} corrects only a named
 * step's {@code realises} set - replacing it wholesale, with an empty list explicitly clearing it
 * (issue #255). Neither mechanism adds, removes or reorders steps. {@code primaryActor} and
 * {@code supportingActors} are correctable too (issue #343), by name and through the very same
 * {@link ActorLookup} {@link #add} resolves against - {@code primaryActor} replace-or-leave,
 * {@code supportingActors} a wholesale replace whose empty list clears them; full step-list
 * restructuring stays out of this port's scope - see {@link UpdateUseCase}.
 * Linking a glossary term or a constraint is idempotent, independent of
 * {@link #update}, and mirrors {@code RequirementService#linkTerm}/{@code #linkConstraint}
 * exactly (issue #329) - {@link #linkConstraint} resolves the human-typed constraint code via the
 * constructor-injected {@link ConstraintLookup} cross-BC port rather than a same-module
 * repository, since unlike the sibling requirements bounded context, {@code Constraint} does not
 * live in this bounded context.</p>
 */
public class UseCaseService implements AddUseCase, GetUseCase, ListUseCases, UpdateUseCase, LinkTerm, LinkConstraint {

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
    private final TermLookup termLookup;
    private final ConstraintLookup constraintLookup;

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
     * @param termLookup        resolves a human-typed glossary term code to its opaque identity,
     *                          for {@link #linkTerm} (must not be {@code null})
     * @param constraintLookup  resolves a human-typed constraint code to its opaque identity, for
     *                          {@link #linkConstraint} - a cross-BC lookup port rather than a
     *                          same-module repository, since {@code Constraint} lives in the
     *                          neighbouring requirements bounded context (must not be
     *                          {@code null})
     */
    public UseCaseService(UseCaseRepository repository, ResourceIdFactory resourceIdFactory,
            RequirementLookup requirementLookup, ActorLookup actorLookup, TermLookup termLookup,
            ConstraintLookup constraintLookup) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.requirementLookup = Objects.requireNonNull(requirementLookup, "requirementLookup");
        this.actorLookup = Objects.requireNonNull(actorLookup, "actorLookup");
        this.termLookup = Objects.requireNonNull(termLookup, "termLookup");
        this.constraintLookup = Objects.requireNonNull(constraintLookup, "constraintLookup");
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
                    command.extensions(), List.of(), List.of());
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
    public List<UseCase> list(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId, displayLocale);
    }

    @Override
    public Optional<UseCase> get(ProjectId projectId, UseCaseCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code, displayLocale);
    }

    @Override
    public UseCase update(ProjectId projectId, UseCaseCode code, String title, String goal, String scope,
            String trigger, String primaryActor, List<String> supportingActors, String precondition,
            String postcondition, List<String> extensions, List<StepTextPatch> stepTextPatches,
            List<UpdateUseCase.StepRealisesPatch> stepRealisesPatches, String language, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // Reference resolution happens once, before the retry, mirroring add(): an unresolvable
        // requirement or actor reference must fail immediately and is not a code-collision race to
        // retry on.
        Map<Integer, List<RequirementRef>> realisesByPosition = stepRealisesPatches == null
                ? null : toRealisesByPosition(projectId, stepRealisesPatches);
        // Null means "leave it" for both actor arguments; the difference is that an empty
        // supportingActors list is a legal, explicit clear, while primaryActor has no clear at all
        // (a use case always has exactly one) - see UpdateUseCase (issue #343).
        ActorRef newPrimaryActor = primaryActor == null
                ? null : new ActorRef(actorLookup.resolveByName(projectId, primaryActor));
        List<ActorRef> newSupportingActors = supportingActors == null
                ? null
                : supportingActors.stream()
                        .map(name -> new ActorRef(actorLookup.resolveByName(projectId, name)))
                        .toList();
        // Which step positions this call itself patches, and whether it touches extensions at
        // all (issue #271): the signal updateWithOptimisticRetry resolves a fresh language
        // against, instead of comparing the patched/replaced text to what is already stored - a
        // caller correcting a typo back to the project's already-current wording is still an
        // explicit write, not a no-op. `extensions` is a wholesale replace (not a per-position
        // patch), so a non-null `extensions` touches every position in the replacement list.
        Set<Integer> touchedStepPositions = stepTextPatches == null
                ? Set.of()
                : stepTextPatches.stream().map(StepTextPatch::position).collect(Collectors.toUnmodifiableSet());
        boolean extensionsTouched = extensions != null;
        return updateWithOptimisticRetry(projectId, code, language, defaultLanguage,
                title != null, goal != null, scope != null, trigger != null,
                precondition != null, postcondition != null, touchedStepPositions, extensionsTouched, current -> {
            UseCase base = new UseCase(
                    current.id(), current.code(),
                    title != null ? title : current.title(),
                    goal != null ? goal : current.goal(),
                    scope != null ? scope : current.scope(),
                    trigger != null ? trigger : current.trigger(),
                    newPrimaryActor != null ? newPrimaryActor : current.primaryActor(),
                    newSupportingActors != null ? newSupportingActors : current.supportingActors(),
                    precondition != null ? precondition : current.precondition(),
                    postcondition != null ? postcondition : current.postcondition(),
                    current.steps(),
                    extensions != null ? List.copyOf(extensions) : current.extensions(),
                    current.usesTerms(), current.constrainedBy());
            base = stepTextPatches != null ? base.withStepTextPatches(projectId, stepTextPatches) : base;
            return realisesByPosition != null ? base.withStepRealisesPatches(projectId, realisesByPosition) : base;
        });
    }

    @Override
    public UseCase linkTerm(ProjectId projectId, UseCaseCode code, String termCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(termCode, "termCode");
        // Resolution does not depend on the use case's current state, so it happens once, outside
        // the retry loop below - a lookup failure must propagate immediately and leave the use
        // case untouched, exactly as RequirementService#linkTerm.
        TermRef term = new TermRef(termLookup.resolveByCode(projectId, termCode));
        // linkTerm() touches no language-tagged field - same null/null/all-untouched call shape as
        // update() would use for a call that names nothing.
        return updateWithOptimisticRetry(projectId, code, null, null, false, false, false, false, false, false,
                Set.of(), false, current -> {
            if (current.usesTerms().contains(term)) {
                return current;
            }
            List<TermRef> linked = new ArrayList<>(current.usesTerms());
            linked.add(term);
            return new UseCase(current.id(), current.code(), current.title(), current.goal(), current.scope(),
                    current.trigger(), current.primaryActor(), current.supportingActors(),
                    current.precondition(), current.postcondition(), current.steps(), current.extensions(),
                    linked, current.constrainedBy());
        });
    }

    @Override
    public UseCase linkConstraint(ProjectId projectId, UseCaseCode code, String constraintCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(constraintCode, "constraintCode");
        // Resolution does not depend on the use case's current state, so it happens once, outside
        // the retry loop below - mirrors linkTerm() exactly, except the lookup crosses into the
        // neighbouring requirements bounded context via ConstraintLookup rather than a
        // same-module repository (see the class-level note).
        ConstraintRef ref = new ConstraintRef(constraintLookup.resolveByCode(projectId, constraintCode));
        return updateWithOptimisticRetry(projectId, code, null, null, false, false, false, false, false, false,
                Set.of(), false, current -> {
            if (current.constrainedBy().contains(ref)) {
                return current;
            }
            List<ConstraintRef> linked = new ArrayList<>(current.constrainedBy());
            linked.add(ref);
            return new UseCase(current.id(), current.code(), current.title(), current.goal(), current.scope(),
                    current.trigger(), current.primaryActor(), current.supportingActors(),
                    current.precondition(), current.postcondition(), current.steps(), current.extensions(),
                    current.usesTerms(), linked);
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
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if {@code titleTouched},
     *                                                {@code goalTouched}, {@code scopeTouched},
     *                                                {@code triggerTouched},
     *                                                {@code preconditionTouched},
     *                                                {@code postconditionTouched},
     *                                                {@code touchedStepPositions} or
     *                                                {@code extensionsTouched} marks a field, step
     *                                                or extension as this call's own and neither
     *                                                {@code language} nor {@code defaultLanguage}
     *                                                is given
     */
    private UseCase updateWithOptimisticRetry(ProjectId projectId, UseCaseCode code, String language,
            String defaultLanguage, boolean titleTouched, boolean goalTouched, boolean scopeTouched,
            boolean triggerTouched, boolean preconditionTouched, boolean postconditionTouched,
            Set<Integer> touchedStepPositions, boolean extensionsTouched, UnaryOperator<UseCase> mutation) {
        UseCaseConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(projectId, code)
                    .orElseThrow(() -> new UseCaseNotFoundException(projectId, code));
            UseCase updated = mutation.apply(current.value());
            // title/goal/scope/trigger/precondition/postcondition/each step's text/each
            // extension's text each get their own language: a field, step or extension this call
            // itself did not name (the boolean/Set parameters above - see update()) round-trips
            // under the exact tag it was read under (a scoped no-op), never under `language`/
            // `defaultLanguage`. A named field/step/extension only resolves a fresh write language
            // when the caller also supplied `language` explicitly or the text it supplies actually
            // differs from what is stored (issue #271, mirrors RequirementService's identical fix,
            // and its regression - a named field whose text happens to equal what is stored and
            // whose caller did not name a language is still a no-op, exactly like an unnamed one,
            // so that resending a field's already-current text as part of a full-state round trip
            // never demands a `defaultLanguage` the project may not have; see
            // resolveTouchedLanguage/resolveTouchedPositionLanguage below). Resolving here, lazily,
            // rather than eagerly in update(), means a malformed/missing language argument only
            // ever throws when this call actually touches a language-tagged field, step or
            // extension (issue #258).
            String titleLanguage = resolveTouchedLanguage(titleTouched, current.value().title(), updated.title(),
                    current.titleLanguage(), language, defaultLanguage);
            String goalLanguage = resolveTouchedLanguage(goalTouched, current.value().goal(), updated.goal(),
                    current.goalLanguage(), language, defaultLanguage);
            String scopeLanguage = resolveTouchedLanguage(scopeTouched, current.value().scope(), updated.scope(),
                    current.scopeLanguage(), language, defaultLanguage);
            String triggerLanguage = resolveTouchedLanguage(triggerTouched, current.value().trigger(),
                    updated.trigger(), current.triggerLanguage(), language, defaultLanguage);
            String preconditionLanguage = resolveTouchedLanguage(preconditionTouched,
                    current.value().precondition(), updated.precondition(), current.preconditionLanguage(),
                    language, defaultLanguage);
            String postconditionLanguage = resolveTouchedLanguage(postconditionTouched,
                    current.value().postcondition(), updated.postcondition(), current.postconditionLanguage(),
                    language, defaultLanguage);
            Map<Integer, String> currentStepTextByPosition = new LinkedHashMap<>();
            for (Step currentStep : current.value().steps()) {
                currentStepTextByPosition.put(currentStep.position(), currentStep.text());
            }
            Map<Integer, String> stepTextLanguageByPosition = new LinkedHashMap<>();
            for (Step updatedStep : updated.steps()) {
                String stepLanguage = resolveTouchedLanguage(touchedStepPositions.contains(updatedStep.position()),
                        currentStepTextByPosition.get(updatedStep.position()), updatedStep.text(),
                        current.stepTextLanguageByPosition().get(updatedStep.position()), language, defaultLanguage);
                stepTextLanguageByPosition.put(updatedStep.position(), stepLanguage);
            }
            Map<Integer, String> extensionTextLanguageByPosition = new LinkedHashMap<>();
            List<String> currentExtensions = current.value().extensions();
            List<String> updatedExtensions = updated.extensions();
            for (int i = 0; i < updatedExtensions.size(); i++) {
                int position = i + 1;
                boolean isNewExtensionPosition = i >= currentExtensions.size();
                String extensionLanguage = resolveTouchedPositionLanguage(isNewExtensionPosition, extensionsTouched,
                        isNewExtensionPosition ? null : currentExtensions.get(i), updatedExtensions.get(i),
                        current.extensionTextLanguageByPosition().get(position), language, defaultLanguage);
                extensionTextLanguageByPosition.put(position, extensionLanguage);
            }
            // A true no-op needs both content and language to already match what is stored:
            // content-only equality (the pre-#271 check) missed a named field/step/extension whose
            // caller supplied a different language for text that happens to already match - see
            // the block comment above.
            if (updated.equals(current.value())
                    && Objects.equals(titleLanguage, current.titleLanguage())
                    && Objects.equals(goalLanguage, current.goalLanguage())
                    && Objects.equals(scopeLanguage, current.scopeLanguage())
                    && Objects.equals(triggerLanguage, current.triggerLanguage())
                    && Objects.equals(preconditionLanguage, current.preconditionLanguage())
                    && Objects.equals(postconditionLanguage, current.postconditionLanguage())
                    && stepTextLanguageByPosition.equals(current.stepTextLanguageByPosition())
                    && extensionTextLanguageByPosition.equals(current.extensionTextLanguageByPosition())) {
                return current.value();
            }
            // A same-length extensions replace can only ever edit content in place - the model has
            // no separate move/reorder operation, only a wholesale list replace - so every position
            // keeps its identity regardless of how many of them changed text (issue #254/PR #267
            // review: a prefix scan that stops at the first content mismatch wrongly starves every
            // position after it, even ones a multi-position edit left byte-for-byte untouched).
            // Only a length change is real evidence of an insert/remove: positions beyond the
            // longest leading prefix the old and new lists still share no longer refer to "the
            // same" extension on either side, so preservation there must be suspended - see
            // compareAndUpdate's stableExtensionPrefixLength javadoc for why that distinction
            // matters. A same-length reorder (a swap) is not distinguishable from an in-place edit
            // by content alone and is accepted as a residual, undetected case - it is not a
            // supported operation on this list today.
            int stableExtensionPrefixLength;
            if (currentExtensions.size() == updatedExtensions.size()) {
                stableExtensionPrefixLength = updatedExtensions.size();
            } else {
                int commonExtensionPrefixLength = 0;
                while (commonExtensionPrefixLength < currentExtensions.size()
                        && commonExtensionPrefixLength < updatedExtensions.size()
                        && currentExtensions.get(commonExtensionPrefixLength)
                                .equals(updatedExtensions.get(commonExtensionPrefixLength))) {
                    commonExtensionPrefixLength++;
                }
                stableExtensionPrefixLength = commonExtensionPrefixLength;
            }
            try {
                repository.compareAndUpdate(projectId, current.head(), updated,
                        titleLanguage, goalLanguage, scopeLanguage, triggerLanguage,
                        preconditionLanguage, postconditionLanguage,
                        stepTextLanguageByPosition, extensionTextLanguageByPosition, defaultLanguage,
                        stableExtensionPrefixLength);
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
     * The BCP-47 language tag a single scalar field ({@code title}/{@code goal}/{@code scope}/
     * {@code trigger}/{@code precondition}/{@code postcondition}/a step's text) is written under:
     * freshly resolved via {@link LanguageTag#resolveWriteLanguage} when {@code touched} and
     * either the caller named {@code language} explicitly or {@code updatedText} actually differs
     * from {@code currentText}; otherwise {@code currentLanguage} unchanged (a scoped no-op, not a
     * retag). A field named by the caller but resent with its own already-current text and no
     * {@code language} argument is therefore still a no-op (issue #271's regression - naming a
     * field alone used to be enough to force a resolution that a project with no {@code
     * defaultLanguage} could not satisfy). Mirrors {@code RequirementService}'s identical helper.
     */
    private static String resolveTouchedLanguage(boolean touched, String currentText, String updatedText,
            String currentLanguage, String language, String defaultLanguage) {
        boolean languageTouched = touched && (language != null || !Objects.equals(updatedText, currentText));
        return languageTouched
                ? LanguageTag.resolveWriteLanguage(language, defaultLanguage)
                : currentLanguage;
    }

    /**
     * {@link #resolveTouchedLanguage} extended with {@code isNewPosition}: a position with no
     * prior text/tag at all (an extension beyond the current, shorter list) always resolves
     * fresh, regardless of {@code touched} - there is nothing to compare its text against or fall
     * back to.
     */
    private static String resolveTouchedPositionLanguage(boolean isNewPosition, boolean touched,
            String currentText, String updatedText, String currentLanguage, String language,
            String defaultLanguage) {
        if (isNewPosition) {
            return LanguageTag.resolveWriteLanguage(language, defaultLanguage);
        }
        return resolveTouchedLanguage(touched, currentText, updatedText, currentLanguage, language, defaultLanguage);
    }

    /**
     * Derives the next free business code in {@code projectId}: the highest running number
     * currently in use, plus one (starting at 1).
     */
    private UseCaseCode nextCode(ProjectId projectId) {
        // Only each use case's UseCaseCode is read here, never a text field, so this call has no
        // need for a display language override - null uses the repository's own configured
        // preference, which has no bearing on this method's result either way.
        int next = repository.findAll(projectId, null).stream()
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
