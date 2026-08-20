// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: correct the name and/or description of an already-created actor.
 *
 * <p>Backs the MVP tool {@code actor_update}. Both fields are optional: {@code null} leaves that
 * field unchanged, so a caller can correct only the description without restating the name. A
 * non-{@code null} value must still satisfy {@link Actor}'s own invariants (non-blank).</p>
 *
 * <p><strong>{@code null} means "leave alone", never "remove"</strong> - the same rule
 * {@code req_update} gives {@code priority} and {@code constraint_update} gives its text fields.
 * Clearing the optional {@code description} would need a signal of its own rather than an
 * overloading of {@code null}, and nothing asks for one yet.</p>
 *
 * <p><strong>What an actor update deliberately cannot change.</strong> Not its
 * {@link de.hauschel.arknet.actor.domain.ActorType}, and not its {@link ActorCode}: both stand from
 * the moment the actor is created. A retyped actor is not a correction of a spelling but a claim
 * that a different kind of thing is being described, and everything already pointing at
 * {@code ACTOR-N} in prose refers to the actor as classified - so retyping is left out of scope
 * here rather than implemented halfway.</p>
 */
public interface UpdateActor {

    /**
     * Updates the actor identified by {@code code} within a project, leaving any {@code null}/
     * omitted argument unchanged.
     *
     * @param projectId   the project (architecture model) the actor lives in
     * @param code        the actor code, e.g. {@code ACTOR-1}
     * @param name        the new name, or {@code null} to leave it unchanged
     * @param description the new description, or {@code null} to leave it unchanged
     * @return the updated actor
     * @throws de.hauschel.arknet.actor.domain.ActorNotFoundException if no actor with {@code code}
     *                    exists in this project
     * @throws de.hauschel.arknet.actor.domain.ActorConcurrentlyModifiedException if the write keeps
     *                    losing the race against concurrent writers across every retry attempt
     */
    Actor update(ProjectId projectId, ActorCode code, String name, String description);
}
