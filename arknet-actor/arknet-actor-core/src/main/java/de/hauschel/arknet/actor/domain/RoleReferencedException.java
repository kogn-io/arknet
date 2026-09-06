// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code role_delete} is asked to remove a role that something else in the project
 * still points at - mirrors {@link ActorReferencedException} exactly.
 *
 * <p><strong>Built ahead of its first consumer, deliberately.</strong> No property carries
 * {@code rdfs:range arkproc:Role} yet - {@code arkreq:primaryActor}/{@code supportingActor} are
 * planned to be repointed at {@link Role} in a later part of kogn-io/arknet#405, not this one - so
 * {@code KognioRdfRoleRepository.REFERENCING_PREDICATES} is empty today and this exception is
 * currently unreachable. It exists now so the guard is already in place, the same way
 * {@link ActorReferencedException} predated issue #336's actual referencing consumer (see that
 * class's own javadoc): {@code ReferenceGuardsCoverEveryOntologyEdgeTest#
 * everyPropertyRangingOverARoleBlocksTheRolesDeletion} in {@code arknet-architecture-tests} will
 * fail the day a property ranging over {@code arkproc:Role} ships without this map knowing about
 * it.</p>
 */
public class RoleReferencedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RoleCode code;
    private final transient List<String> referencingPredicates;

    /**
     * Creates the exception.
     *
     * @param projectId              the project the role lives in
     * @param code                   the role the caller tried to delete
     * @param referencingPredicates  the predicate(s) found still pointing at the role, in the
     *                               human-readable shorthand a caller would recognise, never empty
     */
    public RoleReferencedException(ProjectId projectId, RoleCode code, List<String> referencingPredicates) {
        super("role " + Objects.requireNonNull(code, "code").value() + " in project "
                + Objects.requireNonNull(projectId, "projectId").value()
                + " cannot be deleted: still referenced via "
                + String.join(", ", Objects.requireNonNull(referencingPredicates, "referencingPredicates"))
                + " - remove those edges first");
        this.projectId = projectId;
        this.code = code;
        this.referencingPredicates = List.copyOf(referencingPredicates);
    }

    /** @return the project the role lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the role the caller tried to delete */
    public RoleCode roleCode() {
        return code;
    }

    /** @return the predicate(s) found still pointing at the role, never empty */
    public List<String> referencingPredicates() {
        return referencingPredicates;
    }
}
