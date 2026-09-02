// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.prj.application.port.in.FindProject;
import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.domain.Project;

/**
 * The one backup tool exposed over MCP: {@code project_export} writes a complete TriG export
 * ({@link StoreExporter}) into a timestamped subdirectory of a configurable base directory, once
 * per call.
 *
 * <p><strong>Two scopes, one tool.</strong> By default the call addresses no single project - it
 * asks {@link ListProjects} for every registered project and exports them all, because a backup
 * trigger is inherently cross-project. With {@code projectOnly=true} it narrows to the one project
 * this call addresses through its anchor, resolved exactly like every other tool's
 * ({@link AnchorContext}: the {@code projectAnchor} argument if given, otherwise the transport's
 * header - both delivery paths open, ADR-016 decision 2), and a missing or unregistered anchor is
 * an error rather than a silent fall-back to the full export. The anchor is simply not read in the
 * default scope; a call that addresses no project needs none. An explicit {@code projectAnchor}
 * argument given without {@code projectOnly=true} is rejected outright rather than silently
 * ignored, though - a silently ignored anchor argument would hand the caller the opposite of what
 * it asked for.</p>
 *
 * <p>There is deliberately no restore counterpart - restoring a {@code .trig} file back into a
 * dataset is left to manual or agent-assisted recovery for now.</p>
 *
 * <p>Each project's file is written to a sibling {@code .tmp} path first and only moved onto its
 * final name once {@link StoreExporter#exportTrig} returns - {@code exportTrig} now streams
 * straight into the target {@link OutputStream} rather than building the whole TriG text in
 * memory first, so a failure partway through the write must not leave a truncated, invalid
 * {@code .trig} file sitting under a name that looks like a completed export.</p>
 *
 * <p>Marked {@code readOnlyHint = true} even though it writes files, for the same reason
 * {@code store_overview} is: the hint describes mutation of the RDF store, and this tool never
 * writes to it, only to the filesystem.</p>
 */
public final class StoreExportTools {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    /**
     * Disambiguates {@link #timestampFolderName()} across calls that land in the same
     * wall-clock second - the timestamp alone used to be the whole subdirectory name, so two
     * {@code project_export} calls within one second silently shared it, and {@link
     * Files#move} then overwrote whichever project the first call had already exported
     * (issue #146). One shared, process-wide counter is enough: the disambiguator only has to
     * be unique within this daemon's lifetime, not across restarts.
     */
    private static final AtomicLong CALL_SEQUENCE = new AtomicLong();

    /**
     * Rejection for an explicit {@code projectAnchor} argument given without
     * {@code projectOnly=true}. Named after what the caller should do, the same style
     * {@code RegisteredAnchorProjectResolver}'s remedy messages use, rather than a bare "invalid
     * argument". That resolver is named rather than linked because it is package-private in
     * {@code de.hauschel.arknet.mcp}, the same way {@link StoreReportController} refers to it.
     */
    static final String ANCHOR_WITHOUT_PROJECT_ONLY_MESSAGE =
            "projectAnchor was given without projectOnly=true. Pass projectOnly=true if you meant "
                    + "to export only the project this anchor names, or omit projectAnchor if you "
                    + "meant the full backup of every registered project - a full export addresses "
                    + "no single project, so an explicit anchor cannot be silently applied to it.";

    private final ListProjects listProjects;
    private final FindProject findProject;
    private final ProjectResolver projects;
    private final StoreExporter exporter;
    private final Path fallbackExportDir;
    private final Path exportHostDir;

    /**
     * @param listProjects      lists every registered project to export in the default, full scope
     * @param findProject       looks up the project an anchor resolved to, for its label - the
     *                          {@code projectOnly=true} scope needs the same {@link Project} the
     *                          full scope gets straight from {@link ListProjects}
     * @param projects          resolves this call's target project from its anchor, used only in
     *                          the {@code projectOnly=true} scope
     * @param exporter          the complete-store TriG export path
     * @param fallbackExportDir the directory a timestamped subdirectory is created under for
     *                          every export call
     * @param exportHostDir     the host-reachable path {@code fallbackExportDir} is bind-mounted
     *                          from, or {@code null} when the process runs directly on the
     *                          machine it exports to and no translation is needed (analogous to
     *                          {@link StoreReportTools}'s {@code reportHostDir})
     */
    public StoreExportTools(
            final ListProjects listProjects, final FindProject findProject, final ProjectResolver projects,
            final StoreExporter exporter, final Path fallbackExportDir, final Path exportHostDir) {
        this.listProjects = Objects.requireNonNull(listProjects, "listProjects");
        this.findProject = Objects.requireNonNull(findProject, "findProject");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.fallbackExportDir = Objects.requireNonNull(fallbackExportDir, "fallbackExportDir");
        this.exportHostDir = exportHostDir;
    }

