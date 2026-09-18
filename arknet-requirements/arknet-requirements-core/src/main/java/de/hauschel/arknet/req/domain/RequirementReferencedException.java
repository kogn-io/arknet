// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code req_delete} is asked to remove a requirement something else still points at
 * (kogn-io/arknet#566) - mirrors {@link ConstraintReferencedException} exactly.
 *
 * <p>Four incoming edges hold a requirement, written by three different bounded contexts into
 * three different named graphs: {@code arkarch:addressesRequirement} (a decision),
 * {@code arkreq:stepRealises} (a use-case step), {@code oslc_rm:satisfies} (a use case) and
 * {@code arkreq:dependsOn} (another requirement). One graph-spanning reference check finds any of
 * them.</p>
 */
public class RequirementReferencedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RequirementCode code;
    private final transient List<String> referencingPredicates;

    /**
     * Creates the exception.
     *
     * @param projectId              the project the requirement lives in
     * @param code                   the requirement the caller tried to delete
     * @param referencingPredicates  the predicate(s) found still pointing at the requirement, in
     *                               the human-readable shorthand a caller would recognise (e.g.
     *                               {@code "addressesRequirement"}), never empty
     */
    public RequirementReferencedException(ProjectId projectId, RequirementCode code,
            List<String> referencingPredicates) {
        super("requirement " + Objects.requireNonNull(code, "code").value() + " in project "
                + Objects.requireNonNull(projectId, "projectId").value()
                + " cannot be deleted: still referenced via "
                + String.join(", ", Objects.requireNonNull(referencingPredicates, "referencingPredicates"))
                + " - remove those edges first");
        this.projectId = projectId;
        this.code = code;
        this.referencingPredicates = List.copyOf(referencingPredicates);
    }

    /** @return the project the requirement lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the requirement the caller tried to delete */
    public RequirementCode requirementCode() {
        return code;
    }

    /** @return the predicate(s) found still pointing at the requirement, never empty */
    public List<String> referencingPredicates() {
        return referencingPredicates;
    }
}
