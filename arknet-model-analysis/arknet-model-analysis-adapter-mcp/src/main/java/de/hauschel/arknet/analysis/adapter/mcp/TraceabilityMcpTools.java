// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.adapter.mcp;

import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.analysis.application.port.in.ReadTraceabilityGraph;
import de.hauschel.arknet.analysis.application.port.in.ResolveResourceHandle;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.mcpsupport.ToolParameterDescriptions;
import de.hauschel.arknet.persistence.Prefixes;

/**
 * Read-only traceability reporting tools exposed over MCP: {@code trace_matrix}, {@code
 * orphan_check}, {@code impact_analysis}, {@code role_usecase_matrix} (ADR-37/kogn-io/arknet#405
 * Part C, formerly {@code actor_usecase_matrix}) and {@code
 * term_cooccurrence} (issue #108, raw strategic-design read tools - no bounded-context
 * clustering or verdict, just the data for a human or agent to draw that boundary).
 *
 * <p>This is the driving adapter of the model-analysis hexagon (ADR-54): it reads nothing
 * itself, but calls {@link ReadTraceabilityGraph} and {@link ResolveResourceHandle}, which the
 * component's own store adapter serves over the published language of every other context. It
 * differs from {@code store_overview}/{@code resource_get} - which stay in the composition root
 * as its type-agnostic exception (ADR-55) - by traversing a graph rather than digesting a
 * snapshot or fetching a single resource. Query logic and rendering live in the isolated,
 * unit-testable {@link TraceabilityGraph}/{@link TraceabilityRenderer}; this class only
 * orchestrates them and declares the {@code @McpTool} surface.</p>
 */
public final class TraceabilityMcpTools {

    private final ReadTraceabilityGraph graphs;
    private final ResolveResourceHandle handles;
    private final TraceabilityRenderer renderer;
    private final ProjectResolver projects;
    private final DisplayLocale displayLocale;

    /**
     * @param graphs        the in-port that reads the traced model of one project
     * @param handles       the in-port that resolves a caller's resource handle
     * @param prefixes      the CURIE / IRI resolver
     * @param projects      resolves each call's target project from its anchor
     * @param displayLocale the process-wide display-language fallback, shared with {@code
     *                      store_overview}'s read path (issue #141) - this class merges the
     *                      resolved project's own default language into it per call before
     *                      reading labels ({@link #readGraph}, issue #274), the same merge {@code
     *                      store_overview}/{@code resource_get} apply since issue #276, so the two
     *                      no longer disagree on a term's label for a project whose configured
     *                      default differs from this daemon's
     */
    public TraceabilityMcpTools(
            final ReadTraceabilityGraph graphs, final ResolveResourceHandle handles,
            final Prefixes prefixes, final ProjectResolver projects,
            final DisplayLocale displayLocale) {
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.renderer = new TraceabilityRenderer(Objects.requireNonNull(prefixes, "prefixes"));
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
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        return renderer.traceMatrix(project.id(), readGraph(project));
    }

    @McpTool(name = "orphan_check",
            description = "Finds orphaned artifacts: requirements no use case realises, glossary terms never"
                    + " referenced (neither used by a requirement, a use case or an architecture decision"
                    + " (arkarch:usesTerm), a bounded context's ubiquitous language, nor another term's"
                    + " skos:broader or skos:related),"
                    + " mentions"
                    + " without a backing edge - a requirement's, use case's, bounded context's or architecture"
                    + " decision's text naming a term without the matching"
                    + " usesTerm/primaryRole/supportingRole/ubiquitousLanguageTerm edge (a use case's goal,"
                    + " scope, trigger, precondition, postcondition and every step/extension text count as its"
                    + " text; an architecture decision's name, context, decision, every consequence's statement"
                    + " and every considered option's name/rationale count as its text), or a term's own"
                    + " skos:definition naming another term without a skos:broader or skos:related edge - and constraints no"
                    + " requirement or use case is bound by via constrainedBy. Reported as four lists. The mention"
                    + " match is literal and whole-word, not stem-based, so it also flags everyday words used in"
                    + " their ordinary sense (e.g. \"Rolle\", \"Begriff\", \"Projekt\") - a hit in that list is a"
                    + " reading hint for a human, not a finding that demands an edge.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String orphanCheck(
            final McpSyncRequestContext context,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        return renderer.orphanCheck(project.id(), readGraph(project));
    }

    @McpTool(name = "impact_analysis",
            description = "What is transitively affected if the given resource changes: follows"
                    + " arkreq:usesTerm/primaryRole/supportingRole/stepRealises, oslc_rm:constrainedBy,"
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
            @McpToolParam(description = "true: only direct (one-hop) dependents instead of the full"
                    + " transitive closure. Default false.", required = false)
            final Boolean directOnly,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final String targetIri = handles.resolve(project.id(), id);
        return renderer.impactAnalysis(project.id(), readGraph(project), targetIri,
                Boolean.TRUE.equals(directOnly));
    }

    @McpTool(name = "role_usecase_matrix",
            description = "Raw bipartite view of role/use-case involvement: for every role, which use"
                    + " case(s) reference it via arkreq:primaryRole/supportingRole; for every use case, its"
                    + " full role set; for every actor, which role(s) occupy it via arkproc:filledBy. No"
                    + " clustering, no bounded-context judgement - a shared role across"
                    + " many use cases does not by itself mean they belong to the same context."
                    + " (ADR-37/kogn-io/arknet#405 Part C, formerly actor_usecase_matrix.)",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String roleUseCaseMatrix(
            final McpSyncRequestContext context,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        return renderer.roleUseCaseMatrix(project.id(), readGraph(project));
    }

    @McpTool(name = "term_cooccurrence",
            description = "Which glossary terms are named together in the same requirement or use-case"
                    + " text - literal text co-occurrence only, not a model-edge comparison. Raw data for"
                    + " deciding whether two contexts use the same term the same way or a homonym with two"
                    + " meanings; draws no conclusion itself.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String termCooccurrence(
            final McpSyncRequestContext context,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
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
        return graphs.read(project.id(), effective);
    }

    /**
     * Extracts the calling client's project anchor from the per-call transport context.
     * Null-tolerant on every hop; a {@code null} result is a caller error at {@link
     * ProjectResolver}, never a route to a default - the same shape every driving adapter of a
     * bounded context carries.
     */
    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    /**
     * Resolves the project a call targets: the explicit {@code projectAnchor} argument if the
     * caller supplied one, otherwise the anchor its transport carried. Both delivery paths are
     * open to every MCP client.
     */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }
}
