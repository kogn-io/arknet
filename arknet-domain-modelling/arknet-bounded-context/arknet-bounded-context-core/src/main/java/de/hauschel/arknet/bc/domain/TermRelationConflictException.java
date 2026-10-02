// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a term is to be recorded under one {@link TermRelation} while the bounded context
 * already carries it under the other one.
 *
 * <p>A term is either part of a context's language or delimited by it, never both. Moving a term
 * from one relation to the other is two explicit calls - unlink, then link - so a caller never
 * flips a model statement by accident. Surfaces as the tool call's own error message.</p>
 */
public class TermRelationConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient BoundedContextCode code;
    private final transient String termCode;
    private final TermRelation existing;

    /**
     * Creates the exception.
     *
     * @param projectId the project the bounded context lives in
     * @param code      the bounded-context code the caller named
     * @param termCode  the term code the caller named
     * @param existing  the relation the term already carries
     */
    public TermRelationConflictException(ProjectId projectId, BoundedContextCode code, String termCode,
            TermRelation existing) {
        super("bounded context " + Objects.requireNonNull(code, "code").value() + " already records term "
                + Objects.requireNonNull(termCode, "termCode") + " as "
                + (Objects.requireNonNull(existing, "existing") == TermRelation.USES
                        ? "part of its language" : "delimited")
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " - unlink it with relation " + existing + " first");
        this.projectId = projectId;
        this.code = code;
        this.termCode = termCode;
        this.existing = existing;
    }

    /** @return the project the bounded context lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the bounded-context code the caller named */
    public BoundedContextCode code() {
        return code;
    }

    /** @return the term code the caller named */
    public String termCode() {
        return termCode;
    }

    /** @return the relation the term already carries */
    public TermRelation existing() {
        return existing;
    }
}
