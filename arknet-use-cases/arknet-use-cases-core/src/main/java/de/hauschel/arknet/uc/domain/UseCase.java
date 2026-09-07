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
 * @param primaryRole       the role whose goal the use case serves (ADR-37/
 *                          kogn-io/arknet#405 Part C - formerly the actor playing that role),
 *                          carried as the role resource's opaque subject identity - not as a
 *                          business label
 * @param supportingRoles   further roles the system calls upon; {@code 0..n}, each carried as
 *                          the role resource's opaque subject identity - not as a business
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
 * @param usesTerms         glossary terms of the ubiquitous language this use case uses
 *                          (issue #329), each carried as the term's opaque subject identity -
 *                          not a business label; {@code 0..n} (never {@code null}; a
 *                          {@code null} argument is normalised to an empty list). Trails the
 *                          longer-standing fields as the most recently added ones, mirroring
 *                          {@code Requirement}'s own {@code constrainedBy} having been appended
 *                          last historically
 * @param constrainedBy     constraints this use case is bound by (issue #329), each carried as
 *                          the constraint's opaque subject identity - not a business label;
 *                          {@code 0..n} (never {@code null}; a {@code null} argument is
 *                          normalised to an empty list)
 */
public record UseCase(
        UseCaseId id,
        UseCaseCode code,
        String title,
        String goal,
        String scope,
        String trigger,
        RoleRef primaryRole,
        List<RoleRef> supportingRoles,
        String precondition,
        String postcondition,
        List<Step> steps,
        List<String> extensions,
        List<TermRef> usesTerms,
        List<ConstraintRef> constrainedBy) {

    public UseCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(primaryRole, "primaryRole");
        Objects.requireNonNull(steps, "steps");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        supportingRoles = supportingRoles == null ? List.of() : List.copyOf(supportingRoles);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        usesTerms = usesTerms == null ? List.of() : List.copyOf(usesTerms);
        constrainedBy = constrainedBy == null ? List.of() : List.copyOf(constrainedBy);
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
     *                  their {@code position}; never {@code null}
     * @return a new use case with the patched steps
     * @throws StepPositionNotFoundException if a patch names a position no step in {@link #steps()}
     *                                        carries
     */
    public UseCase withStepTextPatches(ProjectId projectId, List<StepTextPatch> patches) {
        Objects.requireNonNull(patches, "patches");
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
        return new UseCase(id, code, title, goal, scope, trigger, primaryRole, supportingRoles,
                precondition, postcondition, patched, extensions, usesTerms, constrainedBy);
    }

    /**
     * Returns a new use case with {@code realisesByPosition} applied to {@link #steps()} by
     * position - correcting only each named step's {@code realises} references and leaving its
     * {@code text}, every unmatched step and every other field of this use case untouched.
     *
     * <p>A position's value list <strong>replaces</strong> that step's entire {@code realises} set
     * wholesale; an empty list is the explicit signal to clear it, distinct from omitting the
     * position altogether (which leaves it untouched) - the same "provided value replaces
     * wholesale, absent means unchanged" rule {@code extensions} already follows at the
     * whole-use-case level (issue #255). Unlike {@code priority} in the sibling requirements
     * bounded context, where clearing an already-set value was deliberately left out of scope, a
     * wrong {@code realises} reference is a correctable mistake, not merely an unset optional
     * field.</p>
     *
     * @param projectId          the project the correction is issued against, for the exception
     *                           message only (see {@link #withStepTextPatches} for why this is
     *                           never stored on the aggregate)
     * @param realisesByPosition the corrected, already-resolved realises set for each named
     *                           existing step, keyed by {@code position}; never {@code null}
     * @return a new use case with the patched steps
     * @throws StepPositionNotFoundException if a key names a position no step in {@link #steps()}
     *                                        carries
     */
    public UseCase withStepRealisesPatches(ProjectId projectId, Map<Integer, List<RequirementRef>> realisesByPosition) {
        Objects.requireNonNull(realisesByPosition, "realisesByPosition");
        Map<Integer, List<RequirementRef>> remaining = new LinkedHashMap<>(realisesByPosition);
        List<Step> patched = steps.stream()
                .map(step -> {
                    List<RequirementRef> newRealises = remaining.remove(step.position());
                    return newRealises != null ? new Step(step.position(), step.text(), newRealises) : step;
                })
                .toList();
        if (!remaining.isEmpty()) {
            int unmatchedPosition = remaining.keySet().iterator().next();
            throw new StepPositionNotFoundException(projectId, code, unmatchedPosition);
        }
        return new UseCase(id, code, title, goal, scope, trigger, primaryRole, supportingRoles,
                precondition, postcondition, patched, extensions, usesTerms, constrainedBy);
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
