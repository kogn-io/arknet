package de.hauschel.arknet.uc.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.Step;
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
     * @param workspaceId the workspace (architecture model) to add the use case to
     * @param command     the data describing the use case to create
     * @return the persisted use case including its assigned identity
     */
    UseCase add(WorkspaceId workspaceId, NewUseCase command);

    /**
     * Input data for {@link #add(WorkspaceId, NewUseCase)}. Mirrors {@link UseCase}
     * minus the identity, which the service assigns.
     *
     * @param title            short human-readable name of the use case
     * @param goal             the goal the primary actor wants to achieve
     * @param scope            the system/boundary under design; optional (may be
     *                         {@code null})
     * @param trigger          the event that starts the use case; optional (may be
     *                         {@code null})
     * @param primaryActor     the actor whose goal the use case serves, as a label
     *                         reference
     * @param supportingActors further participating actors; {@code 0..n} (may be
     *                         {@code null}, treated as empty)
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
            ActorRef primaryActor,
            List<ActorRef> supportingActors,
            String precondition,
            String postcondition,
            List<Step> steps,
            List<String> extensions) {
    }
}
