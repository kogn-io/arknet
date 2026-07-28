// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;

/**
 * Driving port: correct the goal-level fields and/or individual step wordings of an
 * already-created use case.
 *
 * <p>Backs the tool {@code uc_update} (issue #165), mirroring {@code UpdateRequirement}'s shape
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
 */
public interface UpdateUseCase {

    /**
     * Updates the use case identified by {@code code} within a workspace, leaving any
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
     * @return the updated use case
     */
    UseCase update(ProjectId projectId, UseCaseCode code, String title, String goal, String scope,
            String trigger, String precondition, String postcondition, List<String> extensions,
            List<StepTextPatch> stepTextPatches);

    /**
     * A text-only correction for the existing main-flow step at {@code position}: every other
     * aspect of that step (its {@code realises} references, its very existence) is untouched.
     *
     * @param position the 1-based position of the existing step to correct - must match a step
     *                  already present in the use case
     * @param text     the corrected step text
     */
    record StepTextPatch(int position, String text) {
    }
}
