// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcpsupport.FileNameSanitizer;
import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.mcpsupport.UnresolvedProjectAnchorException;
import de.hauschel.arknet.prj.application.port.in.ExportProject;
import de.hauschel.arknet.prj.application.port.in.FindProject;
import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;

/**
 * Unit tests for {@link ProjectExportMcpTools}: by default {@code project_export} writes every
 * registered project's complete TriG export into a timestamp subdirectory of a configurable base
 * directory; with {@code projectOnly=true} it narrows to just the one project the call addresses
 * through its anchor. {@link ListProjects} is a structural fake (as {@code ProjectMcpToolsTest}
 * does for its in-ports); {@link ExportProject} is a fake that writes a fixed TriG
 * fragment, because what this adapter adds is the file-writing orchestration around it - the dump
 * itself is {@code KognioRdfProjectDatasetsExportTest}'s subject, over a real store.
 */
class ProjectExportMcpToolsTest {

    private static final ProjectId PROJECT_1 = new ProjectId("store-export-tools-test-1");
    private static final ProjectId PROJECT_2 = new ProjectId("store-export-tools-test-2");
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/store-export-tools-test-fr-1";

    @TempDir
    Path exportDir;

    /** Writes {@link #FR_1_IRI} and its title for whichever project it is asked to back up. */
    private final ExportProject exporter = (projectId, out) -> write(out,
            "<" + FR_1_IRI + "> <http://purl.org/dc/terms/title> \"Login\" .\n");

    private static void write(OutputStream out, String text) {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static FakeListProjects listProjectsOf(Project... projects) {
        return new FakeListProjects(List.of(projects));
    }

    @Test
    void exportWritesEachProjectToAFileUnderATimestampSubdirectoryOfTheFallbackDir() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/home/f/DEV/arknet")))),
                exporter, exportDir, null);

        String result = tools.export(null, null, null);

