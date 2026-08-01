// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.UseCase;

/**
 * Driving port: register a new use case.
 *
 * <p>A coarse-grained write: the caller supplies the <em>complete</em> use case -
 * including its ordered step list - in a single call. Identity assignment
 * ({@code UCn}) is policy of the implementing application service.</p>
 */
public interface AddUseCase {

    /**
     * Adds a new use case.
     *
     * @param projectId the project (architecture model) to add the use case to
     * @param command     the data describing the use case to create
     * @return the persisted use case including its assigned identity
     */
    UseCase add(ProjectId projectId, NewUseCase command);

    /**
     * Input data for {@link #add(ProjectId, NewUseCase)}. Mirrors {@link UseCase} minus the
     * identity, which the service assigns.
     *
     * <p><strong>Raw human-typed references.</strong> {@code primaryActor},
     * {@code supportingActors} and each step's {@code realises} are plain business
     * labels/codes here, not {@link de.hauschel.arknet.uc.domain.ActorRef}/
     * {@link de.hauschel.arknet.uc.domain.RequirementRef}: resolving them to the referenced
     * resources' opaque identities happens in the application service, via the driven
     * {@code ActorLookup}/{@code RequirementLookup} ports, before the real {@link UseCase} and
     * its {@link de.hauschel.arknet.uc.domain.Step}s are constructed. The in-port boundary
     * therefore never sees an opaque identity - only what a human typed.</p>
     *
     * @param title            short human-readable name of the use case
     * @param goal             the goal the primary actor wants to achieve
     * @param scope            the system/boundary under design; optional (may be
     *                         {@code null})
     * @param trigger          the event that starts the use case; optional (may be
     *                         {@code null})
     * @param primaryActor     name of the actor whose goal the use case serves (e.g.
     *                         {@code Customer}), resolved by the service
     * @param supportingActors further participating actors' names; {@code 0..n} (may be
     *                         {@code null}, treated as empty), resolved by the service
     * @param precondition     what must hold before the use case runs; optional
     *                         (may be {@code null})
     * @param postcondition    what holds after a successful run; optional (may be
     *                         {@code null})
     * @param steps            the ordered main flow; at least one step, numbered
     *                         {@code 1..n} gap-free
     * @param extensions       alternative/exception flows as free text; {@code 0..n}
     *                         (may be {@code null}, treated as empty)
     */
    record NewUseCase(
            String title,
            String goal,
            String scope,
            String trigger,
            String primaryActor,
            List<String> supportingActors,
            String precondition,
            String postcondition,
            List<NewStep> steps,
            List<String> extensions) {
    }

    /**
     * One step of a new use case's main flow.
     *
     * @param position 1-based position in the flow; the flow must be numbered {@code 1..n}
     *                 gap-free
     * @param text     what happens in this step (an actor or system action)
     * @param realises business codes of the functional requirements this step fulfils (e.g.
     *                 {@code FR-1}); may be empty, resolved by the service
     */
    record NewStep(int position, String text, List<String> realises) {
    }
}
