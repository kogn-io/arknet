package de.hauschel.arknet.mcp.trace;

import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.kernel.WorkspaceResolver;
import de.hauschel.arknet.mcp.store.HandleResolver;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.StoreReader;

/**
 * Read-only traceability reporting tools exposed over MCP: {@code trace_matrix}, {@code
 * orphan_check} and {@code impact_analysis} (issue #131).
 *
 * <p>Like {@link de.hauschel.arknet.mcp.store.StoreReportTools}, this is an in-adapter of the
 * composition root, not of a bounded context - it is a generic technical read path over
 * whatever the requirements/ubiquitous-language/use-cases hexagons wrote, not a fourth BC (ADR-006).
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
    private final WorkspaceResolver workspaces;

    /**
     * @param storeReader the generic store read path
     * @param prefixes    the CURIE / IRI resolver
     * @param workspaces  resolves each call's default workspace from its origin directory (an
     *                    explicit {@code workspace} tool argument still overrides it)
     */
    public TraceabilityMcpTools(
            final StoreReader storeReader, final Prefixes prefixes, final WorkspaceResolver workspaces) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        this.renderer = new TraceabilityRenderer(Objects.requireNonNull(prefixes, "prefixes"));
        this.handleResolver = new HandleResolver(storeReader, prefixes);
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @McpTool(name = "trace_matrix",
            description = "Traceability matrix: for every requirement (FR and NFR) in the workspace, which"
                    + " glossary terms it uses (arkreq:usesTerm) and which use case(s) realise it (via their"
                    + " step flow's arkreq:stepRealises). One line per requirement, business codes not IRIs.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String traceMatrix(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional workspace id; defaults to this server's workspace",
                    required = false)
            final String workspace) {
        final WorkspaceId workspaceId = resolveWorkspace(context, workspace);
        return renderer.traceMatrix(workspaceId, readGraph(workspaceId));
    }

    @McpTool(name = "orphan_check",
            description = "Finds orphaned artifacts: requirements no use case realises, and glossary terms"
                    + " never referenced (neither used by a requirement nor playing an actor role in a use"
                    + " case). Reported as two lists.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String orphanCheck(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional workspace id; defaults to this server's workspace",
                    required = false)
            final String workspace) {
        final WorkspaceId workspaceId = resolveWorkspace(context, workspace);
        return renderer.orphanCheck(workspaceId, readGraph(workspaceId));
    }

    @McpTool(name = "impact_analysis",
            description = "What is transitively affected if the given resource changes: follows"
                    + " arkreq:usesTerm/primaryActor/supportingActor/stepRealises backwards (who references"
                    + " this) to every reachable requirement, term or use case. The id is a CURIE (e.g."
                    + " req:FR-1) or a full IRI; as a convenience a bare business id (e.g. FR-1) is resolved"
                    + " via dcterms:identifier.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String impactAnalysis(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Resource handle: CURIE (req:FR-1), full IRI, or bare id (FR-1)")
            final String id,
            @McpToolParam(description = "Optional workspace id; defaults to this server's workspace",
                    required = false)
            final String workspace) {
        final WorkspaceId workspaceId = resolveWorkspace(context, workspace);
        final String targetIri = handleResolver.resolve(workspaceId, id);
        return renderer.impactAnalysis(workspaceId, readGraph(workspaceId), targetIri);
    }

    private TraceabilityGraph readGraph(final WorkspaceId workspaceId) {
        return TraceabilityGraph.of(storeReader.readSnapshot(workspaceId));
    }

    /**
     * The workspace a call targets: an explicit {@code workspace} tool argument if given, else
     * the one resolved from the request's origin directory (issue #137).
     */
    private WorkspaceId resolveWorkspace(final McpSyncRequestContext context, final String workspace) {
        return HandleResolver.resolveWorkspace(workspace, workspaces.resolve(HandleResolver.originDir(context)));
    }
}
