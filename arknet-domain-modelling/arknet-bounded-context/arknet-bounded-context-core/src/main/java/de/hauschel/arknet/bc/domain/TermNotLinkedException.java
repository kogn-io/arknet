// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code bc_unlink_term} names a glossary term that the bounded context does not
 * currently carry under the named {@link TermRelation}.
 *
 * <p>An expected domain outcome, not a programming error, and the exact counterpart of
 * {@link ContextRelationshipNotFoundException}: an unlink that removes nothing must fail loudly,
 * or a typo in the term code reads as a successful removal and the edge stays where the caller
 * believes it is gone. Linking an already-linked term remains an idempotent no-op - adding what is
 * already there leaves the model in the state the caller asked for, removing what is not there
 * does not.</p>
 *
 * <p>Surfaces as the tool call's own error message rather than a stack trace; no driving adapter
 * has to catch and translate it.</p>
 */
public class TermNotLinkedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient BoundedContextCode code;
    private final transient String termCode;
    private final TermRelation relation;

    /**
     * Creates the exception for a term that is not part of the context's language.
     *
     * @param projectId the project that was searched
     * @param code      the bounded-context code the caller named
     * @param termCode  the term code the caller named
     */
    public TermNotLinkedException(ProjectId projectId, BoundedContextCode code, String termCode) {
        this(projectId, code, termCode, TermRelation.USES);
    }

    /**
     * Creates the exception.
     *
     * @param projectId the project that was searched
     * @param code      the bounded-context code the caller named
     * @param termCode  the term code the caller named
     * @param relation  the relation the caller asked to remove
     */
    public TermNotLinkedException(ProjectId projectId, BoundedContextCode code, String termCode,
            TermRelation relation) {
        super("bounded context " + Objects.requireNonNull(code, "code").value()
                + (Objects.requireNonNull(relation, "relation") == TermRelation.USES
                        ? " does not link term " : " does not delimit term ")
                + Objects.requireNonNull(termCode, "termCode") + " in project "
                + Objects.requireNonNull(projectId, "projectId").value());
        this.projectId = projectId;
        this.code = code;
        this.termCode = termCode;
        this.relation = relation;
    }

    /** @return the project that was searched */
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

    /** @return the relation the caller asked to remove */
    public TermRelation relation() {
        return relation;
    }
}
