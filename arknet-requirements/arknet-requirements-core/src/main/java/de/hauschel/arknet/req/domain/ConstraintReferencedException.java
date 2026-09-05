// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code constraint_delete} is asked to remove a constraint that a requirement or use
 * case still points at via {@code oslc_rm:constrainedBy} (kogn-io/arknet#481) - mirrors
 * {@code ActorReferencedException} exactly.
 *
 * <p>Reachable via both {@code req_link_constraint} and {@code uc_link_constraint}: the edge is
 * written by two different bounded contexts into two different named graphs, but both write the
 * same {@code oslc_rm:constrainedBy} predicate, so one reference check finds either.</p>
 */
public class ConstraintReferencedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient ConstraintCode code;
    private final transient List<String> referencingPredicates;

    /**
     * Creates the exception.
     *
     * @param projectId              the project the constraint lives in
     * @param code                   the constraint the caller tried to delete
     * @param referencingPredicates  the predicate(s) found still pointing at the constraint, in the
     *                               human-readable shorthand a caller would recognise (e.g.
     *                               {@code "constrainedBy"}), never empty
     */
    public ConstraintReferencedException(ProjectId projectId, ConstraintCode code,
            List<String> referencingPredicates) {
        super("constraint " + Objects.requireNonNull(code, "code").value() + " in project "
                + Objects.requireNonNull(projectId, "projectId").value()
                + " cannot be deleted: still referenced via "
                + String.join(", ", Objects.requireNonNull(referencingPredicates, "referencingPredicates"))
                + " - remove those edges first");
        this.projectId = projectId;
        this.code = code;
        this.referencingPredicates = List.copyOf(referencingPredicates);
    }

    /** @return the project the constraint lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the constraint the caller tried to delete */
    public ConstraintCode constraintCode() {
        return code;
    }

    /** @return the predicate(s) found still pointing at the constraint, never empty */
    public List<String> referencingPredicates() {
        return referencingPredicates;
    }
}
