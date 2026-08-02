// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

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
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.mcp.report.HtmlReportRenderer;
import de.hauschel.arknet.mcp.report.ModelViews;
import de.hauschel.arknet.prj.application.port.in.FindProject;
import de.hauschel.arknet.prj.domain.Project;

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
    private final ProjectResolver projects;
    private final FindProject findProject;
    private final Path fallbackReportDir;
    private final Path reportHostDir;

    /**
     * @param storeReader       the generic store read path
     * @param prefixes          the CURIE / IRI resolver
     * @param displayLocale     the display language to select among a resource's language-tagged
     *                          labels, shared with the traceability tools' read path (issue #141)
     * @param htmlRenderer      the self-contained HTML report renderer
     * @param modelViews        assembles the report's per-bounded-context sections; never fails the
     *                          tool - a context whose read path throws is reported as a warning in
     *                          the HTML and its resources fall back to the generic raw view
     * @param projects          resolves each call's target project from its anchor
     * @param findProject       looks up the resolved project's registered label for the digest and
     *                          HTML headers; an {@link Optional#empty()} result falls
     *                          back to the raw id, unchanged from before this lookup existed
     * @param fallbackReportDir the directory a project-scoped subdirectory is created under for the
     *                          HTML report; the subdirectory keeps projects that share this
     *                          directory from overwriting each other's report
     * @param reportHostDir     the host-reachable path that {@code fallbackReportDir} is bind-mounted
     *                          from, or {@code null} when the process runs directly on the machine it
     *                          reports to and no translation is needed. Set on a containerized daemon
     *                          whose {@code fallbackReportDir} is a container-internal
     *                          mount point the calling agent cannot reach.
     */
    public StoreReportTools(
            final StoreReader storeReader,
            final Prefixes prefixes,
            final DisplayLocale displayLocale,
            final HtmlReportRenderer htmlRenderer,
            final ModelViews modelViews,
            final ProjectResolver projects,
            final FindProject findProject,
            final Path fallbackReportDir,
            final Path reportHostDir) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        Objects.requireNonNull(prefixes, "prefixes");
        Objects.requireNonNull(displayLocale, "displayLocale");
        this.htmlRenderer = Objects.requireNonNull(htmlRenderer, "htmlRenderer");
        this.modelViews = Objects.requireNonNull(modelViews, "modelViews");
        this.digestRenderer = new DigestRenderer(prefixes, displayLocale);
        this.resourceRenderer = new ResourceRenderer(prefixes);
        this.handleResolver = new HandleResolver(storeReader, prefixes);
        this.projects = Objects.requireNonNull(projects, "projects");
        this.findProject = Objects.requireNonNull(findProject, "findProject");
        this.fallbackReportDir = Objects.requireNonNull(fallbackReportDir, "fallbackReportDir");
        this.reportHostDir = reportHostDir;
    }

    @McpTool(name = "store_overview",
            description = "Overview of everything in the project store: a compact, domain-agnostic text"
                    + " digest (resource/triple/type counts, prefix legend, one line per resource with a"
                    + " '-> resource_get(...)' drill-down, integrity hint) plus a self-contained HTML report"
                    + " for humans, written to disk (its path is returned). The HTML groups the model by"
                    + " bounded context - use cases with their flow, requirements with their acceptance"
                    + " criteria, glossary, bounded contexts - and keeps a raw section for the rest.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String storeOverview(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional anchor identifying the project to report on, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = AnchorContext.resolveProject(context, projectAnchor, projects);
        final Optional<String> label = findProject.findById(projectId).map(Project::label);

        final StoreSnapshot snapshot = storeReader.readSnapshot(projectId);
        final String digest = digestRenderer.render(projectId, label, snapshot);
        final String html = htmlRenderer.render(projectId, label, snapshot, digest, modelViews.of(projectId));
        return digest + "\n" + writeReportLine(html, projectId) + "\n";
    }

    @McpTool(name = "resource_get",
            description = "Fetch all statements of ONE resource as compact text: its outgoing statements plus"
                    + " its incoming statements (neighbours). The id is a CURIE (e.g. req:FR-1) or a full IRI;"
                    + " as a convenience a bare business id (e.g. FR-1) is resolved via dcterms:identifier.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String resourceGet(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Resource handle: CURIE (req:FR-1), full IRI, bare id (FR-1), or a"
                    + " blank-node reference (_:...) exactly as shown by store_overview")
            final String id,
            @McpToolParam(description = "Optional anchor identifying the project to read from, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = AnchorContext.resolveProject(context, projectAnchor, projects);
        final String iri = handleResolver.resolve(projectId, id);
        final List<Triple> outgoing = storeReader.outgoing(projectId, iri);
        final List<Triple> incoming = storeReader.incoming(projectId, iri);
        return resourceRenderer.render(iri, outgoing, incoming);
    }

    /**
     * The directory the HTML report is written into: always a project-scoped subdirectory of
     * {@link #fallbackReportDir}. The subdirectory matters because every project served by a
     * shared daemon uses the very same {@link #fallbackReportDir} - without it, the last project
     * to call {@code store_overview} would silently overwrite every other project's report under
     * the identical file name.
     *
     * <p><strong>The client's own directory is no longer a candidate</strong>, and that follows
     * from ADR-016 rather than from a preference. The report used to be written into whatever the
     * client sent in its header, because that value <em>was</em> a working directory. What arrives
     * now is an anchor: opaque, possibly a URL or a UUID, and the server does not interpret it -
     * that is the whole of decision 2. Passing it to {@code Path.of} would be exactly the
     * interpretation ADR-016 removes, and it would fail outright for any client whose anchor is
     * not a path. The written path is returned in the digest either way, and on a containerized
     * daemon this was already the only reachable target: there, the client's
     * directory does not exist inside the container at all.</p>
     *
     * <p>{@link ProjectId} is, by its own javadoc, "deliberately unconstrained beyond
     * non-blankness" - {@link FileNameSanitizer#sanitize} keeps a value carrying {@code /},
     * {@code ..} or another filesystem-unsafe character from resolving outside this method's
     * subdirectory or reaching {@link Path#resolve} unfiltered (issue #146); the {@code
     * normalize()}/containment check below is defense in depth for the one input a sanitized
     * segment cannot rule out by itself - a value that sanitizes to exactly {@code ".."} - the
     * same combination {@code io.kogn.rdf}'s own {@code DatasetLifecycleRdf4j.resolveDir} applies
     * to the very same kind of value.</p>
     */
    private Path fallbackDirFor(final ProjectId projectId) {
        final Path dir = fallbackReportDir.resolve(reportSegment(projectId)).normalize();
        if (!dir.startsWith(fallbackReportDir)) {
            // unreachable for a sanitized segment other than "." or ".." alone; defends the
            // project-scoped-subdirectory invariant regardless of what a ProjectId's deliberately
            // unconstrained form allows.
            throw new IllegalArgumentException("projectId maps outside the report directory: " + projectId.value());
        }
        return dir;
    }

    private static String reportSegment(final ProjectId projectId) {
        return FileNameSanitizer.sanitize(projectId.value());
    }

    /**
     * Writes the HTML report and renders the digest's trailing "# HTML report: ..." line - never
     * by throwing. An unwritable target still returns the digest with a failure line rather than
     * losing the whole tool response to an opaque {@link java.nio.file.AccessDeniedException}
     * whose {@code getMessage()} is only a bare path fragment. When
     * {@link #reportHostDir} is set, the reported path is translated to the host-reachable
     * equivalent - the write itself is unaffected, only the path shown to the caller
     * changes.
     *
     * <p>There used to be a second attempt here, falling back from the client's own directory to
     * {@link #fallbackReportDir}. It went with the client directory itself: since ADR-016 there is
     * only ever one target (see {@link #fallbackDirFor}), so a retry would just repeat the write
     * that has already failed.</p>
     *
     * <p>Catches {@link RuntimeException} alongside {@link IOException}: {@link #fallbackDirFor}
     * resolves the target directory from an unconstrained {@link ProjectId} and can itself throw
     * an unchecked exception (issue #146) - a {@code catch (IOException)} alone would have let
     * that escape and lose the whole tool response, the exact failure mode this method exists to
     * prevent.</p>
     */
    private String writeReportLine(final String html, final ProjectId projectId) {
        final Path targetDir;
        try {
            targetDir = fallbackDirFor(projectId);
        } catch (final RuntimeException failure) {
            return reportFailureLine(fallbackReportDir, failure);
        }
        try {
            return "# HTML report: " + displayPath(writeReport(html, targetDir), projectId);
        } catch (final RuntimeException | IOException failure) {
            return reportFailureLine(targetDir, failure);
        }
    }

    private String displayPath(final Path written, final ProjectId projectId) {
        return reportHostDir != null
                ? reportHostDir.resolve(reportSegment(projectId)).resolve(REPORT_FILE_NAME).toString()
                : written.toString();
    }

    private static String reportFailureLine(final Path targetDir, final Exception failure) {
        return "# HTML report: FAILED to write to " + targetDir + " (" + failure.getClass().getSimpleName()
                + ": " + failure.getMessage() + ") - digest above is unaffected.";
    }

    private Path writeReport(final String html, final Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        final Path target = targetDir.resolve(REPORT_FILE_NAME);
        Files.writeString(target, html, StandardCharsets.UTF_8);
        return target.toAbsolutePath();
    }
}
