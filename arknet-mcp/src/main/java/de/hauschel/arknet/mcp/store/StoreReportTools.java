package de.hauschel.arknet.mcp.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import de.hauschel.arknet.kernel.WorkspaceId;

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
    private final Prefixes prefixes;
    private final HtmlReportRenderer htmlRenderer;
    private final DigestRenderer digestRenderer;
    private final ResourceRenderer resourceRenderer;
    private final WorkspaceId defaultWorkspaceId;
    private final Path reportDir;

    /**
     * @param storeReader        the generic store read path
     * @param prefixes           the CURIE / IRI resolver
     * @param htmlRenderer       the self-contained HTML report renderer
     * @param defaultWorkspaceId the workspace used when a tool call omits one
     * @param reportDir          the directory the HTML report is written into
     */
    public StoreReportTools(
            final StoreReader storeReader,
            final Prefixes prefixes,
            final HtmlReportRenderer htmlRenderer,
            final WorkspaceId defaultWorkspaceId,
            final Path reportDir) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
        this.htmlRenderer = Objects.requireNonNull(htmlRenderer, "htmlRenderer");
        this.digestRenderer = new DigestRenderer(prefixes);
        this.resourceRenderer = new ResourceRenderer(prefixes);
        this.defaultWorkspaceId = Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId");
        this.reportDir = Objects.requireNonNull(reportDir, "reportDir");
    }

    @McpTool(name = "store_overview",
            description = "Domain-agnostic overview of everything in the workspace store: a compact text"
                    + " digest (resource/triple/type counts, prefix legend, one line per resource with a"
                    + " '-> resource_get(...)' drill-down, integrity hint) plus a self-contained HTML report"
                    + " written to disk (its path is returned). Works for every bounded context.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String storeOverview(
            @McpToolParam(description = "Optional workspace id; defaults to this server's workspace",
                    required = false)
            final String workspace) {
        final WorkspaceId workspaceId = blankToNull(workspace) == null
                ? defaultWorkspaceId
                : new WorkspaceId(workspace.trim());

        final StoreSnapshot snapshot = storeReader.readSnapshot(workspaceId);
        final String digest = digestRenderer.render(workspaceId, snapshot);
        final String html = htmlRenderer.render(workspaceId, snapshot, digest);
        final Path written = writeReport(html);
        return digest + "\n# HTML report: " + written + "\n";
    }

    @McpTool(name = "resource_get",
            description = "Fetch all statements of ONE resource as compact text: its outgoing statements plus"
                    + " its incoming statements (neighbours). The id is a CURIE (e.g. req:FR-1) or a full IRI;"
                    + " as a convenience a bare business id (e.g. FR-1) is resolved via dcterms:identifier.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String resourceGet(
            @McpToolParam(description = "Resource handle: CURIE (req:FR-1), full IRI, or bare id (FR-1)")
            final String id,
            @McpToolParam(description = "Optional workspace id; defaults to this server's workspace",
                    required = false)
            final String workspace) {
        final WorkspaceId workspaceId = blankToNull(workspace) == null
                ? defaultWorkspaceId
                : new WorkspaceId(workspace.trim());
        final String iri = resolveHandle(workspaceId, id);
        final List<Triple> outgoing = storeReader.outgoing(workspaceId, iri);
        final List<Triple> incoming = storeReader.incoming(workspaceId, iri);
        return resourceRenderer.render(iri, outgoing, incoming);
    }

    /**
     * Resolves a resource handle to an absolute IRI following the handle contract: a full IRI
     * or CURIE is authoritative; a bare business id is a convenience resolved against
     * {@code dcterms:identifier}. An unknown prefix or an ambiguous bare id is rejected with a
     * didactic message instead of guessing.
     */
    private String resolveHandle(final WorkspaceId workspaceId, final String id) {
        final String handle = Objects.requireNonNull(id, "id").strip();
        if (handle.isEmpty()) {
            throw new IllegalArgumentException("Empty resource handle. Pass a CURIE (req:FR-1),"
                    + " a full IRI, or a bare business id (FR-1).");
        }

        final Optional<String> resolved = prefixes.toIri(handle);
        if (resolved.isPresent()) {
            return resolved.get();
        }

        // A colon that is not part of a scheme means a CURIE with an unknown prefix - do not
        // guess, explain (the handle contract is CURIE/IRI first).
        if (handle.contains(":") && !handle.contains("://")) {
            final String known = prefixes.bindings().stream()
                    .map(Prefixes.Prefix::prefix).sorted().reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Unknown prefix in handle '" + handle + "'."
                    + " Known prefixes: " + known + ". Pass a full IRI instead, or a bare business id.");
        }

        // Bare business id: resolve via dcterms:identifier; reject ambiguity across contexts.
        final List<String> matches = storeReader.findByIdentifier(workspaceId, handle);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No resource found for id '" + handle + "'."
                    + " Use a CURIE (req:FR-1) or full IRI, or check the id via store_overview.");
        }
        if (matches.size() > 1) {
            final String candidates = matches.stream()
                    .map(prefixes::toCurie).reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Ambiguous id '" + handle + "' matches several resources"
                    + " across bounded contexts: " + candidates + ". Re-call resource_get with the exact"
                    + " CURIE or IRI of the one you mean.");
        }
        return matches.get(0);
    }

    private Path writeReport(final String html) {
        try {
            Files.createDirectories(reportDir);
            final Path target = reportDir.resolve(REPORT_FILE_NAME);
            Files.writeString(target, html, StandardCharsets.UTF_8);
            return target.toAbsolutePath();
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to write store report to " + reportDir + ": "
                    + e.getMessage(), e);
        }
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
