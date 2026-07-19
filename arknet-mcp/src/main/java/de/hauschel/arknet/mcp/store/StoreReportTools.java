package de.hauschel.arknet.mcp.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.kernel.WorkspaceResolver;

/**
 * Generic, read-only store tools exposed over MCP: {@code store_overview} and
 * {@code resource_get}. Both are fed by one generic {@code SELECT ?s ?p ?o}
 * ({@link StoreReader}) and render domain-agnostic views, so they work for every bounded
 * context (requirements, ubiquitous-language, ...) without any type-to-tool mapping.
 *
 * <p>This is an in-adapter of the composition root, not of a bounded context: the store
 * report has no domain of its own, it is a generic technical read path over whatever the
 * BCs wrote. The rendering, CURIE resolution and query execution live in isolated,
 * unit-testable collaborators ({@link StoreReader}, {@link DigestRenderer},
 * {@link ResourceRenderer}, {@link HtmlReportRenderer}, {@link Prefixes}); this class only
 * orchestrates them and declares the {@code @McpTool} surface.</p>
 */
public final class StoreReportTools {

    private static final String REPORT_FILE_NAME = "store-report.html";

    private final StoreReader storeReader;
    private final HtmlReportRenderer htmlRenderer;
    private final DigestRenderer digestRenderer;
    private final ResourceRenderer resourceRenderer;
    private final HandleResolver handleResolver;
    private final WorkspaceResolver workspaces;
    private final Path fallbackReportDir;

    /**
     * @param storeReader       the generic store read path
     * @param prefixes          the CURIE / IRI resolver
     * @param htmlRenderer      the self-contained HTML report renderer
     * @param workspaces        resolves each call's default workspace from its origin directory (an
     *                          explicit {@code workspace} tool argument still overrides it)
     * @param fallbackReportDir the directory the HTML report is written into when a call carries no
     *                          origin directory (the report otherwise lands in the calling project)
     */
    public StoreReportTools(
            final StoreReader storeReader,
            final Prefixes prefixes,
            final HtmlReportRenderer htmlRenderer,
            final WorkspaceResolver workspaces,
            final Path fallbackReportDir) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        Objects.requireNonNull(prefixes, "prefixes");
        this.htmlRenderer = Objects.requireNonNull(htmlRenderer, "htmlRenderer");
        this.digestRenderer = new DigestRenderer(prefixes);
        this.resourceRenderer = new ResourceRenderer(prefixes);
        this.handleResolver = new HandleResolver(storeReader, prefixes);
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.fallbackReportDir = Objects.requireNonNull(fallbackReportDir, "fallbackReportDir");
    }

    @McpTool(name = "store_overview",
            description = "Domain-agnostic overview of everything in the workspace store: a compact text"
                    + " digest (resource/triple/type counts, prefix legend, one line per resource with a"
                    + " '-> resource_get(...)' drill-down, integrity hint) plus a self-contained HTML report"
                    + " written to disk (its path is returned). Works for every bounded context.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String storeOverview(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional workspace id; defaults to this server's workspace",
                    required = false)
            final String workspace) {
        final String originDir = HandleResolver.originDir(context);
        final WorkspaceId workspaceId =
                HandleResolver.resolveWorkspace(workspace, workspaces.resolve(originDir));

        final StoreSnapshot snapshot = storeReader.readSnapshot(workspaceId);
        final String digest = digestRenderer.render(workspaceId, snapshot);
        final String html = htmlRenderer.render(workspaceId, snapshot, digest);
        final Path written = writeReport(html, reportDirFor(originDir));
        return digest + "\n# HTML report: " + written + "\n";
    }

    @McpTool(name = "resource_get",
            description = "Fetch all statements of ONE resource as compact text: its outgoing statements plus"
                    + " its incoming statements (neighbours). The id is a CURIE (e.g. req:FR-1) or a full IRI;"
                    + " as a convenience a bare business id (e.g. FR-1) is resolved via dcterms:identifier.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String resourceGet(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Resource handle: CURIE (req:FR-1), full IRI, or bare id (FR-1)")
            final String id,
            @McpToolParam(description = "Optional workspace id; defaults to this server's workspace",
                    required = false)
            final String workspace) {
        final WorkspaceId workspaceId =
                HandleResolver.resolveWorkspace(workspace, workspaces.resolve(HandleResolver.originDir(context)));
        final String iri = handleResolver.resolve(workspaceId, id);
        final List<Triple> outgoing = storeReader.outgoing(workspaceId, iri);
        final List<Triple> incoming = storeReader.incoming(workspaceId, iri);
        return resourceRenderer.render(iri, outgoing, incoming);
    }

    /**
     * The directory the HTML report is written into: the calling client's origin directory when
     * it supplied one (so the report lands in the project the call came from, issue #137), else
     * the server's {@link #fallbackReportDir}.
     */
    private Path reportDirFor(final String originDir) {
        return (originDir == null || originDir.isBlank()) ? fallbackReportDir : Path.of(originDir);
    }

    private Path writeReport(final String html, final Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            final Path target = targetDir.resolve(REPORT_FILE_NAME);
            Files.writeString(target, html, StandardCharsets.UTF_8);
            return target.toAbsolutePath();
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to write store report to " + targetDir + ": "
                    + e.getMessage(), e);
        }
    }
}
