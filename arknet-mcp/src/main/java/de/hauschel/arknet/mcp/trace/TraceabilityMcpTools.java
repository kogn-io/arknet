// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.mcp.store.AnchorContext;
import de.hauschel.arknet.mcp.store.HandleResolver;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.StoreReader;

/**
 * Read-only traceability reporting tools exposed over MCP: {@code trace_matrix}, {@code
 * orphan_check}, {@code impact_analysis}, {@code actor_usecase_matrix} and {@code
 * term_cooccurrence} (issue #108, raw strategic-design read tools - no bounded-context
 * clustering or verdict, just the data for a human or agent to draw that boundary).
 *
 * <p>Like {@link de.hauschel.arknet.mcp.store.StoreReportTools}, this is an in-adapter of the
 * composition root, not of a bounded context - it is a generic technical read path over
 * whatever the requirements/ubiquitous-language/use-cases/bounded-context/adr hexagons wrote, not a
 * bounded context of its own (ADR-006).
 * It differs from {@code store_overview}/{@code resource_get} by doing graph traversal rather
 * than a full-snapshot digest or a single-resource fetch, but reuses the very same {@link
 * StoreReader} read path and {@link HandleResolver} handle contract - see
 * {@code arknet-mcp/CLAUDE.md} for why this deliberately departs from a fully generic query.
 * Query logic and rendering live in the isolated, unit-testable {@link TraceabilityGraph}/
 * {@link TraceabilityRenderer}; this class only orchestrates them and declares the {@code
 * @McpTool} surface.</p>
 */
public final class TraceabilityMcpTools {

    private final StoreReader storeReader;
    private final TraceabilityRenderer renderer;
    private final HandleResolver handleResolver;
    private final ProjectResolver projects;
    private final DisplayLocale displayLocale;

    /**
     * @param storeReader   the generic store read path
     * @param prefixes      the CURIE / IRI resolver
     * @param projects      resolves each call's target project from its anchor
     * @param displayLocale the process-wide display-language fallback, shared with {@code
     *                      store_overview}'s read path (issue #141) - this class merges the
     *                      resolved project's own default language into it per call before
     *                      reading labels ({@link #readGraph}, issue #274), so the two no longer
     *                      necessarily agree on a term's label for a project whose configured
     *                      default differs from this daemon's; {@code store_overview} does not yet
     *                      apply the same per-project merge
     */
    public TraceabilityMcpTools(
            final StoreReader storeReader, final Prefixes prefixes, final ProjectResolver projects,
            final DisplayLocale displayLocale) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        this.renderer = new TraceabilityRenderer(Objects.requireNonNull(prefixes, "prefixes"));
        this.handleResolver = new HandleResolver(storeReader, prefixes);
        this.projects = Objects.requireNonNull(projects, "projects");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
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
        final ResolvedProject project = AnchorContext.resolveResolvedProject(context, projectAnchor, projects);
        return renderer.traceMatrix(project.id(), readGraph(project));
    }

    @McpTool(name = "orphan_check",
            description = "Finds orphaned artifacts: requirements no use case realises, glossary terms never"
                    + " referenced (neither used by a requirement, a use case or an architecture decision"
                    + " (arkarch:usesTerm), playing an actor role in a use"
                    + " case, a bounded context's ubiquitous language, nor another term's skos:broader), mentions"
                    + " without a backing edge - a requirement's, use case's or bounded context's text naming a"
                    + " term without the usesTerm/primaryActor/supportingActor/ubiquitousLanguageTerm edge (a use"
                    + " case's goal, scope, trigger, precondition, postcondition and every step/extension text"
                    + " count as its text), or a term's own skos:definition naming another term without a"
                    + " skos:broader edge - and constraints no requirement or use case is bound by via"
                    + " constrainedBy. Reported as four lists.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String orphanCheck(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional anchor identifying the project to analyse, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = AnchorContext.resolveResolvedProject(context, projectAnchor, projects);
        return renderer.orphanCheck(project.id(), readGraph(project));
    }

    @McpTool(name = "impact_analysis",
            description = "What is transitively affected if the given resource changes: follows"
                    + " arkreq:usesTerm/primaryActor/supportingActor/stepRealises, oslc_rm:constrainedBy,"
                    + " arkddd:ubiquitousLanguageTerm/upstream/downstream and"
                    + " arkarch:addressesRequirement/affectsContext/usesTerm backwards (who references this)"
                    + " and arkarch:supersededBy forwards (from a superseded decision to its"
                    + " successor) to every reachable requirement, term, use case, constraint,"
                    + " bounded context, context relationship or architecture decision. The id is a CURIE"
                    + " (e.g. req:FR-1) or a full IRI; as a convenience a bare business id (e.g. FR-1) is"
                    + " resolved via dcterms:identifier.",
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
        final ResolvedProject project = AnchorContext.resolveResolvedProject(context, projectAnchor, projects);
        final String targetIri = handleResolver.resolve(project.id(), id);
        return renderer.impactAnalysis(project.id(), readGraph(project), targetIri);
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
        final ResolvedProject project = AnchorContext.resolveResolvedProject(context, projectAnchor, projects);
        return renderer.actorUseCaseMatrix(project.id(), readGraph(project));
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
        final ResolvedProject project = AnchorContext.resolveResolvedProject(context, projectAnchor, projects);
        return renderer.termCooccurrence(project.id(), readGraph(project));
    }

    /**
     * Reads the graph for {@code project}, resolving labels under the requesting project's own
     * configured default language rather than this class's process-wide, per-daemon {@link
     * #displayLocale} - the same merge {@code term_get} already applies via {@code
     * UbiquitousLanguageMcpTools#effectiveDisplayLocale} (issue #274). Without this, a project
     * whose default language differs from the daemon's ({@code arknet.locale.requested}, "en"
     * unless configured) would have its multi-language terms/requirements matched against the
     * wrong language variant throughout {@code orphan_check}/{@code trace_matrix}/{@code
     * term_cooccurrence} - a label mismatch, not a missing edge, silently read as "no mention
     * here".
     */
    private TraceabilityGraph readGraph(final ResolvedProject project) {
        final DisplayLocale effective = displayLocale.withRequestedOverride(project.defaultLanguage());
        return TraceabilityGraph.of(storeReader.readSnapshot(project.id()), effective);
    }

}
