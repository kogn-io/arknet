// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code bc_unlink_context} names a triple (upstream, downstream, relationship type)
 * that does not - or no longer - exist in the targeted project.
 *
 * <p>An expected domain outcome (not a programming error): the whole point of recording this
 * signal is that a typo in the relationship type or a swapped direction must fail loudly rather
 * than silently removing nothing or the wrong edge - unlike linking an already-linked term or
 * relationship, unlinking a triple that is not there is never a silent no-op. This surfaces as the
 * {@code bc_unlink_context} tool call's own error message rather than a stack trace - no driving
 * adapter needs to catch and translate it itself.</p>
 *
 * <p><strong>Codes are optional.</strong> {@code upstreamCode}/{@code downstreamCode} may be
 * {@code null}: the out-adapter (which only ever holds opaque {@link BoundedContextId}s, never a
 * business code) throws this with both {@code null}, and
 * {@code de.hauschel.arknet.bc.application.BoundedContextService#unlinkContext}
 * catches that internal signal and re-throws with the codes it already resolved before ever calling
 * the out-port - the codes a caller actually typed, not the raw identities behind them, exactly the
 * "MCP boundary never surfaces the store-internal identity" rule the rest of this hexagon follows.
 * </p>
 */
public class ContextRelationshipNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient BoundedContextCode upstreamCode;
    private final transient BoundedContextCode downstreamCode;
    private final transient RelationshipType relationshipType;

    /**
     * Creates the exception.
     *
     * @param projectId        the project that was searched
     * @param upstreamCode     the upstream bounded-context code the caller named, or {@code null}
     *                         if only thrown from a layer that holds no business code (see the
     *                         class javadoc)
     * @param downstreamCode   the downstream bounded-context code the caller named, or {@code null}
     *                         for the same reason
     * @param relationshipType the relationship type the caller named
     */
    public ContextRelationshipNotFoundException(ProjectId projectId, BoundedContextCode upstreamCode,
            BoundedContextCode downstreamCode, RelationshipType relationshipType) {
        super(message(Objects.requireNonNull(projectId, "projectId"), upstreamCode, downstreamCode,
                Objects.requireNonNull(relationshipType, "relationshipType")));
        this.projectId = projectId;
        this.upstreamCode = upstreamCode;
        this.downstreamCode = downstreamCode;
        this.relationshipType = relationshipType;
    }

    private static String message(ProjectId projectId, BoundedContextCode upstreamCode,
            BoundedContextCode downstreamCode, RelationshipType relationshipType) {
        if (upstreamCode == null || downstreamCode == null) {
            return "no matching " + relationshipType + " context relationship in project " + projectId.value();
        }
        return "no " + relationshipType + " relationship from " + upstreamCode.value()
                + " to " + downstreamCode.value() + " in project " + projectId.value();
    }

    /** @return the project that was searched */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the upstream bounded-context code the caller named */
    public BoundedContextCode upstreamCode() {
        return upstreamCode;
    }

    /** @return the downstream bounded-context code the caller named */
    public BoundedContextCode downstreamCode() {
        return downstreamCode;
    }

    /** @return the relationship type the caller named */
    public RelationshipType relationshipType() {
        return relationshipType;
    }
}
