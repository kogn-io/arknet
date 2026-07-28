// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

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
import de.hauschel.arknet.mcp.report.HtmlReportRenderer;
import de.hauschel.arknet.mcp.report.ModelViews;

/**
 * Read-only store tools exposed over MCP: {@code store_overview} and {@code resource_get}.
 * Both are fed by one generic {@code SELECT ?s ?p ?o} ({@link StoreReader}), so no bounded
 * context needs a read tool of its own and a new one appears without a type-to-tool mapping.
 *
 * <p><strong>Two audiences, two shapes.</strong> The tool's return value - what the agent
 * reads - stays the domain-agnostic text digest built from that one query. The HTML report -
 * what a human reads - is assembled per bounded context through their read in-ports
 * ({@link ModelViews}), because a use case rendered as its raw triples is not a use case any
 * more: its flow is a set of opaque step subjects ordered by a position literal. The generic
 * snapshot remains the report's safety net for everything no context claims. See the ADR-006
 * addendum.</p>
 *
 * <p>This is an in-adapter of the composition root, not of a bounded context: the store report
 * has no domain of its own. Borrowing four contexts' read in-ports for display is the same
 * gateway role ADR-008 grants a driving adapter. The rendering, CURIE resolution and query
 * execution live in isolated, unit-testable collaborators ({@link StoreReader},
 * {@link DigestRenderer}, {@link ResourceRenderer}, {@link HtmlReportRenderer},
 * {@link ModelViews}, {@link Prefixes}); this class only orchestrates them and declares the
 * {@code @McpTool} surface.</p>
 */
public final class StoreReportTools {

    private static final String REPORT_FILE_NAME = "store-report.html";

    private final StoreReader storeReader;
    private final HtmlReportRenderer htmlRenderer;
    private final ModelViews modelViews;
    private final DigestRenderer digestRenderer;
    private final ResourceRenderer resourceRenderer;
    private final HandleResolver handleResolver;
    private final WorkspaceResolver workspaces;
    private final Path fallbackReportDir;
    private final Path reportHostDir;

