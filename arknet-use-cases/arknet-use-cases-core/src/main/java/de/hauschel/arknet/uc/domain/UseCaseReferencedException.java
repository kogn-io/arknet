// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code uc_delete} is asked to remove a use case another use case still points at
 * (kogn-io/arknet#566) - mirrors {@code ConstraintReferencedException} exactly.
 *
 * <p>Both edges that can hold a use case back are UML flow relations between two use cases,
 * {@code arkreq:includesUseCase} (include) and {@code arkreq:extendsUseCase} (extend). Neither is
 * written by any tool today; the shipped ontology declares them, so store-first data may carry
 * them, and a delete that ignored them would leave the edge dangling on a subject that no longer
 * exists.</p>
 *
 * <p>The message names the edges, not the use cases holding them: the predicate is what a caller
 * has to go and remove, and the reference check that produced this exception asks per predicate
 * rather than per holder.</p>
 */
public class UseCaseReferencedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient UseCaseCode code;
    private final transient List<String> referencingPredicates;

    /**
     * Creates the exception.
     *
     * @param projectId             the project the use case lives in
     * @param code                  the use case the caller tried to delete
     * @param referencingPredicates the predicate(s) found still pointing at the use case, in the
     *                              human-readable shorthand a caller would recognise (e.g.
     *                              {@code "includesUseCase"}), never empty
     */
    public UseCaseReferencedException(ProjectId projectId, UseCaseCode code,
            List<String> referencingPredicates) {
        super("use case " + Objects.requireNonNull(code, "code").value() + " in project "
                + Objects.requireNonNull(projectId, "projectId").value()
                + " cannot be deleted: still referenced via "
                + String.join(", ", Objects.requireNonNull(referencingPredicates, "referencingPredicates"))
                + " - remove those edges first");
        this.projectId = projectId;
        this.code = code;
        this.referencingPredicates = List.copyOf(referencingPredicates);
    }

    /** @return the project the use case lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the use case the caller tried to delete */
    public UseCaseCode useCaseCode() {
        return code;
    }

    /** @return the predicate(s) found still pointing at the use case, never empty */
    public List<String> referencingPredicates() {
        return referencingPredicates;
    }
}
