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
 * {@code extensions} references, and renumbered steps. Every scalar field here is optional:
 * {@code null} leaves that field unchanged, so a caller can correct only what actually needs
 * correcting.</p>
 *
 * <p><strong>Step corrections are text-only (deliberately narrow scope).</strong>
 * {@code stepTextPatches} lets a caller fix the wording of one or more existing main-flow steps
 * by {@link de.hauschel.arknet.uc.domain.Step#position() position} - nothing else about a step.
 * It cannot add, remove or reorder steps, and it never touches a step's
 * {@link de.hauschel.arknet.uc.domain.Step#realises() realises} references. A patch naming a
 * position with no matching step is rejected rather than silently ignored.</p>
 *
 * <p><strong>Explicitly out of scope.</strong> {@code primaryActor}, {@code supportingActors},
 * full step-list restructuring (adding/removing/reordering steps) and {@code realises} edges are
 * untouched by this port - recreate the use case with {@code uc_add} if those need to change.</p>
 *
 * <p><strong>Language.</strong> {@code title}, {@code goal} and each patched step's {@code text}
 * may each legally carry several language-tagged variants (SKOS-S14-style {@code sh:uniqueLang}).
 * {@code language} names the BCP-47 tag every language-tagged field <em>this call actually
 * touches</em> is written in - whichever of {@code title}/{@code goal} is non-{@code null}, and
 * every step named in {@code stepTextPatches} - mirroring {@code UpdateTerm}'s single shared
 * {@code language} covering whichever of {@code prefLabel}/{@code definition} it touches. A field
 * (or step) this call does not touch keeps every language variant it already had, untouched,
 * exactly as before this parameter existed. A field/step that <em>is</em> being changed but ships
 * no {@code language} falls back to {@code defaultLanguage} (issue #258) rather than staying
 * untagged - and if that field/step's existing value already carries an untagged literal, writing
 * it under a tag equal to {@code defaultLanguage} sweeps the untagged one away instead of
 * preserving it as a spurious "other" variant (see {@code UseCaseRepository#compareAndUpdate}'s
 * {@code defaultLanguage} parameter for the out-adapter side of this).</p>
 */
public interface UpdateUseCase {

    /**
     * Updates the use case identified by {@code code} within a project, leaving any
     * {@code null}/omitted argument unchanged.
     *
     * @param projectId       the project (architecture model) the use case lives in
     * @param code            the use-case code, e.g. {@code UC1}
     * @param title           the new short human-readable name, or {@code null} to leave it
     *                        unchanged
     * @param goal            the new goal the primary actor wants to achieve, or {@code null} to
     *                        leave it unchanged
     * @param scope           the new system/design scope, or {@code null} to leave it unchanged
     * @param trigger         the new triggering event, or {@code null} to leave it unchanged
     * @param precondition    the new precondition, or {@code null} to leave it unchanged
     * @param postcondition   the new postcondition, or {@code null} to leave it unchanged
     * @param extensions      the new alternative/exception flows, replacing the existing ones
     *                        wholesale, or {@code null} to leave them unchanged
     * @param stepTextPatches text corrections for individual existing main-flow steps, addressed
     *                        by their {@code position}, or {@code null} to leave every step
     *                        unchanged
     * @param language        the BCP-47 language tag every field this call actually touches
     *                        (a non-{@code null} {@code title}/{@code goal}, each patched step's
     *                        text) is written in, or {@code null} to fall back to
     *                        {@code defaultLanguage}. Only the existing literal carrying the tag
     *                        actually written is replaced per field - every other language-tagged
     *                        variant survives untouched, except an existing untagged one that a
     *                        fallback to {@code defaultLanguage} sweeps away (see class-level
     *                        Language note)
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - only consulted for a field/step this
     *                        call is actually changing and that ships no {@code language}
     * @return the updated use case
     * @throws UseCaseNotFoundException              if no use case with {@code code} exists in
     *                                                {@code projectId}
     * @throws UseCaseConcurrentlyModifiedException if the write keeps losing the compare-and-set
     *                                                race against a concurrent writer across every
     *                                                retry attempt
     * @throws StepPositionNotFoundException         if {@code stepTextPatches} names a position
     *                                                with no matching existing step
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if a changed field/step
     *                                                ships no {@code language} and {@code
     *                                                defaultLanguage} is {@code null} too
     */
    UseCase update(ProjectId projectId, UseCaseCode code, String title, String goal, String scope,
            String trigger, String precondition, String postcondition, List<String> extensions,
            List<StepTextPatch> stepTextPatches, String language, String defaultLanguage);
}
