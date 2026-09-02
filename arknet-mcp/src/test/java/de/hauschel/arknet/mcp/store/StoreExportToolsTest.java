// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.prj.application.port.in.FindProject;
import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Unit tests for {@link StoreExportTools}: by default {@code project_export} writes every
 * registered project's complete TriG export into a timestamp subdirectory of a configurable base
 * directory; with {@code projectOnly=true} it narrows to just the one project the call addresses
 * through its anchor. {@link ListProjects} is a structural fake (as {@code ProjectMcpToolsTest}
 * does for its in-ports); {@link StoreExporter} runs for real over a real kognio-rdf store, since
 * it is the collaborator under test alongside the file-writing orchestration.
 */
class StoreExportToolsTest {

    private static final ProjectId PROJECT_1 = new ProjectId("store-export-tools-test-1");
    private static final ProjectId PROJECT_2 = new ProjectId("store-export-tools-test-2");
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/store-export-tools-test-fr-1";

    @TempDir
    Path storageDir;

    @TempDir
    Path exportDir;

    private DatasetLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        RequirementRepository requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        requirements.create(PROJECT_1, requirementTitled("Login"), null);
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(PROJECT_1.value()));
    }

    private static Requirement requirementTitled(String title) {
        return new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), title,
                "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
    }

    private static FakeListProjects listProjectsOf(Project... projects) {
        return new FakeListProjects(List.of(projects));
    }

    @Test
    void exportWritesEachProjectToAFileUnderATimestampSubdirectoryOfTheFallbackDir() {
        StoreExportTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/home/f/DEV/arknet")))),
                new StoreExporter(lifecycle), exportDir, null);

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
     * {@link StoreExportTools#timestampFolderName()} now disambiguates with an appended call
     * sequence number, so even two calls issued back to back land in distinct subdirectories.
     */
    @Test
    void exportOfTwoRapidCallsWritesToDistinctSubdirectories() {
        StoreExportTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/home/f/DEV/arknet")))),
                new StoreExporter(lifecycle), exportDir, null);

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
        StoreExportTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet / dev (main)", List.of(pathAnchor("/x")))),
                new StoreExporter(lifecycle), exportDir, null);

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
        try {
            RequirementRepository requirements =
                    KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            requirements.create(PROJECT_2, requirementTitled("Second"), null);

            StoreExportTools tools = exportToolsOver(
                    listProjectsOf(
                            new Project(PROJECT_1, "team/main", List.of(pathAnchor("/x"))),
                            new Project(PROJECT_2, "team main", List.of(pathAnchor("/y")))),
                    new StoreExporter(lifecycle), exportDir, null);

            String result = tools.export(null, null, null);

            assertThat(result).contains("# Exported team/main: ").contains("# Exported team main: ");
            List<Path> written = findTrigFiles(exportDir);
            assertThat(written.stream().map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(
                            "team_main__" + FileNameSanitizer.uniqueSegment(PROJECT_1.value()) + ".trig",
                            "team_main__" + FileNameSanitizer.uniqueSegment(PROJECT_2.value()) + ".trig");
        } finally {
            lifecycle.close(new DatasetId(PROJECT_2.value()));
        }
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
        try {
            RequirementRepository requirements =
                    KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            requirements.create(idA, requirementTitled("First"), null);
            requirements.create(idB, requirementTitled("Second"), null);

            assertThat(FileNameSanitizer.sanitize(idA.value())).isEqualTo(FileNameSanitizer.sanitize(idB.value()));

            StoreExportTools tools = exportToolsOver(
                    listProjectsOf(
                            new Project(idA, "team", List.of(pathAnchor("/x"))),
                            new Project(idB, "team", List.of(pathAnchor("/y")))),
                    new StoreExporter(lifecycle), exportDir, null);

            tools.export(null, null, null);

            List<Path> written = findTrigFiles(exportDir);
            assertThat(written.stream().map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(
                            "team__" + FileNameSanitizer.uniqueSegment(idA.value()) + ".trig",
                            "team__" + FileNameSanitizer.uniqueSegment(idB.value()) + ".trig");
        } finally {
            lifecycle.close(new DatasetId(idA.value()));
            lifecycle.close(new DatasetId(idB.value()));
        }
    }

    /**
     * Two registered projects both land under the very same timestamp subdirectory - one export
     * call, one point in time, not one subdirectory per project.
     */
    @Test
    void exportWritesAllRegisteredProjectsUnderTheSameTimestampSubdirectory() {
        try {
            RequirementRepository requirements =
                    KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            requirements.create(PROJECT_2, requirementTitled("Second"), null);

            StoreExportTools tools = exportToolsOver(
                    listProjectsOf(
                            new Project(PROJECT_1, "first", List.of(pathAnchor("/x"))),
                            new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                    new StoreExporter(lifecycle), exportDir, null);

            tools.export(null, null, null);

            List<Path> written = findTrigFiles(exportDir);
            assertThat(written).hasSize(2);
            assertThat(written.get(0).getParent()).isEqualTo(written.get(1).getParent());
        } finally {
            lifecycle.close(new DatasetId(PROJECT_2.value()));
        }
    }

    /**
     * The resilience lesson applied to the export path: the daemon's own export directory can
     * be a container-internal mount the calling agent cannot reach, so the digest must report the
     * host-reachable equivalent while the write itself is unaffected.
     */
    @Test
    void exportReportsTheHostDirInsteadOfTheFallbackDirWhenSet(@TempDir Path hostDir) {
        StoreExportTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/home/f/DEV/arknet")))),
                new StoreExporter(lifecycle), exportDir, hostDir);

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
     * StoreExportTools#timestampFolderName()}), and that name is unpredictable before the call
     * (current time plus a process-wide call sequence). {@link SecondElementTriggersSideEffect}
     * works around that: it defers creating the blocking directory until the stream that {@link
     * StoreExportTools#export()} iterates actually reaches the second project - by then the first
     * project's {@code exportOne} has already run to completion (streams are pulled one element at
     * a time), so the now-created timestamp subdirectory can be discovered on disk and the block
     * placed exactly at the second project's own {@code .tmp} path.</p>
     */
    @Test
    void exportOfOneProjectsWriteFailureDoesNotPreventTheOtherFromBeingExported(@TempDir Path root)
            throws Exception {
        RequirementRepository requirements =
                KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        requirements.create(PROJECT_2, requirementTitled("Second"), null);
        try {
            Project working = new Project(PROJECT_1, "working", List.of(pathAnchor("/x")));
            Project broken = new Project(PROJECT_2, "broken", List.of(pathAnchor("/y")));
            String brokenTmpFileName = "broken__" + FileNameSanitizer.uniqueSegment(PROJECT_2.value()) + ".trig.tmp";

            List<Project> workingThenBroken = new SecondElementTriggersSideEffect<>(
                    List.of(working, broken),
                    () -> blockTmpFileOfTheOnlyTimestampSubdirectory(root, brokenTmpFileName));
            StoreExportTools tools =
                    exportToolsOver(() -> workingThenBroken, new StoreExporter(lifecycle), root, null);

            String result = tools.export(null, null, null);

            assertThat(result).contains("# Exported working: ").doesNotContain("# Exported working: FAILED");
            assertThat(result).contains("# Exported broken: FAILED to write to");
            List<Path> written = findTrigFiles(root);
            assertThat(written).hasSize(1);
            assertThat(written.get(0).getFileName().toString())
                    .isEqualTo("working__" + FileNameSanitizer.uniqueSegment(PROJECT_1.value()) + ".trig");
        } finally {
            lifecycle.close(new DatasetId(PROJECT_2.value()));
        }
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
     * Regression test for issue #146: a failure that happens while {@link StoreExporter#exportTrig}
     * <em>reads</em> from the store (acquiring an already-open dataset) must not be reported under
     * the "FAILED to write" prefix that used to cover every failure regardless of where it
     * originated. A second, independently constructed {@link DatasetLifecycle} over the same
     * {@code storageDir} holding {@link #PROJECT_1}'s dataset open reproduces a genuine RDF4J lock
     * conflict - the exact scenario the issue names.
     */
    @Test
    void exportOfADatasetLockedByAnotherLifecycleDoesNotClaimAWriteFailure() {
        DatasetHandle heldOpen = lifecycle.acquire(new DatasetId(PROJECT_1.value()));
        try {
            DatasetLifecycle competingLifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
            StoreExportTools tools = exportToolsOver(
                    listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/x")))),
                    new StoreExporter(competingLifecycle), exportDir, null);

            String result = tools.export(null, null, null);

            assertThat(result).contains("# Exported arknet: FAILED to export");
            assertThat(result).doesNotContain("FAILED to write");
        } finally {
            heldOpen.close();
        }
    }

    @Test
    void exportOfNoRegisteredProjectsRendersAPlaceholderInsteadOfAnEmptyString() {
        StoreExportTools tools = exportToolsOver(listProjectsOf(), new StoreExporter(lifecycle), exportDir, null);

        assertThat(tools.export(null, null, null)).contains("no projects");
    }

    /**
     * Builds the tool over one {@link ListProjects}, deriving the two collaborators the
     * {@code projectOnly=true} scope needs from that very same list: {@link FindProject} looks a
     * project up by id, and the {@link ProjectResolver} matches an anchor against the projects'
     * own registered anchors, raising {@link UnresolvedProjectAnchorException} for anything else -
     * the registry behaviour ADR-016 decision 3 prescribes, in the smallest fake that has it.
     */
    private static StoreExportTools exportToolsOver(
            ListProjects list, StoreExporter exporter, Path exportDir, Path hostDir) {
        FindProject findProject = id -> list.list().stream().filter(p -> p.id().equals(id)).findFirst();
        ProjectResolver resolver = anchor -> projectAnchoredAt(list, anchor)
                .map(project -> new ResolvedProject(project.id(), null))
                .orElseThrow(() -> new UnresolvedProjectAnchorException(anchor, "no project registered for " + anchor));
        return new StoreExportTools(list, findProject, resolver, exporter, exportDir, hostDir);
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
        McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        when(context.transportContext())
                .thenReturn(McpTransportContext.create(Map.of(ProjectResolver.ANCHOR_KEY, anchor)));
        return context;
    }

    /**
     * The {@code projectOnly=true} scope over the header path: only the project this call
     * addresses through its transport anchor is exported, even though a second project is
     * registered and the default scope would have written both.
     */
    @Test
    void exportOfProjectOnlyWritesOnlyTheProjectTheTransportAnchorAddresses() {
        try {
            RequirementRepository requirements =
                    KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            requirements.create(PROJECT_2, requirementTitled("Second"), null);

            StoreExportTools tools = exportToolsOver(
                    listProjectsOf(
                            new Project(PROJECT_1, "first", List.of(pathAnchor("/x"))),
                            new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                    new StoreExporter(lifecycle), exportDir, null);

            String result = tools.export(contextAnchoredAt("/y"), true, null);

            assertThat(result).contains("# Exported second: ").doesNotContain("# Exported first: ");
            List<Path> written = findTrigFiles(exportDir);
            assertThat(written).hasSize(1);
            assertThat(written.get(0).getFileName().toString())
                    .isEqualTo("second__" + FileNameSanitizer.uniqueSegment(PROJECT_2.value()) + ".trig");
        } finally {
            lifecycle.close(new DatasetId(PROJECT_2.value()));
        }
    }

    /**
     * The other delivery path ADR-016 decision 2 keeps open to every client: the explicit
     * {@code projectAnchor} parameter routes the same narrowed export for a client that cannot set
     * the header, and takes precedence over a header naming a different project.
     */
    @Test
    void exportOfProjectOnlyRoutesByTheExplicitAnchorParameterOverTheHeader() {
        try {
            RequirementRepository requirements =
                    KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            requirements.create(PROJECT_2, requirementTitled("Second"), null);

            StoreExportTools tools = exportToolsOver(
                    listProjectsOf(
                            new Project(PROJECT_1, "first", List.of(pathAnchor("/x"))),
                            new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                    new StoreExporter(lifecycle), exportDir, null);

            assertThat(tools.export(null, true, "/y")).contains("# Exported second: ");
            assertThat(tools.export(contextAnchoredAt("/x"), true, "/y"))
                    .as("the explicit parameter is used INSTEAD of the header, not merged with it")
                    .contains("# Exported second: ").doesNotContain("# Exported first: ");
        } finally {
            lifecycle.close(new DatasetId(PROJECT_2.value()));
        }
    }

    /**
     * ADR-016 decision 3 on the narrowed scope: narrowing the export to "this project" without a
     * registered anchor has no defensible fall-back - least of all the full export the caller just
     * opted out of - so it fails the same way every read tool does.
     */
    @Test
    void exportOfProjectOnlyWithoutARegisteredAnchorFailsInsteadOfExportingEverything() {
        StoreExportTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/x")))),
                new StoreExporter(lifecycle), exportDir, null);

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
     * StoreExportTools(...)} instead of {@link #exportToolsOver}: that factory derives both from
     * the very same {@link ListProjects}, which can never disagree the way this test needs it to.
     */
    @Test
    void exportOfProjectOnlyReportsFailureInsteadOfThrowingWhenTheResolvedProjectIsNotFound() {
        ProjectResolver resolver = anchor -> new ResolvedProject(PROJECT_1, null);
        FindProject findProject = id -> Optional.empty();
        StoreExportTools tools = new StoreExportTools(
                listProjectsOf(), findProject, resolver, new StoreExporter(lifecycle), exportDir, null);

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
        try {
            RequirementRepository requirements =
                    KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            requirements.create(PROJECT_2, requirementTitled("Second"), null);

            StoreExportTools tools = exportToolsOver(
                    listProjectsOf(
                            new Project(PROJECT_1, "first", List.of(pathAnchor("/x"))),
                            new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                    new StoreExporter(lifecycle), exportDir, null);

            String result = tools.export(contextAnchoredAt("/nobody/registered/this"), false, null);

            assertThat(result).contains("# Exported first: ").contains("# Exported second: ");
            assertThat(findTrigFiles(exportDir)).hasSize(2);
        } finally {
            lifecycle.close(new DatasetId(PROJECT_2.value()));
        }
    }

    /**
     * An explicit {@code projectAnchor} names one project, so applying it while the call otherwise
     * exports every registered project would silently do the opposite of what the caller most
     * likely meant. Rejected instead, whether {@code projectOnly} is left unset or is explicitly
     * {@code false} - in both cases no file is written.
     */
    @Test
    void exportOfAnExplicitAnchorWithoutProjectOnlyIsRejected() {
        StoreExportTools tools = exportToolsOver(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/x")))),
                new StoreExporter(lifecycle), exportDir, null);

        assertThatThrownBy(() -> tools.export(null, null, "/x"))
                .as("projectOnly left unset")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.export(null, false, "/x"))
                .as("projectOnly explicitly false")
                .isInstanceOf(IllegalArgumentException.class);

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
