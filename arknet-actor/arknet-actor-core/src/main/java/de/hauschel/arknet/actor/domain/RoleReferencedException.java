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
 * <p><strong>Built ahead of its first consumer, then reached it.</strong> Until ADR-37/
 * kogn-io/arknet#405 Part C no property carried {@code rdfs:range arkproc:Role} yet, so
 * {@code KognioRdfRoleRepository.REFERENCING_PREDICATES} was empty and this exception was
 * unreachable - built early the same way {@link ActorReferencedException} predated issue #336's
 * actual referencing consumer (see that class's own javadoc). Part C repointed
 * {@code arkreq:primaryRole}/{@code supportingRole} at {@link Role} (they used to target
 * {@code arkproc:Actor} as {@code primaryActor}/{@code supportingActor}), so both now range over
 * {@code arkproc:Role} and {@code REFERENCING_PREDICATES} lists them: a role a use case still
 * references as its {@code primaryRole}/{@code supportingRole} now really does reject
 * {@code role_delete}. {@code ReferenceGuardsCoverEveryOntologyEdgeTest#
 * everyPropertyRangingOverARoleBlocksTheRolesDeletion} in {@code arknet-architecture-tests} holds
 * that map against the shipped ontologies, so a future property ranging over {@code arkproc:Role}
 * cannot ship unnoticed either.</p>
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
