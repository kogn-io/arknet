// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: register a new actor.
 *
 * <p>Backs the MVP tool {@code actor_add}. Identity assignment ({@code ACTOR-N}, one running
 * number shared by all four {@link ActorType}s) is policy of the implementing application
 * service.</p>
 *
 * <p><strong>No glossary obligation.</strong> Adding an actor writes an actor and nothing else: no
 * {@code skos:Concept}, no definition, no {@code TERM-N}. An actor that also deserves a glossary
 * entry gets one through {@code term_add}, as a separate decision.</p>
 */
public interface AddActor {

    /**
     * Adds a new actor.
     *
     * @param projectId the project (architecture model) to add the actor to
     * @param command   the data describing the actor to create
     * @return the persisted actor including its assigned identity and code
     */
    Actor add(ProjectId projectId, NewActor command);

    /**
     * Input data for {@link #add(ProjectId, NewActor)}.
     *
     * @param type        which of the four kinds this actor is; fixed from here on
     * @param name        what this actor is called
     * @param description free-text description, or {@code null} if none
     */
    record NewActor(ActorType type, String name, String description) {
    }
}
