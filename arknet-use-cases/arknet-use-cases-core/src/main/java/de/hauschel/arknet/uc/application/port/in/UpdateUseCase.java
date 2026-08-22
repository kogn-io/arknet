// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
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
 * <p><strong>Step corrections are two independent, narrowly-scoped mechanisms.</strong>
 * {@code stepTextPatches} lets a caller fix the wording of one or more existing main-flow steps
 * by {@link de.hauschel.arknet.uc.domain.Step#position() position} - nothing else about a step;
 * it never touches a step's {@link de.hauschel.arknet.uc.domain.Step#realises() realises}
 * references. {@code stepRealisesPatches} is a separate, independent list that instead corrects
 * only a step's {@code realises} references, leaving its {@code text} untouched: a listed
 * position's value list replaces that step's entire {@code realises} set wholesale, with an
 * empty list the explicit, unambiguous signal to clear it - distinct from omitting the position
 * altogether, which leaves it unchanged (issue #255). Neither mechanism can add, remove or
 * reorder steps, and a patch naming a position with no matching step is rejected rather than
 * silently ignored, in either list.</p>
 *
 * <p><strong>Actor corrections (issue #343).</strong> {@code primaryActor} and
 * {@code supportingActors} are corrected by name, exactly as {@link AddUseCase} takes them: raw,
 * human-typed labels the application service resolves against the actor register, never opaque
 * identities. What "unchanged" means differs between the two, because the model constrains them
 * differently. A use case has exactly one primary actor ({@code sh:minCount 1}/{@code
 * sh:maxCount 1} on {@code arkreq:primaryActor}), so {@code primaryActor} is a plain
 * replace-or-leave field: {@code null} leaves it as it is, a name replaces it, and there is no
 * way to clear it. {@code supportingActors} carries no such floor and is a wholesale replace
 * with the same tri-state as {@code stepRealisesPatches}: {@code null} leaves the existing list
 * untouched, a non-{@code null} list replaces it entirely, and an empty list is the explicit,
 * unambiguous signal to clear every supporting actor. An unknown or ambiguous name is rejected
 * before anything is written, exactly as in {@link AddUseCase} - correcting an actor reference
 * never has to go through delete-and-recreate, which would mint a new {@link UseCaseCode} and
 * break every inbound reference to the use case. Neither field carries a language-tagged
 * literal, so neither ever forces a write language to be resolved: an actor-only correction
 * goes through in a project that has no {@code defaultLanguage} configured at all.</p>
 *
 * <p><strong>Explicitly out of scope.</strong> Full step-list restructuring (adding, removing or
 * reordering steps) is untouched by this port - create a replacement use case with
 * {@code uc_add} if the flow itself needs restructuring, at the price named above: a new
 * {@link UseCaseCode}, and no inbound reference carried over.</p>
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
     * @throws RuntimeException                      if {@code correction}'s {@code primaryActor}
     *                                                or any entry of its {@code supportingActors}
     *                                                names an actor that is unknown or ambiguous
     *                                                within {@code projectId} - the same didactic
     *                                                rejection {@link AddUseCase} raises, thrown
     *                                                before anything is written
     * @throws UseCaseConcurrentlyModifiedException if the write keeps losing the compare-and-set
     *                                                race against a concurrent writer across every
     *                                                retry attempt
     * @throws StepPositionNotFoundException         if {@code stepTextPatches} or
     *                                                {@code stepRealisesPatches} names a position
     *                                                with no matching existing step
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
     * @param primaryActor        the name of the actor that should be this use case's primary
     *                            actor going forward, resolved against the actor register, or
     *                            {@code null} to leave it unchanged - a use case always has
     *                            exactly one, so there is no way to clear it
     * @param supportingActors    the names of the supporting (secondary) actors this use case
     *                            should carry going forward, resolved against the actor register
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
     * @param language            the BCP-47 language tag every field this call actually touches
     *                            (a non-{@code null} {@code title}/{@code goal}/{@code scope}/
     *                            {@code trigger}/{@code precondition}/{@code postcondition}, each
     *                            patched step's text, and, if {@code extensions} is non-{@code
     *                            null}, every entry of it) is written in, or {@code null} to fall
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
            String primaryActor,
            List<String> supportingActors,
            String precondition,
            String postcondition,
            List<String> extensions,
            List<StepTextPatch> stepTextPatches,
            List<StepRealisesPatch> stepRealisesPatches,
            String language) {

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
            private String primaryActor;
            private List<String> supportingActors;
            private String precondition;
            private String postcondition;
            private List<String> extensions;
            private List<StepTextPatch> stepTextPatches;
            private List<StepRealisesPatch> stepRealisesPatches;
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

            /** @param value see {@link UseCaseCorrection#primaryActor()} @return this builder */
            public Builder primaryActor(String value) {
                this.primaryActor = value;
                return this;
            }

            /** @param value see {@link UseCaseCorrection#supportingActors()} @return this builder */
            public Builder supportingActors(List<String> value) {
                this.supportingActors = value;
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

            /** @param value see {@link UseCaseCorrection#language()} @return this builder */
            public Builder language(String value) {
                this.language = value;
                return this;
            }

            /** @return the correction collected so far */
            public UseCaseCorrection build() {
                return new UseCaseCorrection(title, goal, scope, trigger, primaryActor,
                        supportingActors, precondition, postcondition, extensions, stepTextPatches,
                        stepRealisesPatches, language);
            }
        }
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
