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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.domain.Project;

/**
 * The one backup tool exposed over MCP: {@code project_export} writes every registered project's
 * complete TriG export ({@link StoreExporter}) into a timestamped subdirectory of a configurable
 * base directory, once per call.
 *
 * <p>Unlike every other tool in {@code mcp/store/}, this one addresses no single project - it has
 * no anchor parameter and takes no {@link org.springframework.ai.mcp.annotation.context.McpSyncRequestContext},
 * because a backup trigger is inherently cross-project: it asks {@link ListProjects} for every
 * registered project rather than routing one call to one dataset. There is deliberately no
 * restore counterpart (issue #154) - restoring a {@code .trig} file back into a dataset is left to
 * manual or agent-assisted recovery for now.</p>
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

    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    private final ListProjects listProjects;
    private final StoreExporter exporter;
    private final Path fallbackExportDir;
    private final Path exportHostDir;

    /**
     * @param listProjects      lists every registered project to export
     * @param exporter          the complete-store TriG export path
     * @param fallbackExportDir the directory a timestamped subdirectory is created under for
     *                          every export call
     * @param exportHostDir     the host-reachable path {@code fallbackExportDir} is bind-mounted
     *                          from, or {@code null} when the process runs directly on the
     *                          machine it exports to and no translation is needed (analogous to
     *                          {@link StoreReportTools}'s {@code reportHostDir}, issue #160)
     */
    public StoreExportTools(
            final ListProjects listProjects, final StoreExporter exporter,
            final Path fallbackExportDir, final Path exportHostDir) {
        this.listProjects = Objects.requireNonNull(listProjects, "listProjects");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.fallbackExportDir = Objects.requireNonNull(fallbackExportDir, "fallbackExportDir");
        this.exportHostDir = exportHostDir;
    }

    @McpTool(name = "project_export",
            description = "Backup: export every registered project's complete RDF store as a .trig file"
                    + " (including provenance and project self-description) into a timestamped subdirectory"
                    + " of the server's export directory. Not project-scoped - exports ALL registered"
                    + " projects in one call. There is no matching import/restore tool yet.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String export() {
        final List<Project> projects = listProjects.list();
        if (projects.isEmpty()) {
            return "(no projects to export)";
        }
        final String timestamp = timestampFolderName();
        return projects.stream()
                .map(project -> exportOne(project, timestamp))
                .collect(Collectors.joining("\n")) + "\n";
    }

    private String exportOne(final Project project, final String timestamp) {
        final Path targetDir = fallbackExportDir.resolve(timestamp);
        final String fileName = sanitize(project.label()) + ".trig";
        final Path target = targetDir.resolve(fileName);
        final Path tmp = targetDir.resolve(fileName + ".tmp");
        try {
            Files.createDirectories(targetDir);
            try (OutputStream out = Files.newOutputStream(tmp)) {
                exporter.exportTrig(project.id(), out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return "# Exported " + project.label() + ": " + displayPath(timestamp, fileName);
        } catch (final IOException | RuntimeException failure) {
            deleteQuietly(tmp);
            return "# Exported " + project.label() + ": FAILED to write to " + targetDir + " ("
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage() + ")";
        }
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
     * One shared point in time per {@link #export()} call - every registered project's file lands
     * under the very same subdirectory, not one per project. Package-private (rather than fully
     * private) only so the test can predict it; not a general clock seam (no {@code Clock}
     * injection - YAGNI, no other tool in this codebase needs one either).
     */
    static String timestampFolderName() {
        return TIMESTAMP_FORMAT.format(LocalDateTime.now());
    }

    private static String sanitize(final String label) {
        return UNSAFE_FILENAME_CHARS.matcher(label).replaceAll("_");
    }
}
