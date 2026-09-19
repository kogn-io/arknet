// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code bc_delete} is asked to remove a bounded context that something else still
 * points at (kogn-io/arknet#566) - mirrors {@code ConstraintReferencedException} exactly.
 *
 * <p>Five predicates can hold a context, written from four different bounded contexts into their
 * own named graphs: {@code arkarch:affectsContext} (a decision), {@code arkddd:upstream}/
 * {@code arkddd:downstream} (a context relationship), {@code arkreq:scopedTo} (a requirement) and
 * {@code arkddd:hasContext} (a domain). The message names the predicates found, in the shorthand a
 * caller would recognise, rather than the holders' codes: the edges come from several hexagons and
 * a code is not what the caller needs in order to find the tool that removes the edge.</p>
 */
public class BoundedContextReferencedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient BoundedContextCode code;
    private final transient List<String> referencingPredicates;

    /**
     * Creates the exception.
     *
     * @param projectId             the project the bounded context lives in
     * @param code                  the bounded context the caller tried to delete
     * @param referencingPredicates the predicate(s) found still pointing at the bounded context, in
     *                              the human-readable shorthand a caller would recognise (e.g.
     *                              {@code "affectsContext"}), never empty
     */
    public BoundedContextReferencedException(ProjectId projectId, BoundedContextCode code,
            List<String> referencingPredicates) {
        super("bounded context " + Objects.requireNonNull(code, "code").value() + " in project "
                + Objects.requireNonNull(projectId, "projectId").value()
                + " cannot be deleted: still referenced via "
                + String.join(", ", Objects.requireNonNull(referencingPredicates, "referencingPredicates"))
                + " - remove those edges first");
        this.projectId = projectId;
        this.code = code;
        this.referencingPredicates = List.copyOf(referencingPredicates);
    }

    /** @return the project the bounded context lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the bounded context the caller tried to delete */
    public BoundedContextCode boundedContextCode() {
        return code;
    }

    /** @return the predicate(s) found still pointing at the bounded context, never empty */
    public List<String> referencingPredicates() {
        return referencingPredicates;
    }
}
