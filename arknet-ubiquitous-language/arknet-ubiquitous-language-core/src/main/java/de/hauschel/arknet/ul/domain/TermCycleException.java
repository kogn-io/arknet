// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when setting a term's {@code skos:broader} reference would create a cycle: the
 * requested broader term is the term itself, or the requested broader term's own transitive
 * {@code skos:broader} chain already leads back to the term being corrected.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP
 * tools - translate it into a didactic "would create a cycle" message rather than a stack trace.
 * Only {@code term_update} can trigger this - a brand-new term minted by {@code term_add} can
 * never already sit anywhere in an existing broader chain, so cycle detection there is
 * structurally unreachable.</p>
 */
public class TermCycleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient TermCode code;
    private final transient TermCode broaderCode;

    /**
     * Creates the exception.
     *
     * @param projectId   the project the term lives in
     * @param code        the term the caller tried to correct
     * @param broaderCode the requested broader term that would close a cycle
     */
    public TermCycleException(ProjectId projectId, TermCode code, TermCode broaderCode) {
        super("term " + Objects.requireNonNull(code, "code").value() + " in project "
                + Objects.requireNonNull(projectId, "projectId").value() + " cannot use "
                + Objects.requireNonNull(broaderCode, "broaderCode").value() + " as its skos:broader term: "
                + broaderCode.value() + " is " + code.value() + " itself, or " + code.value()
                + " already sits (directly or transitively) in " + broaderCode.value()
                + "'s own broader chain - setting it would create a cycle");
        this.projectId = projectId;
        this.code = code;
        this.broaderCode = broaderCode;
    }

    /** @return the project the term lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the term the caller tried to correct */
    public TermCode code() {
        return code;
    }

    /** @return the requested broader term that would close a cycle */
    public TermCode broaderCode() {
        return broaderCode;
    }
}
