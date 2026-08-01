// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a read-modify-write round trip ({@code req_set_status}, {@code req_update} without
 * explicit acceptance criteria, {@code req_link_term}) would otherwise carry forward the
 * legacy-placeholder acceptance criteria a pre-invariant requirement was read with, turning a
 * read-time stand-in into a real, persisted literal.
 *
 * <p><strong>Why this is rejected instead of silently written.</strong> {@code
 * arkreq:acceptanceCriterion} is mandatory ({@code sh:minCount 1}) only since that field was
 * introduced; a requirement created before then carries no such triple at all. The out-adapter's
 * read path substitutes a fixed placeholder text for that gap so {@link Requirement}'s
 * constructor (which rejects an empty list unconditionally) does not crash on it - a read-time
 * stand-in, not a store fact. {@code null} on {@code accept}/{@code linkTerm}/{@code update}'s
 * acceptance-criteria argument means "leave unchanged", so without this guard the very next
 * replace-by-identity write (any of the three) would write that placeholder text as a genuine
 * {@code arkreq:acceptanceCriterion} literal - after which the requirement is indistinguishable
 * from one that was deliberately given that criterion, and the gap this placeholder was meant to
 * surface becomes permanently invisible instead. Rejecting the write keeps the gap visible and
 * pushes the caller to close it explicitly (e.g. via {@code req_update} with real criteria)
 * before anything else about the requirement can change.</p>
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP tools
 * - translate it into a user-facing "add acceptance criteria first" message rather than a stack
 * trace.</p>
 */
public class MissingAcceptanceCriteriaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RequirementCode code;

    /**
     * Creates the exception.
     *
     * @param projectId the project the requirement lives in
     * @param code        the requirement code whose acceptance criteria are still synthesized
     */
    public MissingAcceptanceCriteriaException(ProjectId projectId, RequirementCode code) {
        super("requirement " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " has no acceptance criteria on record - it predates the mandatory acceptance-criterion "
                + "invariant and is currently showing a read-time placeholder, not a real one; add explicit "
                + "acceptance criteria (e.g. via req_update) before this requirement can be modified further");
        this.projectId = projectId;
        this.code = code;
    }

    /** @return the project the requirement lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the requirement code whose acceptance criteria are still synthesized */
    public RequirementCode requirementCode() {
        return code;
    }
}
