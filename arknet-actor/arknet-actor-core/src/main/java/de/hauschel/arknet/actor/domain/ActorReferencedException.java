// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code actor_delete} is asked to remove an actor that something else in the project
 * still points at via {@code arkproc:filledBy} - a role that has this actor among its occupants
 * (ADR-37/kogn-io/arknet#405 Part B/{@link RoleReferencedException}'s own javadoc).
 *
 * <p><strong>No longer reachable via a use case.</strong> Between issue #336 and ADR-37/
 * kogn-io/arknet#405 Part C, this guard also rejected a delete while a use case still pointed at
 * the actor via {@code arkreq:primaryActor}/{@code supportingActor} (issue #335's original scope,
 * reachable in practice once issue #336 pointed {@code arknet-use-cases}' actor resolution at this
 * register, and since issue #343 also settable through {@code uc_update}). Part C repointed those
 * two properties at {@code arkproc:Role} instead, renaming them to {@code arkreq:primaryRole}/
 * {@code supportingRole} in the process - no use-case edge can range over an actor any more, so
 * {@code KognioRdfActorRepository.REFERENCING_PREDICATES} carries only {@code arkproc:filledBy}
 * today.</p>
 */
public class ActorReferencedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient ActorCode code;
    private final transient List<String> referencingPredicates;

    /**
     * Creates the exception.
     *
     * @param projectId              the project the actor lives in
     * @param code                   the actor the caller tried to delete
     * @param referencingPredicates  the predicate(s) found still pointing at the actor, in the
     *                               human-readable shorthand a caller would recognise (e.g.
     *                               {@code "filledBy"}), never empty
     */
    public ActorReferencedException(ProjectId projectId, ActorCode code, List<String> referencingPredicates) {
        super("actor " + Objects.requireNonNull(code, "code").value() + " in project "
                + Objects.requireNonNull(projectId, "projectId").value()
                + " cannot be deleted: still referenced via "
                + String.join(", ", Objects.requireNonNull(referencingPredicates, "referencingPredicates"))
                + " - remove those edges first");
        this.projectId = projectId;
        this.code = code;
        this.referencingPredicates = List.copyOf(referencingPredicates);
    }

    /** @return the project the actor lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the actor the caller tried to delete */
    public ActorCode actorCode() {
        return code;
    }

    /** @return the predicate(s) found still pointing at the actor, never empty */
    public List<String> referencingPredicates() {
        return referencingPredicates;
    }
}
