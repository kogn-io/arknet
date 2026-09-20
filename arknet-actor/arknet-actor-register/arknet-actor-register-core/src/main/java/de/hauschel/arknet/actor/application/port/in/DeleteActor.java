// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorReferencedException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: removes an actor and its triples from the project entirely (issue #335).
 *
 * <p>Unlike {@link UpdateActor}, there is no field-level correction here - the whole resource
 * goes away. Rejected outright, rather than silently orphaning an edge, if anything else in the
 * project still references the actor - see {@link ActorReferencedException}. Backs the MVP tool
 * {@code actor_delete}, the closing counterpart of {@link AddActor} this hexagon lacked until now.
 * A resource that is also a glossary term keeps its own {@code skos:*} triples regardless - those
 * live in the ubiquitous-language context's own named graph, out of this delete's reach (mirroring
 * {@link ActorRepository#compareAndUpdate}'s own "nothing to preserve" note).</p>
 */
public interface DeleteActor {

    /**
     * Deletes the actor identified by {@code code} from {@code projectId}.
     *
     * @param projectId the project (architecture model) the actor lives in
     * @param code      the actor code, e.g. {@code ACTOR-1}
     * @throws ActorNotFoundException   if no actor with this identity exists
     * @throws ActorReferencedException if anything else in the project still references the actor
     */
    void delete(ProjectId projectId, ActorCode code);
}