        assertThat(result).contains("# Exported arknet: ");
        List<Path> written = findTrigFiles(exportDir);
        assertThat(written).hasSize(1);
        Path file = written.get(0);
        assertThat(file.getParent().getFileName().toString())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-\\d+");
        assertThat(file.getFileName().toString())
                .isEqualTo("arknet__" + FileNameSanitizer.uniqueSegment(PROJECT_1.value()) + ".trig");
        assertThat(contentOf(file)).contains(FR_1_IRI).contains("\"Login\"");
    }

    /**
     * Regression test for issue #146: two {@code project_export} calls landing in the same
     * wall-clock second used to share one timestamp subdirectory, so the second call's {@link
     * java.nio.file.Files#move} silently overwrote the first call's file for the same project.
     * {@link ProjectExportMcpTools#timestampFolderName()} now disambiguates with an appended call
     * sequence number, so even two calls issued back to back land in distinct subdirectories.
     */
    @Test
    void exportOfTwoRapidCallsWritesToDistinctSubdirectories() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/home/f/DEV/arknet")))),
                exporter, exportDir, null);

        tools.export(null, null, null);
        tools.export(null, null, null);

        List<Path> written = findTrigFiles(exportDir);
        assertThat(written).hasSize(2);
        assertThat(written.get(0).getParent()).isNotEqualTo(written.get(1).getParent());
    }

    /**
     * A project label carrying filesystem-unsafe characters (spaces, slashes) must not break the
     * write, nor collide across projects sharing the same sanitized stem in this test's scope.
     */
    @Test
    void exportSanitizesTheProjectLabelIntoAFilesystemSafeFileName() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet / dev (main)", List.of(pathAnchor("/x")))),
                exporter, exportDir, null);

        tools.export(null, null, null);

        List<Path> written = findTrigFiles(exportDir);
        assertThat(written).hasSize(1);
        assertThat(written.get(0).getFileName().toString())
                .isEqualTo("arknet___dev__main___" + FileNameSanitizer.uniqueSegment(PROJECT_1.value()) + ".trig");
    }

    /**
     * Two projects whose labels sanitize to the identical filesystem stem ("team/main" and
     * "team main" both become "team_main") must not collide on disk - the project id is unique
     * and its {@link FileNameSanitizer#uniqueSegment} is appended to the filename precisely so
     * one export can never silently overwrite the other.
     */
    @Test
    void exportOfTwoProjectsWithCollidingSanitizedLabelsWritesDistinctFiles() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(
                        new Project(PROJECT_1, "team/main", List.of(pathAnchor("/x"))),
                        new Project(PROJECT_2, "team main", List.of(pathAnchor("/y")))),
                exporter, exportDir, null);

        String result = tools.export(null, null, null);

        assertThat(result).contains("# Exported team/main: ").contains("# Exported team main: ");
        List<Path> written = findTrigFiles(exportDir);
        assertThat(written.stream().map(p -> p.getFileName().toString()).toList())
                .containsExactlyInAnyOrder(
                        "team_main__" + FileNameSanitizer.uniqueSegment(PROJECT_1.value()) + ".trig",
                        "team_main__" + FileNameSanitizer.uniqueSegment(PROJECT_2.value()) + ".trig");
    }

    /**
     * Regression test for issue #300: the id part of the export filename used to be plain {@link
     * FileNameSanitizer#sanitize}, which - just like the label - is not injective, so two distinct
     * ids sanitizing to the identical stem (here {@code "team a"} and {@code "team!a"}, both
     * {@code "team_a"}) collided on disk exactly the way #147 already fixed for the label. The
     * comment above {@code exportOne} used to claim the id "rules out silently overwriting" a
     * collision - true only once the id part also uses {@link FileNameSanitizer#uniqueSegment}.
     */
    @Test
    void exportOfTwoProjectsWithCollidingSanitizedIdsWritesDistinctFiles() {
        ProjectId idA = new ProjectId("team a");
        ProjectId idB = new ProjectId("team!a");
        assertThat(FileNameSanitizer.sanitize(idA.value())).isEqualTo(FileNameSanitizer.sanitize(idB.value()));

        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(
                        new Project(idA, "team", List.of(pathAnchor("/x"))),
                        new Project(idB, "team", List.of(pathAnchor("/y")))),
                exporter, exportDir, null);

        tools.export(null, null, null);

        List<Path> written = findTrigFiles(exportDir);
        assertThat(written.stream().map(p -> p.getFileName().toString()).toList())
                .containsExactlyInAnyOrder(
                        "team__" + FileNameSanitizer.uniqueSegment(idA.value()) + ".trig",
                        "team__" + FileNameSanitizer.uniqueSegment(idB.value()) + ".trig");
    }

    /**
     * Two registered projects both land under the very same timestamp subdirectory - one export
     * call, one point in time, not one subdirectory per project.
     */
    @Test
    void exportWritesAllRegisteredProjectsUnderTheSameTimestampSubdirectory() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(
                        new Project(PROJECT_1, "first", List.of(pathAnchor("/x"))),
                        new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                exporter, exportDir, null);

        tools.export(null, null, null);

        List<Path> written = findTrigFiles(exportDir);
        assertThat(written).hasSize(2);
        assertThat(written.get(0).getParent()).isEqualTo(written.get(1).getParent());
    }

    /**
     * The resilience lesson applied to the export path: the daemon's own export directory can
     * be a container-internal mount the calling agent cannot reach, so the digest must report the
     * host-reachable equivalent while the write itself is unaffected.
     */
    @Test
    void exportReportsTheHostDirInsteadOfTheFallbackDirWhenSet(@TempDir Path hostDir) {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/home/f/DEV/arknet")))),
                exporter, exportDir, hostDir);

        String result = tools.export(null, null, null);

        assertThat(result).contains(hostDir.toString());
        assertThat(result).doesNotContain(exportDir.toString());
        assertThat(findTrigFiles(exportDir)).hasSize(1);
    }

    /**
     * A project whose export cannot be written must not prevent the others from being exported -
     * the whole point of exporting every project in one call is that one broken project cannot
     * take the others down with it.
     *
     * <p>Regression test for the #147 review follow-up (P1): the previous version of this test
     * blocked the whole {@code fallbackExportDir} with a plain file, which fails every project's
     * {@code Files.createDirectories} identically - it only proved "every failure is reported",
     * not the original property "a broken project does not take a working one down with it". This
     * version blocks only the SECOND project's own {@code .tmp} target file, leaving the FIRST
     * project's export to complete normally in the same call - proving the second project's
     * failure has no effect on the first's already-written result.</p>
     *
     * <p>Both projects share one timestamp subdirectory per call ({@link
     * ProjectExportMcpTools#timestampFolderName()}), and that name is unpredictable before the call
     * (current time plus a process-wide call sequence). {@link SecondElementTriggersSideEffect}
     * works around that: it defers creating the blocking directory until the stream that {@link
     * ProjectExportMcpTools#export()} iterates actually reaches the second project - by then the first
     * project's {@code exportOne} has already run to completion (streams are pulled one element at
     * a time), so the now-created timestamp subdirectory can be discovered on disk and the block
     * placed exactly at the second project's own {@code .tmp} path.</p>
     */
    @Test
    void exportOfOneProjectsWriteFailureDoesNotPreventTheOtherFromBeingExported(@TempDir Path root)
            throws Exception {
        Project working = new Project(PROJECT_1, "working", List.of(pathAnchor("/x")));
        Project broken = new Project(PROJECT_2, "broken", List.of(pathAnchor("/y")));
        String brokenTmpFileName = "broken__" + FileNameSanitizer.uniqueSegment(PROJECT_2.value()) + ".trig.tmp";

        List<Project> workingThenBroken = new SecondElementTriggersSideEffect<>(
                List.of(working, broken),
                () -> blockTmpFileOfTheOnlyTimestampSubdirectory(root, brokenTmpFileName));
        ProjectExportMcpTools tools =
                exportToolsOver(() -> workingThenBroken, exporter, root, null);

        String result = tools.export(null, null, null);

        assertThat(result).contains("# Exported working: ").doesNotContain("# Exported working: FAILED");
        assertThat(result).contains("# Exported broken: FAILED to write to");
        List<Path> written = findTrigFiles(root);
        assertThat(written).hasSize(1);
        assertThat(written.get(0).getFileName().toString())
                .isEqualTo("working__" + FileNameSanitizer.uniqueSegment(PROJECT_1.value()) + ".trig");
    }

    private static void blockTmpFileOfTheOnlyTimestampSubdirectory(Path root, String tmpFileName) {
        try {
            Path timestampSubdirectory = onlySubdirectoryOf(root);
            Files.createDirectory(timestampSubdirectory.resolve(tmpFileName));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path onlySubdirectoryOf(Path root) throws IOException {
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).findFirst()
                    .orElseThrow(() -> new IllegalStateException("expected one timestamp subdirectory under " + root));
        }
    }

    /**
     * A {@link List} that runs {@code sideEffect} the moment its second element is read, before
     * returning it - used to inject filesystem state between two sequential {@code
     * Stream#map} invocations that would otherwise be impossible to place, because the second
     * element's target path only becomes known once the first has already been processed (see
     * {@link #exportOfOneProjectsWriteFailureDoesNotPreventTheOtherFromBeingExported}).
     */
    private static final class SecondElementTriggersSideEffect<T> extends AbstractList<T> {
        private final List<T> delegate;
        private final Runnable sideEffect;

        SecondElementTriggersSideEffect(List<T> delegate, Runnable sideEffect) {
            this.delegate = delegate;
            this.sideEffect = sideEffect;
        }

        @Override
        public T get(int index) {
            if (index == 1) {
                sideEffect.run();
            }
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }
    }

    /**
     * Regression test for issue #146: a failure that happens while {@link ExportProject#export}
     * <em>reads</em> from the store (acquiring an already-open dataset) must not be reported under
     * the "FAILED to write" prefix that used to cover every failure regardless of where it
     * originated. The fake throws what the store adapter throws for a dataset another lifecycle
     * holds locked; that the adapter does throw there is pinned by
     * {@code KognioRdfProjectDatasetsExportTest#exportOfADatasetLockedByAnotherLifecycleThrows}.
     */
    @Test
    void exportOfADatasetLockedByAnotherLifecycleDoesNotClaimAWriteFailure() {
        ExportProject locked = (projectId, out) -> {
            throw new IllegalStateException("dataset " + projectId.value() + " is locked by another lifecycle");
        };
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/x")))),
                locked, exportDir, null);

        String result = tools.export(null, null, null);

        assertThat(result).contains("# Exported arknet: FAILED to export");
        assertThat(result).doesNotContain("FAILED to write");
    }

    @Test
    void exportOfNoRegisteredProjectsRendersAPlaceholderInsteadOfAnEmptyString() {
        ProjectExportMcpTools tools = exportToolsOver(listProjectsOf(), exporter, exportDir, null);

        assertThat(tools.export(null, null, null)).contains("no projects");
    }

    /**
     * Builds the tool over one {@link ListProjects}, deriving the two collaborators the
     * {@code projectOnly=true} scope needs from that very same list: {@link FindProject} looks a
     * project up by id, and the {@link ProjectResolver} matches an anchor against the projects'
     * own registered anchors, raising {@link UnresolvedProjectAnchorException} for anything else -
     * the registry's no-default, no-fallback behaviour, in the smallest fake that has it.
     */
    private static ProjectExportMcpTools exportToolsOver(
            ListProjects list, ExportProject exporter, Path exportDir, Path hostDir) {
        FindProject findProject = id -> list.list().stream().filter(p -> p.id().equals(id)).findFirst();
        ProjectResolver resolver = anchor -> projectAnchoredAt(list, anchor)
                .map(project -> new ResolvedProject(project.id(), null))
                .orElseThrow(() -> new UnresolvedProjectAnchorException(anchor, "no project registered for " + anchor));
        return new ProjectExportMcpTools(list, findProject, resolver, exporter, exportDir, hostDir);
    }

    private static Optional<Project> projectAnchoredAt(ListProjects list, String anchor) {
        if (anchor == null || anchor.isBlank()) {
            return Optional.empty();
        }
        return list.list().stream()
                .filter(project -> project.anchors().stream().anyMatch(a -> a.value().equals(anchor)))
                .findFirst();
    }

    private static McpSyncRequestContext contextAnchoredAt(String anchor) {
        McpTransportContext transport = McpTransportContext.create(Map.of(ProjectResolver.ANCHOR_KEY, anchor));
        // A dynamic proxy, as in ProjectMcpToolsTest: the interface has some thirty methods, and the
        // tool reads only the transport context.
        return (McpSyncRequestContext) Proxy.newProxyInstance(
                McpSyncRequestContext.class.getClassLoader(),
                new Class<?>[] {McpSyncRequestContext.class},
                (proxy, method, args) -> {
                    if ("transportContext".equals(method.getName())) {
                        return transport;
                    }
                    throw new UnsupportedOperationException("the export tool must not call " + method.getName());
                });
    }

    /**
     * The {@code projectOnly=true} scope over the header path: only the project this call
     * addresses through its transport anchor is exported, even though a second project is
     * registered and the default scope would have written both.
     */
    @Test
    void exportOfProjectOnlyWritesOnlyTheProjectTheTransportAnchorAddresses() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(
                        new Project(PROJECT_1, "first", List.of(pathAnchor("/x"))),
                        new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                exporter, exportDir, null);

        String result = tools.export(contextAnchoredAt("/y"), true, null);

        assertThat(result).contains("# Exported second: ").doesNotContain("# Exported first: ");
        List<Path> written = findTrigFiles(exportDir);
        assertThat(written).hasSize(1);
        assertThat(written.get(0).getFileName().toString())
                .isEqualTo("second__" + FileNameSanitizer.uniqueSegment(PROJECT_2.value()) + ".trig");
    }

    /**
     * The other delivery path the anchor model keeps open to every client: the explicit
     * {@code projectAnchor} parameter routes the same narrowed export for a client that cannot set
     * the header, and takes precedence over a header naming a different project.
     */
    @Test
    void exportOfProjectOnlyRoutesByTheExplicitAnchorParameterOverTheHeader() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(
                        new Project(PROJECT_1, "first", List.of(pathAnchor("/x"))),
                        new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                exporter, exportDir, null);

        assertThat(tools.export(null, true, "/y")).contains("# Exported second: ");
        assertThat(tools.export(contextAnchoredAt("/x"), true, "/y"))
                .as("the explicit parameter is used INSTEAD of the header, not merged with it")
                .contains("# Exported second: ").doesNotContain("# Exported first: ");
    }

    /**
     * The no-default, no-fallback rule on the narrowed scope: narrowing the export to "this project" without a
     * registered anchor has no defensible fall-back - least of all the full export the caller just
     * opted out of - so it fails the same way every read tool does.
     */
    @Test
    void exportOfProjectOnlyWithoutARegisteredAnchorFailsInsteadOfExportingEverything() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/x")))),
                exporter, exportDir, null);

        assertThatThrownBy(() -> tools.export(null, true, null))
                .as("no anchor at all")
                .isInstanceOf(UnresolvedProjectAnchorException.class);
        assertThatThrownBy(() -> tools.export(contextAnchoredAt("/somewhere/else"), true, null))
                .as("an anchor nobody registered")
                .isInstanceOf(UnresolvedProjectAnchorException.class);

        assertThat(findTrigFiles(exportDir)).isEmpty();
    }

    /**
     * Pins the defensive branch in the {@code projectOnly=true} scope that reports a per-project
     * failure line instead of throwing {@link java.util.NoSuchElementException} when the resolved
     * project cannot be found by id - not reachable through the registry today (it offers no
     * deregistration operation), but kept for the day one is added. Needs its own, deliberately
     * divergent {@link ProjectResolver}/{@link FindProject} fakes built directly with {@code new
     * ProjectExportMcpTools(...)} instead of {@link #exportToolsOver}: that factory derives both from
     * the very same {@link ListProjects}, which can never disagree the way this test needs it to.
     */
    @Test
    void exportOfProjectOnlyReportsFailureInsteadOfThrowingWhenTheResolvedProjectIsNotFound() {
        ProjectResolver resolver = anchor -> new ResolvedProject(PROJECT_1, null);
        FindProject findProject = id -> Optional.empty();
        ProjectExportMcpTools tools = new ProjectExportMcpTools(
                listProjectsOf(), findProject, resolver, exporter, exportDir, null);

        String result = tools.export(contextAnchoredAt("/x"), true, null);

        assertThat(result).contains("FAILED to export (project is no longer registered)");
        assertThat(findTrigFiles(exportDir)).isEmpty();
    }

    /**
     * The default scope addresses no single project, so it reads no anchor: a call carrying one
     * through its transport header - every header-setting client does - still exports every
     * registered project, and a header anchor nobody registered does not turn the full backup into
     * an error. The explicit {@code projectAnchor} argument is left {@code null} here on purpose:
     * unlike the header, an explicit anchor given without {@code projectOnly=true} is rejected (see
     * {@link #exportOfAnExplicitAnchorWithoutProjectOnlyIsRejected()}), so this test only pins the
     * header path.
     */
    @Test
    void exportWithoutProjectOnlyIgnoresTheAnchorAndExportsEveryProject() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(
                        new Project(PROJECT_1, "first", List.of(pathAnchor("/x"))),
                        new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                exporter, exportDir, null);

        String result = tools.export(contextAnchoredAt("/nobody/registered/this"), false, null);

        assertThat(result).contains("# Exported first: ").contains("# Exported second: ");
        assertThat(findTrigFiles(exportDir)).hasSize(2);
    }

    /**
     * An explicit {@code projectAnchor} names one project, so applying it while the call otherwise
     * exports every registered project would silently do the opposite of what the caller most
     * likely meant. Rejected instead, whether {@code projectOnly} is left unset or is explicitly
     * {@code false} - in both cases no file is written.
     *
     * <p>Pins the message, not just the exception type - in two steps, because the whole point of
     * {@link ProjectExportMcpTools#ANCHOR_WITHOUT_PROJECT_ONLY_MESSAGE} is its wording and a rejection
     * degraded to a bare "invalid argument" would still satisfy {@code isInstanceOf} alone.
     * {@code hasMessage} against the constant pins that the guard still throws <em>that</em>
     * message (and is what its package-private visibility is for); asserting on the constant's own
     * content pins the part a caller acts on - both ways out, not just the rejection - which
     * comparing the constant to itself cannot.</p>
     */
    @Test
    void exportOfAnExplicitAnchorWithoutProjectOnlyIsRejected() {
        ProjectExportMcpTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/x")))),
                exporter, exportDir, null);

        assertThatThrownBy(() -> tools.export(null, null, "/x"))
                .as("projectOnly left unset")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ProjectExportMcpTools.ANCHOR_WITHOUT_PROJECT_ONLY_MESSAGE);
        assertThatThrownBy(() -> tools.export(null, false, "/x"))
                .as("projectOnly explicitly false")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ProjectExportMcpTools.ANCHOR_WITHOUT_PROJECT_ONLY_MESSAGE);
        assertThat(ProjectExportMcpTools.ANCHOR_WITHOUT_PROJECT_ONLY_MESSAGE)
                .as("the remedy names both ways out of the rejected combination")
                .contains("projectOnly=true", "omit projectAnchor");

        assertThat(findTrigFiles(exportDir)).isEmpty();
    }

    private static Anchor pathAnchor(String value) {
        return new Anchor(value, AnchorType.PATH);
    }

    private static String contentOf(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Path> findTrigFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".trig")).sorted().toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Structural fake implementing {@link ListProjects}, as used by {@code ProjectMcpToolsTest}. */
    private static final class FakeListProjects implements ListProjects {
        private final List<Project> all;

        FakeListProjects(List<Project> all) {
            this.all = all;
        }

        @Override
        public List<Project> list() {
            return all;
        }
    }
}
