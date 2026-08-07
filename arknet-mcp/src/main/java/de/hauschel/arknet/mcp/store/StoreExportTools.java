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
import java.util.concurrent.atomic.AtomicLong;
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
 * restore counterpart - restoring a {@code .trig} file back into a dataset is left to
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
     *                          {@link StoreReportTools}'s {@code reportHostDir})
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
     * One shared point in time per {@link #export()} call - every registered project's file lands
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
