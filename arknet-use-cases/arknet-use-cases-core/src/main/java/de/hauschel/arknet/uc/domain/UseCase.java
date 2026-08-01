// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * A flow-oriented (Cockburn-style) use case: a goal an actor pursues against
 * the system, told as an ordered sequence of {@link Step steps}.
 *
 * <p>Aggregate root of the use-cases component. All invariants are enforced in
 * the compact constructor; instances are immutable and their collections are
 * defensively copied.</p>
 *
 * <p><strong>Step ordering.</strong> The {@code steps} of the main flow must be
 * numbered {@code 1, 2, ..., n} with no gaps, no duplicates and in ascending
 * order - i.e. the step at list index {@code i} carries position {@code i + 1}.
 * Alternative and exception flows are, for now, kept as free-text
 * {@code extensions}.</p>
 *
 * @param id                opaque, unchanging identity of this use case (never a business
 *                          label); minted once by a
 *                          {@link de.hauschel.arknet.kernel.ResourceIdFactory} and stable
 *                          across relabelling
 * @param code              human-readable business label (e.g. {@code UC1}); maps to
 *                          {@code dcterms:identifier}
 * @param title             short human-readable name of the use case
 * @param goal              the goal the primary actor wants to achieve
 * @param scope             the system/boundary under design; optional (may be
 *                          {@code null})
 * @param trigger           the event that starts the use case; optional (may be
 *                          {@code null})
 * @param primaryActor      the actor whose goal the use case serves, carried as the
 *                          actor term's opaque subject identity - not as a business label
 * @param supportingActors  further participating actors; {@code 0..n}, each carried as
 *                          the actor term's opaque subject identity - not as a business
 *                          label (never {@code null}; a {@code null} argument is
 *                          normalised to an empty list)
 * @param precondition      what must hold before the use case runs; optional
 *                          (may be {@code null})
 * @param postcondition     what holds after a successful run; optional (may be
 *                          {@code null})
 * @param steps             the ordered main flow; at least one step, numbered
 *                          {@code 1..n} gap-free
 * @param extensions        alternative/exception flows as free text; {@code 0..n}
 *                          (never {@code null}; a {@code null} argument is
 *                          normalised to an empty list)
 */
public record UseCase(
        UseCaseId id,
        UseCaseCode code,
        String title,
        String goal,
        String scope,
        String trigger,
        ActorRef primaryActor,
        List<ActorRef> supportingActors,
        String precondition,
        String postcondition,
        List<Step> steps,
        List<String> extensions) {

    public UseCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(primaryActor, "primaryActor");
        Objects.requireNonNull(steps, "steps");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        supportingActors = supportingActors == null ? List.of() : List.copyOf(supportingActors);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a use case must have at least one step");
        }
        requireConsecutiveStepPositions(steps);
    }

    /**
     * Returns a new use case with {@code patches} applied to {@link #steps()} by position -
     * correcting only each matched step's {@code text} and leaving its {@code realises}
     * references, every unmatched step and every other field of this use case untouched.
     *
     * <p>{@code projectId} is a pure pass-through for {@link StepPositionNotFoundException}'s
     * message - it is never stored on this aggregate (issue #96): a hexagonal/DDD rule already
     * respected everywhere else in this codebase, {@link UseCase} carries no {@link ProjectId}
     * field.</p>
     *
     * @param projectId the project the correction is issued against, for the exception message
     *                  only
     * @param patches   text corrections for individual existing main-flow steps, addressed by
     *                  their {@code position}
     * @return a new use case with the patched steps
     * @throws StepPositionNotFoundException if a patch names a position no step in {@link #steps()}
     *                                        carries
     */
    public UseCase withStepTextPatches(ProjectId projectId, List<StepTextPatch> patches) {
        Map<Integer, String> textByPosition = new LinkedHashMap<>();
        for (StepTextPatch patch : patches) {
            textByPosition.put(patch.position(), patch.text());
        }
        List<Step> patched = steps.stream()
                .map(step -> {
                    String newText = textByPosition.remove(step.position());
                    return newText != null ? new Step(step.position(), newText, step.realises()) : step;
                })
                .toList();
        if (!textByPosition.isEmpty()) {
            int unmatchedPosition = textByPosition.keySet().iterator().next();
            throw new StepPositionNotFoundException(projectId, code, unmatchedPosition);
        }
        return new UseCase(id, code, title, goal, scope, trigger, primaryActor, supportingActors,
                precondition, postcondition, patched, extensions);
    }

    /**
     * Enforces that step positions are gap-free, duplicate-free and ascending:
     * the step at index {@code i} must carry position {@code i + 1}.
     */
    private static void requireConsecutiveStepPositions(List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) {
            int expected = i + 1;
            int actual = steps.get(i).position();
            if (actual != expected) {
                throw new IllegalArgumentException(
                        "step positions must be gap-free, duplicate-free and ascending "
                                + "(1.." + steps.size() + "); expected position " + expected
                                + " at index " + i + " but was " + actual);
            }
        }
    }
}