    @McpTool(name = "project_export",
            description = "Backup: export a complete RDF store as a .trig file (including provenance and"
                    + " project self-description) into a timestamped subdirectory of the server's export"
                    + " directory. By default this is NOT project-scoped - it exports ALL registered"
                    + " projects in one call; pass projectOnly=true to export only the project this call"
                    + " addresses. There is no matching import/restore tool yet.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String export(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Export only the project this call addresses through its anchor,"
                    + " instead of every registered project. Defaults to false, i.e. a full backup of all"
                    + " projects.", required = false)
            final Boolean projectOnly,
            @McpToolParam(description = "Optional anchor identifying the project to export, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered. Requires projectOnly=true - a full export addresses no "
                    + "single project, so this anchor is rejected rather than ignored if projectOnly "
                    + "is not also true.", required = false)
            final String projectAnchor) {
        if (projectAnchor != null && !projectAnchor.isBlank() && !Boolean.TRUE.equals(projectOnly)) {
            throw new IllegalArgumentException(ANCHOR_WITHOUT_PROJECT_ONLY_MESSAGE);
        }
        final String timestamp = timestampFolderName();
        if (Boolean.TRUE.equals(projectOnly)) {
            return exportAddressedProject(context, projectAnchor, timestamp);
        }
        return exportAll(timestamp);
    }

    private String exportAll(final String timestamp) {
        final List<Project> projectsToExport = listProjects.list();
        if (projectsToExport.isEmpty()) {
            return "(no projects to export)";
        }
        return projectsToExport.stream()
                .map(project -> exportOne(project, timestamp))
                .collect(Collectors.joining("\n")) + "\n";
    }

    /**
     * The {@code projectOnly=true} scope. Resolution is {@link AnchorContext}'s, unchanged, so a
     * missing or unregistered anchor throws
     * {@link de.hauschel.arknet.kernel.UnresolvedProjectAnchorException} exactly as it does for
     * every read tool - narrowing the export to "this project" without knowing which project that
     * is has no defensible fall-back, least of all the full export the caller just opted out of.
     */
    private String exportAddressedProject(
            final McpSyncRequestContext context, final String projectAnchor, final String timestamp) {
        final ProjectId projectId = AnchorContext.resolveProject(context, projectAnchor, projects);
        final Optional<Project> project = findProject.findById(projectId);
        if (project.isEmpty()) {
            // Not reachable today: ProjectRegistry (arknet-project-core, port/out) offers no
            // deregistration operation - only register/findByAnchor/findById/findAll/
            // findCurrentById/compareAndUpdate/updateAttributes - and there is no project_delete
            // tool, so a project this anchor just resolved to cannot vanish before this lookup
            // runs. Kept as defensive code anyway, in the same shape as every other per-project
            // failure line, rather than left out: without it, the day a deregistration operation
            // is added, project.get() below would throw NoSuchElementException instead of
            // reporting a clean failure.
            return "# Exported " + projectId.value() + ": FAILED to export (project is no longer registered)\n";
        }
        return exportOne(project.get(), timestamp) + "\n";
    }

    private String exportOne(final Project project, final String timestamp) {
        final Path targetDir = fallbackExportDir.resolve(timestamp);
        // The label alone can collide after sanitizing (e.g. "team/main" and "team main" both
        // become "team_main"). The id is unique, but plain sanitize() is not injective either
        // (issue #300) - two different raw ids can sanitize to the identical segment just like two
        // labels can - so the id part uses FileNameSanitizer#uniqueSegment, whose appended digest
        // is what actually rules out silently overwriting one project's export with another's.
        final String fileName = FileNameSanitizer.sanitize(project.label()) + "__"
                + FileNameSanitizer.uniqueSegment(project.id().value()) + ".trig";
        final Path target = targetDir.resolve(fileName);
        final Path tmp = targetDir.resolve(fileName + ".tmp");

        try {
            Files.createDirectories(targetDir);
        } catch (final IOException failure) {
            return writeFailureLine(project, targetDir, failure);
        }

        final OutputStream tmpStream;
        try {
            tmpStream = Files.newOutputStream(tmp);
        } catch (final IOException failure) {
            return writeFailureLine(project, targetDir, failure);
        }
        // Deliberately its own try/catch, not folded into the createDirectories/move steps above:
        // exportTrig both reads (acquires the dataset) and writes (streams into tmpStream), so a
        // failure here may have nothing to do with the filesystem at all - e.g. a
        // DatasetLockConflictException from a concurrently open lease. "FAILED to write" would
        // misname exactly that case (issue #146).
        try (tmpStream) {
            exporter.exportTrig(project.id(), tmpStream);
        } catch (final IOException | RuntimeException failure) {
            deleteQuietly(tmp);
            return exportFailureLine(project, failure);
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException failure) {
            deleteQuietly(tmp);
            return writeFailureLine(project, targetDir, failure);
        }
        return "# Exported " + project.label() + ": " + displayPath(timestamp, fileName);
    }

    private static String writeFailureLine(final Project project, final Path targetDir, final Exception failure) {
        return "# Exported " + project.label() + ": FAILED to write to " + targetDir + " ("
                + failure.getClass().getSimpleName() + ": " + failure.getMessage() + ")";
    }

    private static String exportFailureLine(final Project project, final Exception failure) {
        return "# Exported " + project.label() + ": FAILED to export (" + failure.getClass().getSimpleName()
                + ": " + failure.getMessage() + ")";
    }

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ignored) {
            // best-effort cleanup of a partial write; the FAILED result already reports the real error
        }
    }

    private String displayPath(final String timestamp, final String fileName) {
        return exportHostDir != null
                ? exportHostDir.resolve(timestamp).resolve(fileName).toString()
                : fallbackExportDir.resolve(timestamp).resolve(fileName).toAbsolutePath().toString();
    }

    /**
     * One shared point in time per {@link #export} call - every project exported by that call lands
     * under the very same subdirectory, not one per project - disambiguated by an appended
     * process-wide call sequence number so two calls landing in the same wall-clock second still
     * get distinct subdirectories (issue #146). Package-private (rather than fully private) only
     * so the test can predict it; not a general clock seam (no {@code Clock} injection - YAGNI, no
     * other tool in this codebase needs one either).
     */
    static String timestampFolderName() {
        return TIMESTAMP_FORMAT.format(LocalDateTime.now()) + "-" + CALL_SEQUENCE.incrementAndGet();
    }
}
