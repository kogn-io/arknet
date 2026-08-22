// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code actor_delete} is asked to remove an actor that a use case still points at via
 * {@code arkreq:primaryActor}/{@code supportingActor} (issue #335).
 *
 * <p>Reachable in practice since issue #336: {@code arknet-use-cases} resolves a use case's
 * {@code primaryActor}/{@code supportingActor} against this register (see the "erster Konsument
 * angeschlossen" note in {@code arknet-actor/CLAUDE.md}), and since issue #343 an
 * {@code uc_update} can put such an edge on an actor as well - so {@code actor_delete} on a
 * referenced actor is rejected rather than left to dangle. The guard predates that consumer: it
 * was written with issue #335, matching the wording of that issue's own scope ("an actor
 * {@code arkreq:primaryActor} points at"), so it was already in place rather than a second issue
 * away when the first consumer arrived.</p>
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
     *                               {@code "primaryActor"}), never empty
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
