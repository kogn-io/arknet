// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.Objects;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;

/**
 * Resolves the project an MCP tool call targets from its anchor.
 *
 * <p>Split out of {@link HandleResolver} (issue #103): the two solve unrelated problems - this
 * class routes a call to its project before any resource lookup happens, {@link HandleResolver}
 * resolves a resource handle <em>within</em> an already-resolved project - and neither uses the
 * other's state ({@link HandleResolver}'s {@code storeReader}/{@code prefixes} play no part
 * here).
 */
public final class AnchorContext {

    private AnchorContext() {
    }

    /**
     * Resolves the project a read-tool call targets: the explicit {@code projectAnchor} argument if
     * the caller supplied one, otherwise the anchor its transport carried (ADR-016 decision 2 -
     * both delivery paths are open to every MCP client). Shared by {@link StoreReportTools}
     * ({@code store_overview}/{@code resource_get}) and the five traceability tools
     * ({@code trace_matrix}/{@code orphan_check}/{@code impact_analysis}/
     * {@code actor_usecase_matrix}/{@code term_cooccurrence}), which each expose the same
     * optional-anchor parameter, instead of each carrying its own copy.
     *
     * <p><strong>An anchor, not an id.</strong> This parameter used to be called {@code workspace}
     * and was wrapped straight into a {@link ProjectId} - so a caller could address <em>any</em>
     * dataset by naming it, with nothing checking that the name meant anything. That is the
     * cross-project bleed ADR-016 closes, arriving through the read path instead of the header: a
     * language model that extrapolated a plausible id would silently read a stranger's model. What
     * it accepts now is an anchor, which is looked up like every other anchor and rejected when it
     * is not registered.</p>
     *
     * @param context       the per-call request context, may itself be {@code null}
     * @param projectAnchor the raw tool argument, may be {@code null} or blank
     * @param projects      the resolver that maps an anchor to its project
     * @return the resolved project id
     * @throws de.hauschel.arknet.kernel.UnresolvedProjectAnchorException if neither path yielded a
     *                                                                   registered anchor
     */
    public static ProjectId resolveProject(
            McpSyncRequestContext context, String projectAnchor, ProjectResolver projects) {
        return resolveResolvedProject(context, projectAnchor, projects).id();
    }

    /**
     * Same resolution as {@link #resolveProject}, but returns the full {@link ResolvedProject}
     * rather than just its id - for a caller that also needs the resolved project's configured
     * default display language (e.g. to merge into a {@link de.hauschel.arknet.kernel.DisplayLocale}
     * override the way {@code term_get} already does, issue #274), instead of reading a resource's
     * label under whichever language the process-wide default happens to be.
     *
     * @param context       the per-call request context, may itself be {@code null}
     * @param projectAnchor the raw tool argument, may be {@code null} or blank
     * @param projects      the resolver that maps an anchor to its project
     * @return the resolved project
     * @throws de.hauschel.arknet.kernel.UnresolvedProjectAnchorException if neither path yielded a
     *                                                                   registered anchor
     */
    public static ResolvedProject resolveResolvedProject(
            McpSyncRequestContext context, String projectAnchor, ProjectResolver projects) {
        Objects.requireNonNull(projects, "projects");
        final String explicit = (projectAnchor == null || projectAnchor.isBlank()) ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    /**
     * Extracts the calling client's project anchor from the per-call transport context.
     * Null-tolerant on every hop; a {@code null} result is a caller error at
     * {@link ProjectResolver}, never a route to a default. Shared by {@link StoreReportTools} and
     * {@code de.hauschel.arknet.mcp.trace.TraceabilityMcpTools} instead of each carrying its own
     * copy, the same reasoning as {@link #resolveProject}.
     *
     * @param context the per-call request context, may itself be {@code null}
     * @return the anchor, or {@code null} if none was supplied
     */
    public static String contextAnchor(McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }
}