    /**
     * @param storeReader       the generic store read path
     * @param prefixes          the CURIE / IRI resolver
     * @param htmlRenderer      the self-contained HTML report renderer
     * @param modelViews        assembles the report's per-bounded-context sections; never fails the
     *                          tool - a context whose read path throws is reported as a warning in
     *                          the HTML and its resources fall back to the generic raw view
     * @param workspaces        resolves each call's default workspace from its origin directory (an
     *                          explicit {@code workspace} tool argument still overrides it)
     * @param fallbackReportDir the directory a workspace-scoped subdirectory is created under when a
     *                          call carries no origin directory (the report otherwise lands in the
     *                          calling project); the subdirectory keeps workspaces that share this
     *                          fallback from overwriting each other's report (issue #172)
     * @param reportHostDir     the host-reachable path that {@code fallbackReportDir} is bind-mounted
     *                          from, or {@code null} when the process runs directly on the machine it
     *                          reports to (bare jar) and no translation is needed. Set on a
     *                          containerized daemon (issue #160) whose {@code fallbackReportDir} is a
     *                          container-internal mount point the calling agent cannot reach.
     */
    public StoreReportTools(
            final StoreReader storeReader,
            final Prefixes prefixes,
            final HtmlReportRenderer htmlRenderer,
            final ModelViews modelViews,
            final WorkspaceResolver workspaces,
            final Path fallbackReportDir,
            final Path reportHostDir) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        Objects.requireNonNull(prefixes, "prefixes");
        this.htmlRenderer = Objects.requireNonNull(htmlRenderer, "htmlRenderer");
        this.modelViews = Objects.requireNonNull(modelViews, "modelViews");
        this.digestRenderer = new DigestRenderer(prefixes);
        this.resourceRenderer = new ResourceRenderer(prefixes);
        this.handleResolver = new HandleResolver(storeReader, prefixes);
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.fallbackReportDir = Objects.requireNonNull(fallbackReportDir, "fallbackReportDir");
        this.reportHostDir = reportHostDir;
    }

    @McpTool(name = "store_overview",
            description = "Overview of everything in the workspace store: a compact, domain-agnostic text"
                    + " digest (resource/triple/type counts, prefix legend, one line per resource with a"
                    + " '-> resource_get(...)' drill-down, integrity hint) plus a self-contained HTML report"
                    + " for humans, written to disk (its path is returned). The HTML groups the model by"
                    + " bounded context - use cases with their flow, requirements with their acceptance"
                    + " criteria, glossary, bounded contexts - and keeps a raw section for the rest.",
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
        final String html = htmlRenderer.render(workspaceId, snapshot, digest, modelViews.of(workspaceId));
        return digest + "\n" + writeReportLine(html, reportDirFor(originDir, workspaceId), workspaceId) + "\n";
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
     * it supplied one (so the report lands in the project the call came from, issue #137), else a
     * workspace-scoped subdirectory of {@link #fallbackReportDir}. The subdirectory matters
     * because every workspace served by a shared daemon falls back to the very same
     * {@link #fallbackReportDir} (issue #172: a containerized daemon has no writable origin dir
     * for ANY project, so this is not a rare corner case but the common path there) - without it,
     * the last workspace to call {@code store_overview} would silently overwrite every other
     * workspace's report under the identical file name.
     */
    private Path reportDirFor(final String originDir, final WorkspaceId workspaceId) {
        return (originDir == null || originDir.isBlank()) ? fallbackDirFor(workspaceId) : Path.of(originDir);
    }

    private Path fallbackDirFor(final WorkspaceId workspaceId) {
        return fallbackReportDir.resolve(workspaceId.value());
    }

    /**
     * Writes the HTML report and renders the digest's trailing "# HTML report: ..." line -
     * never by throwing. A daemon shared across workspaces (issue #137/ADR-009) cannot assume
     * it shares a filesystem with every calling client (issue #158: a containerized daemon has
     * no access to a client's {@code originDir} at all), so a preferred target that turns out
     * unwritable falls back to the workspace's subdirectory of {@link #fallbackReportDir}; if
     * that fails too, the digest is still returned with a failure line instead of losing the
     * whole tool response to an opaque {@link java.nio.file.AccessDeniedException} whose
     * {@code getMessage()} is only a bare path fragment. When the report lands in the fallback
     * dir and {@link #reportHostDir} is set, the reported path is translated to the
     * host-reachable equivalent (issue #160) - the write itself still targets the fallback dir,
     * only the path shown to the caller changes.
     */
    private String writeReportLine(final String html, final Path preferredDir, final WorkspaceId workspaceId) {
        final Path fallbackDir = fallbackDirFor(workspaceId);
        try {
            return "# HTML report: " + displayPath(writeReport(html, preferredDir), preferredDir, workspaceId);
        } catch (final IOException preferredFailure) {
            if (preferredDir.equals(fallbackDir)) {
                return reportFailureLine(preferredDir, preferredFailure);
            }
            try {
                return "# HTML report: " + displayPath(writeReport(html, fallbackDir), fallbackDir, workspaceId);
            } catch (final IOException fallbackFailure) {
                return reportFailureLine(fallbackDir, fallbackFailure);
            }
        }
    }

    private String displayPath(final Path written, final Path writtenDir, final WorkspaceId workspaceId) {
        return writtenDir.equals(fallbackDirFor(workspaceId)) && reportHostDir != null
                ? reportHostDir.resolve(workspaceId.value()).resolve(REPORT_FILE_NAME).toString()
                : written.toString();
    }

    private static String reportFailureLine(final Path targetDir, final IOException e) {
        return "# HTML report: FAILED to write to " + targetDir + " (" + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ") - digest above is unaffected.";
    }

    private Path writeReport(final String html, final Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        final Path target = targetDir.resolve(REPORT_FILE_NAME);
        Files.writeString(target, html, StandardCharsets.UTF_8);
        return target.toAbsolutePath();
    }
}
