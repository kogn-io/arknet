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
 * <p>In this cut nothing in the codebase yet writes either predicate against an
 * {@code arknet-actor}-minted identity - the use-case adapter's actor resolution still runs
 * against the ubiquitous-language BC's facette machinery (see {@code arknet-actor/CLAUDE.md}'s
 * "no consumer yet" note) - so this exception is currently unreachable in practice. The out-adapter
 * checks for it anyway, matching the wording of issue #335's own scope ("an actor
 * {@code arkreq:primaryActor} points at"): once a future consumer migration (tracked separately)
 * makes a use case reference an {@code arknet-actor} identity directly, the guard is already in
 * place rather than a second issue away.</p>
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
