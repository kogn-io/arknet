// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.mcp.store.AnchorContext;
import de.hauschel.arknet.mcp.store.HandleResolver;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.StoreReader;

/**
 * Read-only traceability reporting tools exposed over MCP: {@code trace_matrix}, {@code
 * orphan_check}, {@code impact_analysis} (issue #131), {@code actor_usecase_matrix} and {@code
 * term_cooccurrence} (issue #108, raw strategic-design read tools - no bounded-context
 * clustering or verdict, just the data for a human or agent to draw that boundary).
 *
 * <p>Like {@link de.hauschel.arknet.mcp.store.StoreReportTools}, this is an in-adapter of the
 * composition root, not of a bounded context - it is a generic technical read path over
 * whatever the requirements/ubiquitous-language/use-cases/bounded-context/adr hexagons wrote, not a
 * bounded context of its own (ADR-006).
 * It differs from {@code store_overview}/{@code resource_get} by doing graph traversal rather
 * than a full-snapshot digest or a single-resource fetch, but reuses the very same {@link
 * StoreReader} read path and {@link HandleResolver} handle contract - see the ADR-006 addendum.
 * Query logic and rendering live in the isolated, unit-testable {@link TraceabilityGraph}/
 * {@link TraceabilityRenderer}; this class only orchestrates them and declares the {@code
 * @McpTool} surface.</p>
 */
public final class TraceabilityMcpTools {

    private final StoreReader storeReader;
    private final TraceabilityRenderer renderer;
    private final HandleResolver handleResolver;
    private final ProjectResolver projects;

    /**
     * @param storeReader the generic store read path
     * @param prefixes    the CURIE / IRI resolver
     * @param projects    resolves each call's target project from its anchor
     */
    public TraceabilityMcpTools(
            final StoreReader storeReader, final Prefixes prefixes, final ProjectResolver projects) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        this.renderer = new TraceabilityRenderer(Objects.requireNonNull(prefixes, "prefixes"));
        this.handleResolver = new HandleResolver(storeReader, prefixes);
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @McpTool(name = "trace_matrix",
            description = "Traceability matrix: for every requirement (FR and NFR) in the project, which"
                    + " glossary terms it uses (arkreq:usesTerm) and which use case(s) realise it (via their"
                    + " step flow's arkreq:stepRealises). One line per requirement, business codes not IRIs.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String traceMatrix(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional anchor identifying the project to analyse, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = AnchorContext.resolveProject(context, projectAnchor, projects);
        return renderer.traceMatrix(projectId, readGraph(projectId));
    }

    @McpTool(name = "orphan_check",
            description = "Finds orphaned artifacts: requirements no use case realises, glossary terms never"
                    + " referenced (neither used by a requirement, playing an actor role in a use case, nor a"
                    + " bounded context's ubiquitous language), and terms a requirement's or bounded context's"
                    + " text names without the usesTerm/ubiquitousLanguageTerm edge to back it up. Reported as"
                    + " three lists.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String orphanCheck(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional anchor identifying the project to analyse, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = AnchorContext.resolveProject(context, projectAnchor, projects);
        return renderer.orphanCheck(projectId, readGraph(projectId));
    }

    @McpTool(name = "impact_analysis",
            description = "What is transitively affected if the given resource changes: follows"
                    + " arkreq:usesTerm/primaryActor/supportingActor/stepRealises,"
                    + " arkddd:ubiquitousLanguageTerm and"
                    + " arkarch:addressesRequirement/affectsContext/supersedes backwards (who"
                    + " references this) to every reachable requirement, term, use case, bounded context"
                    + " or architecture decision. The id is a CURIE (e.g."
                    + " req:FR-1) or a full IRI; as a convenience a bare business id (e.g. FR-1) is resolved"
                    + " via dcterms:identifier.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String impactAnalysis(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Resource handle: CURIE (req:FR-1), full IRI, or bare id (FR-1)")
            final String id,
            @McpToolParam(description = "Optional anchor identifying the project to analyse, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = AnchorContext.resolveProject(context, projectAnchor, projects);
        final String targetIri = handleResolver.resolve(projectId, id);
        return renderer.impactAnalysis(projectId, readGraph(projectId), targetIri);
    }

    @McpTool(name = "actor_usecase_matrix",
            description = "Raw bipartite view of actor/use-case involvement: for every actor, which use"
                    + " case(s) reference it via arkreq:primaryActor/supportingActor; for every use case, its"
                    + " full actor set. No clustering, no bounded-context judgement - a shared actor across"
                    + " many use cases does not by itself mean they belong to the same context.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String actorUseCaseMatrix(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional anchor identifying the project to analyse, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = HandleResolver.resolveProject(context, projectAnchor, projects);
        return renderer.actorUseCaseMatrix(projectId, readGraph(projectId));
    }

    @McpTool(name = "term_cooccurrence",
            description = "Which glossary terms are named together in the same requirement or use-case"
                    + " text - literal text co-occurrence only, not a model-edge comparison. Raw data for"
                    + " deciding whether two contexts use the same term the same way or a homonym with two"
                    + " meanings; draws no conclusion itself.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String termCooccurrence(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional anchor identifying the project to analyse, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = HandleResolver.resolveProject(context, projectAnchor, projects);
        return renderer.termCooccurrence(projectId, readGraph(projectId));
    }

    private TraceabilityGraph readGraph(final ProjectId projectId) {
        return TraceabilityGraph.of(storeReader.readSnapshot(projectId));
    }

}
