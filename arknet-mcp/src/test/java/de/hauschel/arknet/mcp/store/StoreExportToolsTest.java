// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Unit tests for {@link StoreExportTools}: {@code project_export} writes every registered
 * project's complete TriG export into a timestamp subdirectory of a configurable base
 * directory. {@link ListProjects} is a structural fake (as {@code ProjectMcpToolsTest} does for
 * its in-ports); {@link StoreExporter} runs for real over a real kognio-rdf store, since it is
 * the collaborator under test alongside the file-writing orchestration.
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
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of("Login succeeds with valid credentials"));
    }

    private static FakeListProjects listProjectsOf(Project... projects) {
        return new FakeListProjects(List.of(projects));
    }

    @Test
    void exportWritesEachProjectToAFileUnderATimestampSubdirectoryOfTheFallbackDir() {
        StoreExportTools tools = new StoreExportTools(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/home/f/DEV/arknet")))),
                new StoreExporter(lifecycle), exportDir, null);

        String result = tools.export();

        assertThat(result).contains("# Exported arknet: ");
        List<Path> written = findTrigFiles(exportDir);
        assertThat(written).hasSize(1);
        Path file = written.get(0);
        assertThat(file.getParent().getFileName().toString())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-\\d+");
        assertThat(file.getFileName().toString()).isEqualTo("arknet__store-export-tools-test-1.trig");
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
        StoreExportTools tools = new StoreExportTools(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/home/f/DEV/arknet")))),
                new StoreExporter(lifecycle), exportDir, null);

        tools.export();
        tools.export();

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
        StoreExportTools tools = new StoreExportTools(
                listProjectsOf(new Project(PROJECT_1, "arknet / dev (main)", List.of(pathAnchor("/x")))),
                new StoreExporter(lifecycle), exportDir, null);

        tools.export();

        List<Path> written = findTrigFiles(exportDir);
        assertThat(written).hasSize(1);
        assertThat(written.get(0).getFileName().toString())
                .isEqualTo("arknet___dev__main___store-export-tools-test-1.trig");
    }

    /**
     * Two projects whose labels sanitize to the identical filesystem stem ("team/main" and
     * "team main" both become "team_main") must not collide on disk - the project id is
     * guaranteed unique and is appended to the filename precisely so one export can never
     * silently overwrite the other.
     */
    @Test
    void exportOfTwoProjectsWithCollidingSanitizedLabelsWritesDistinctFiles() {
        try {
            RequirementRepository requirements =
                    KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            requirements.create(PROJECT_2, requirementTitled("Second"), null);

            StoreExportTools tools = new StoreExportTools(
                    listProjectsOf(
                            new Project(PROJECT_1, "team/main", List.of(pathAnchor("/x"))),
                            new Project(PROJECT_2, "team main", List.of(pathAnchor("/y")))),
                    new StoreExporter(lifecycle), exportDir, null);

            String result = tools.export();

            assertThat(result).contains("# Exported team/main: ").contains("# Exported team main: ");
            List<Path> written = findTrigFiles(exportDir);
            assertThat(written.stream().map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(
                            "team_main__store-export-tools-test-1.trig",
                            "team_main__store-export-tools-test-2.trig");
        } finally {
            lifecycle.close(new DatasetId(PROJECT_2.value()));
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

            StoreExportTools tools = new StoreExportTools(
                    listProjectsOf(
                            new Project(PROJECT_1, "first", List.of(pathAnchor("/x"))),
                            new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                    new StoreExporter(lifecycle), exportDir, null);

            tools.export();

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
        StoreExportTools tools = new StoreExportTools(
                listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/home/f/DEV/arknet")))),
                new StoreExporter(lifecycle), exportDir, hostDir);

        String result = tools.export();

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
            String brokenTmpFileName = "broken__" + PROJECT_2.value() + ".trig.tmp";

            List<Project> workingThenBroken = new SecondElementTriggersSideEffect<>(
                    List.of(working, broken),
                    () -> blockTmpFileOfTheOnlyTimestampSubdirectory(root, brokenTmpFileName));
            StoreExportTools tools =
                    new StoreExportTools(() -> workingThenBroken, new StoreExporter(lifecycle), root, null);

            String result = tools.export();

            assertThat(result).contains("# Exported working: ").doesNotContain("# Exported working: FAILED");
            assertThat(result).contains("# Exported broken: FAILED to write to");
            List<Path> written = findTrigFiles(root);
            assertThat(written).hasSize(1);
            assertThat(written.get(0).getFileName().toString())
                    .isEqualTo("working__store-export-tools-test-1.trig");
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
            StoreExportTools tools = new StoreExportTools(
                    listProjectsOf(new Project(PROJECT_1, "arknet", List.of(pathAnchor("/x")))),
                    new StoreExporter(competingLifecycle), exportDir, null);

            String result = tools.export();

            assertThat(result).contains("# Exported arknet: FAILED to export");
            assertThat(result).doesNotContain("FAILED to write");
        } finally {
            heldOpen.close();
        }
    }

    @Test
    void exportOfNoRegisteredProjectsRendersAPlaceholderInsteadOfAnEmptyString() {
        StoreExportTools tools = new StoreExportTools(listProjectsOf(), new StoreExporter(lifecycle), exportDir, null);

        assertThat(tools.export()).contains("no projects");
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
