// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.RemovedPositions;
import de.hauschel.arknet.uc.domain.StepPositionNotFoundException;
import de.hauschel.arknet.uc.domain.StepTextPatch;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseConcurrentlyModifiedException;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Driving port: correct the goal-level fields and/or individual step wordings of an
 * already-created use case.
 *
 * <p>Backs the tool {@code uc_update}, mirroring {@code UpdateRequirement}'s shape
 * in the sibling requirements bounded context. Until this port existed, fixing a typo or an
 * outdated label in a use case's {@code goal}/{@code trigger}/{@code precondition}/
 * {@code postcondition} or in a single step's text meant deleting and recreating the whole use
 * case via {@code uc_add} - risking a new {@link UseCaseCode}, orphaned {@code realises}/
 * {@code extensions} references, and renumbered steps. Every field of the
 * {@link UseCaseCorrection} this port takes is optional: {@code null} leaves that field
 * unchanged, so a caller can correct only what actually needs correcting.</p>
 *
 * <p><strong>One correction object rather than a parameter list.</strong> The correction travels
 * as a single {@link UseCaseCorrection}, built through {@link UseCaseCorrection#builder()},
 * exactly as {@link AddUseCase} takes one {@code NewUseCase}. A flat parameter list of a dozen
 * mostly-{@code null} arguments - several of them adjacent and of the same type - put every
 * caller one silent transposition away from writing the wrong field, and grew that risk with
 * each new correctable field; the builder names the fields a call actually sets and leaves the
 * rest alone.</p>
 *
 * <p><strong>Step corrections are four independent, narrowly-scoped mechanisms (append and remove
 * added by kogn-io/arknet#513, mirroring {@code adr_update}'s {@code removeConsequencePositions}).
 * </strong> {@code stepTextPatches} lets a caller fix the wording of one or more existing main-flow
 * steps by {@link de.hauschel.arknet.uc.domain.Step#position() position} - nothing else about a
 * step; it never touches a step's {@link de.hauschel.arknet.uc.domain.Step#realises() realises}
 * references. {@code stepRealisesPatches} is a separate, independent list that instead corrects
 * only a step's {@code realises} references, leaving its {@code text} untouched: a listed
 * position's value list replaces that step's entire {@code realises} set wholesale, with an
 * empty list the explicit, unambiguous signal to clear it - distinct from omitting the position
 * altogether, which leaves it unchanged (issue #255). {@code newMainSteps} appends new steps after
 * the existing ones, numbered continuing from the current highest position; {@code
 * removeMainStepPositions} takes one or more existing steps out by that same position and moves
 * the ones after it up. Position is a purely technical write-ordering detail throughout - a patch
 * naming a position with no matching step is rejected rather than silently ignored, in any list,
 * and naming the same position in both {@code stepTextPatches}/{@code stepRealisesPatches} and
 * {@code removeMainStepPositions} is rejected too - correcting what is being removed is a
 * contradiction, not a sequence. Removing every remaining step in one call is rejected as well: a
 * use case must have at least one step.</p>
 *
 * <p><strong>Role corrections (issue #343; repointed from actor to role by ADR-37/
 * kogn-io/arknet#405 Part C).</strong> {@code primaryRole} and
 * {@code supportingRoles} are corrected by business code, exactly as {@link AddUseCase} takes
 * them: raw, human-typed codes the application service resolves against the role register, never
 * opaque identities. What "unchanged" means differs between the two, because the model
 * constrains them differently. A use case has exactly one primary role ({@code sh:minCount 1}/
 * {@code sh:maxCount 1} on {@code arkreq:primaryRole}), so {@code primaryRole} is a plain
 * replace-or-leave field: {@code null} leaves it as it is, a code replaces it, and there is no
 * way to clear it. {@code supportingRoles} carries no such floor and is a wholesale replace
 * with the same tri-state as {@code stepRealisesPatches}: {@code null} leaves the existing list
 * untouched, a non-{@code null} list replaces it entirely, and an empty list is the explicit,
 * unambiguous signal to clear every supporting role. An unknown code is rejected
 * before anything is written, exactly as in {@link AddUseCase} - correcting a role reference
 * never has to go through delete-and-recreate, which would mint a new {@link UseCaseCode} and
 * break every inbound reference to the use case. Neither field carries a language-tagged
 * literal, so neither ever forces a write language to be resolved: a role-only correction
 * goes through in a project that has no {@code defaultLanguage} configured at all.</p>
 *
 * <p><strong>{@code usesTermCodes} carries the same tri-state as {@code supportingRoles}
 * (kogn-io/arknet#540, precedent {@code adr_update}'s reference lists).</strong> {@code null}
 * leaves the existing {@code arkreq:usesTerm} edges untouched, an empty list is the explicit,
 * unambiguous signal to remove every one of them, and a non-empty list replaces them wholesale.
 * {@code uc_link_term} remains the convenient, idempotent way to add a single edge without
 * restating the rest - this field is what lets a caller drop one, which {@code uc_link_term}
 * (add-only) cannot.</p>
 *
 * <p><strong>Explicitly out of scope.</strong> Reordering the main flow is still untouched by this
 * port - {@code newMainSteps}/{@code removeMainStepPositions} append and remove, they never move
 * a surviving step to a different position; a caller wanting a different order still needs
 * {@code uc_add} for a replacement use case, at the price named above: a new {@link UseCaseCode},
 * and no inbound reference carried over. {@code extensions} keeps its own, pre-existing
 * restructuring rule (a wholesale replace, issue #254/PR #267) rather than gaining this port's new
 * position-addressed append/remove mechanism.</p>
 *
 * <p><strong>Language.</strong> {@code title}, {@code goal}, {@code scope}, {@code trigger},
 * {@code precondition}, {@code postcondition}, each patched step's {@code text} and each entry of
 * {@code extensions} may each legally carry several language-tagged variants (SKOS-S14-style
 * {@code sh:uniqueLang}). {@link UseCaseCorrection#language()} names the BCP-47 tag every
 * language-tagged field <em>this call actually touches</em> is written in - whichever of
 * {@code title}/{@code goal}/{@code scope}/{@code trigger}/{@code precondition}/
 * {@code postcondition} is non-{@code null}, every step named in {@code stepTextPatches}, and, if
 * {@code extensions} is non-{@code null}, every entry of it - mirroring {@code UpdateTerm}'s
 * single shared {@code language} covering whichever of {@code prefLabel}/{@code definition} it
 * touches. A field (or step, or extension) this call does not touch keeps every language variant
 * it already had, untouched, exactly as before this parameter existed. A field/step/extension
 * that <em>is</em> being changed but ships no {@code language} falls back to
 * {@code defaultLanguage} (issue #258) rather than staying untagged - and if that
 * field/step/extension's existing value already carries an untagged literal, writing it under a
 * tag equal to {@code defaultLanguage} sweeps the untagged one away instead of preserving it as a
 * spurious "other" variant (see {@code UseCaseRepository#compareAndUpdate}'s
 * {@code defaultLanguage} parameter for the out-adapter side of this).</p>
 */
public interface UpdateUseCase {

    /**
     * Updates the use case identified by {@code code} within a project, leaving any
     * {@code null}/omitted field of {@code correction} unchanged.
     *
     * @param projectId       the project (architecture model) the use case lives in
     * @param code            the use-case code, e.g. {@code UC1}
     * @param correction      the fields to correct, built via {@link UseCaseCorrection#builder()};
     *                        every field it leaves unset stays as it is
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - only consulted for a field/step
     *                        this call is actually changing and that ships no
     *                        {@link UseCaseCorrection#language()}
     * @return the updated use case
     * @throws UseCaseNotFoundException              if no use case with {@code code} exists in
     *                                                {@code projectId}
     * @throws RuntimeException                      if {@code correction}'s {@code primaryRole}
     *                                                or any entry of its {@code supportingRoles}
     *                                                names a role that is unknown
     *                                                within {@code projectId} - the same didactic
     *                                                rejection {@link AddUseCase} raises, thrown
     *                                                before anything is written
     * @throws RuntimeException                      if any entry of {@code correction}'s {@code
     *                                                usesTermCodes} names a glossary term unknown
     *                                                within {@code projectId} (kogn-io/arknet#540) -
     *                                                the same didactic rejection thrown before
     *                                                anything is written
     * @throws UseCaseConcurrentlyModifiedException if the write keeps losing the compare-and-set
     *                                                race against a concurrent writer across every
     *                                                retry attempt
     * @throws StepPositionNotFoundException         if {@code stepTextPatches}, {@code
     *                                                stepRealisesPatches} or {@code
     *                                                removeMainStepPositions} names a position
     *                                                with no matching existing step
     * @throws IllegalArgumentException              if the same position is named in {@code
     *                                                removeMainStepPositions} and in {@code
     *                                                stepTextPatches}/{@code stepRealisesPatches},
     *                                                or if {@code removeMainStepPositions} would
     *                                                leave the main flow empty
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if a changed field/step
     *                                                ships no {@code language} and {@code
     *                                                defaultLanguage} is {@code null} too
     */
    UseCase update(ProjectId projectId, UseCaseCode code, UseCaseCorrection correction,
            String defaultLanguage);

    /**
     * The fields one {@code uc_update} call corrects - every one of them optional, {@code null}
     * meaning "leave this as it is".
     *
     * <p>Built through {@link #builder()} rather than its canonical constructor: naming each
     * field at the call site is the point of this type (see the port's own "one correction
     * object" note), and the builder keeps the one place that has to get a dozen positional
     * arguments right down to {@link Builder#build()}.</p>
     *
     * @param title               the new short human-readable name, or {@code null} to leave it
     *                            unchanged
     * @param goal                the new goal the primary actor wants to achieve, or {@code null}
     *                            to leave it unchanged
     * @param scope               the new system/design scope, or {@code null} to leave it
     *                            unchanged
     * @param trigger             the new triggering event, or {@code null} to leave it unchanged
     * @param primaryRole         the business code of the role that should be this use case's
     *                            primary role going forward, resolved against the role register,
     *                            or {@code null} to leave it unchanged - a use case always has
     *                            exactly one, so there is no way to clear it
     * @param supportingRoles     the business codes of the supporting roles this use case
     *                            should carry going forward, resolved against the role register
     *                            and replacing the existing ones wholesale; an empty list clears
     *                            them all, {@code null} leaves them unchanged
     * @param precondition        the new precondition, or {@code null} to leave it unchanged
     * @param postcondition       the new postcondition, or {@code null} to leave it unchanged
     * @param extensions          the new alternative/exception flows, replacing the existing ones
     *                            wholesale, or {@code null} to leave them unchanged
     * @param stepTextPatches     text corrections for individual existing main-flow steps,
     *                            addressed by their {@code position}, or {@code null} to leave
     *                            every step's text unchanged
     * @param stepRealisesPatches corrections to individual existing main-flow steps'
     *                            {@code realises} references, addressed by their
     *                            {@code position} - each listed position's value list replaces
     *                            that step's entire {@code realises} set wholesale, an empty
     *                            list clears it, and a position not listed here is left
     *                            unchanged; {@code null} to leave every step's realises
     *                            unchanged
     * @param newMainSteps        main-flow steps to append after the existing ones
     *                            (kogn-io/arknet#513), or {@code null}/empty for none - always
     *                            allowed (see
     *                            {@link de.hauschel.arknet.uc.domain.UseCase#withAppendedMainSteps})
     * @param removeMainStepPositions the 1-based positions, as the use case currently numbers
     *                            them, of the main-flow steps to remove (kogn-io/arknet#513), or
     *                            {@code null}/{@link RemovedPositions#NONE} for none; the steps
     *                            after a removed one move up. Naming the same position here and in
     *                            {@code stepTextPatches}/{@code stepRealisesPatches} is refused, and
     *                            removing every remaining step is refused too - a use case must
     *                            have at least one (see
     *                            {@link de.hauschel.arknet.uc.domain.UseCase#withoutMainSteps})
     * @param usesTermCodes       business codes of the glossary terms this use case should use
     *                            going forward, e.g. {@code TERM-1}, replacing the existing
     *                            {@code arkreq:usesTerm} edges wholesale; an empty list clears them
     *                            all, {@code null} leaves them unchanged (kogn-io/arknet#540) - see
     *                            the class-level note
     * @param language            the BCP-47 language tag every field this call actually touches
     *                            (a non-{@code null} {@code title}/{@code goal}/{@code scope}/
     *                            {@code trigger}/{@code precondition}/{@code postcondition}, each
     *                            patched or newly appended step's text, and, if {@code extensions}
     *                            is non-{@code null}, every entry of it) is written in, or
     *                            {@code null} to fall
     *                            back to the project's {@code defaultLanguage}. Only the existing
     *                            literal carrying the tag actually written is replaced per field -
     *                            every other language-tagged variant survives untouched, except an
     *                            existing untagged one that a fallback to {@code defaultLanguage}
     *                            sweeps away (see the port's class-level Language note)
     */
    record UseCaseCorrection(
            String title,
            String goal,
            String scope,
            String trigger,
            String primaryRole,
            List<String> supportingRoles,
            String precondition,
            String postcondition,
            List<String> extensions,
            List<StepTextPatch> stepTextPatches,
            List<StepRealisesPatch> stepRealisesPatches,
            List<NewMainStep> newMainSteps,
            RemovedPositions removeMainStepPositions,
            List<String> usesTermCodes,
            String language) {

        public UseCaseCorrection {
            newMainSteps = newMainSteps == null ? List.of() : List.copyOf(newMainSteps);
            // usesTermCodes is not normalised to List.of() here: null/empty carry different
            // meaning for this field alone (leave alone vs. clear), same as AdrCorrection's own
            // four reference lists.
            usesTermCodes = usesTermCodes == null ? null : List.copyOf(usesTermCodes);
            removeMainStepPositions = removeMainStepPositions == null ? RemovedPositions.NONE : removeMainStepPositions;
            rejectCorrectingARemovedPosition(stepTextPatches == null ? List.of()
                    : stepTextPatches.stream().map(StepTextPatch::position).toList(),
                    removeMainStepPositions, "main-flow step");
            rejectCorrectingARemovedPosition(stepRealisesPatches == null ? List.of()
                    : stepRealisesPatches.stream().map(StepRealisesPatch::position).toList(),
                    removeMainStepPositions, "main-flow step");
        }

        /**
         * A position both corrected (text or realises) and removed in one call is a
         * contradiction, refused here on the correction object itself rather than deep in the
         * mutation chain, so the caller learns which list collides before anything is read or
         * written (kogn-io/arknet#513, mirrors {@code AdrCorrection}'s own
         * {@code rejectCorrectingARemovedPosition}).
         */
        private static void rejectCorrectingARemovedPosition(
                List<Integer> correctedPositions, RemovedPositions removed, String what) {
            for (Integer position : correctedPositions) {
                if (removed.contains(position)) {
                    throw new IllegalArgumentException(what + " position " + position
                            + " is named both as a correction and as a removal - remove it or correct it, not both");
                }
            }
        }

        /** @return a builder for a correction that, until something is set on it, changes nothing */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Collects the fields of one correction by name.
         *
         * <p>Every setter is optional and a field never set stays {@code null} - "leave it as it
         * is". Not thread-safe, and meant to be built and handed over within one call.</p>
         */
        public static final class Builder {

            private String title;
            private String goal;
            private String scope;
            private String trigger;
            private String primaryRole;
            private List<String> supportingRoles;
            private String precondition;
            private String postcondition;
            private List<String> extensions;
            private List<StepTextPatch> stepTextPatches;
            private List<StepRealisesPatch> stepRealisesPatches;
            private List<NewMainStep> newMainSteps;
            private RemovedPositions removeMainStepPositions;
            private List<String> usesTermCodes;
            private String language;

            private Builder() {
            }

            /** @param value see {@link UseCaseCorrection#title()} @return this builder */
            public Builder title(String value) {
                this.title = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#goal()} @return this builder */
            public Builder goal(String value) {
                this.goal = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#scope()} @return this builder */
            public Builder scope(String value) {
                this.scope = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#trigger()} @return this builder */
            public Builder trigger(String value) {
                this.trigger = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#primaryRole()} @return this builder */
            public Builder primaryRole(String value) {
                this.primaryRole = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#supportingRoles()} @return this builder */
            public Builder supportingRoles(List<String> value) {
                this.supportingRoles = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#precondition()} @return this builder */
            public Builder precondition(String value) {
                this.precondition = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#postcondition()} @return this builder */
            public Builder postcondition(String value) {
                this.postcondition = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#extensions()} @return this builder */
            public Builder extensions(List<String> value) {
                this.extensions = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#stepTextPatches()} @return this builder */
            public Builder stepTextPatches(List<StepTextPatch> value) {
                this.stepTextPatches = value;
                return this;
            }

            /**
             * @param value see {@link UseCaseCorrection#stepRealisesPatches()}
             * @return this builder
             */
            public Builder stepRealisesPatches(List<StepRealisesPatch> value) {
                this.stepRealisesPatches = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#newMainSteps()} @return this builder */
            public Builder newMainSteps(List<NewMainStep> value) {
                this.newMainSteps = value;
                return this;
            }

            /**
             * @param value see {@link UseCaseCorrection#removeMainStepPositions()}
             * @return this builder
             */
            public Builder removeMainStepPositions(RemovedPositions value) {
                this.removeMainStepPositions = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#usesTermCodes()} @return this builder */
            public Builder usesTermCodes(List<String> value) {
                this.usesTermCodes = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#language()} @return this builder */
            public Builder language(String value) {
                this.language = value;
                return this;
            }

            /** @return the correction collected so far */
            public UseCaseCorrection build() {
                return new UseCaseCorrection(title, goal, scope, trigger, primaryRole,
                        supportingRoles, precondition, postcondition, extensions, stepTextPatches,
                        stepRealisesPatches, newMainSteps, removeMainStepPositions, usesTermCodes, language);
            }
        }
    }

    /**
     * A main-flow step to append, as passed by the agent to {@code uc_update} (kogn-io/arknet#513) -
     * the position-free counterpart of {@link AddUseCase.NewStep}, since an appended step's position
     * is assigned by {@link de.hauschel.arknet.uc.domain.UseCase#withAppendedMainSteps}, continuing
     * from the use case's current highest one, never named by the caller.
     *
     * <p><strong>Raw human-typed references.</strong> {@code realises} is a list of plain business
     * codes here (e.g. {@code FR-1}), not {@link de.hauschel.arknet.uc.domain.RequirementRef}:
     * resolving them to the referenced requirements' opaque identities is the application service's
     * job, mirroring {@link AddUseCase.NewStep#realises()}.</p>
     *
     * @param text     what happens in this step (an actor or system action)
     * @param realises business codes of the functional requirements this step fulfils (e.g.
     *                 {@code FR-1}); may be empty, resolved by the service
     */
    record NewMainStep(String text, List<String> realises) {
    }

    /**
     * A correction to one existing main-flow step's {@code realises} references, addressed by
     * {@code position}.
     *
     * <p><strong>Raw human-typed references.</strong> {@code realises} is a list of plain business
     * codes here (e.g. {@code FR-1}), not {@link de.hauschel.arknet.uc.domain.RequirementRef}:
     * resolving them to the referenced requirements' opaque identities is the application
     * service's job, mirroring {@link AddUseCase.NewStep#realises()}.</p>
     *
     * @param position 1-based position of the existing step to correct - must match a step already
     *                 present in the use case
     * @param realises business codes of the functional requirements this step should realise going
     *                 forward, replacing its current realises set wholesale; empty to clear all
     *                 references for this step
     */
    record StepRealisesPatch(int position, List<String> realises) {
    }
}
