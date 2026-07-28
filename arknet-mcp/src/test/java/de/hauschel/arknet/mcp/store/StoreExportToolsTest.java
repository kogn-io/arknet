// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        requirements.create(PROJECT_1, requirementTitled("Login"));
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
        assertThat(file.getParent().getFileName().toString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}");
        assertThat(file.getFileName().toString()).isEqualTo("arknet__store-export-tools-test-1.trig");
        assertThat(contentOf(file)).contains(FR_1_IRI).contains("\"Login\"");
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
            requirements.create(PROJECT_2, requirementTitled("Second"));

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
            requirements.create(PROJECT_2, requirementTitled("Second"));

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
     * #158's resilience lesson applied to the export path: the daemon's own export directory can
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
     * A project whose export cannot be written must not prevent the others from being written -
     * the whole point of exporting every project in one call is that one broken project cannot
     * take the others down with it. {@link StoreExportTools} now writes to a sibling
     * {@code .trig.tmp} path first and only moves it onto the final {@code .trig} name once the
     * export succeeds, so the write is blocked by pre-creating a directory at the {@code .tmp}
     * path rather than at the final one.
     */
    @Test
    void exportContinuesWithOtherProjectsWhenOneProjectsFileCannotBeWritten() {
        try {
            RequirementRepository requirements =
                    KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            requirements.create(PROJECT_2, requirementTitled("Second"));

            String timestamp = StoreExportTools.timestampFolderName();
            Path timestampDir = exportDir.resolve(timestamp);
            blockFileWithADirectory(timestampDir.resolve("broken__store-export-tools-test-1.trig.tmp"));

            StoreExportTools tools = new StoreExportTools(
                    listProjectsOf(
                            new Project(PROJECT_1, "broken", List.of(pathAnchor("/x"))),
                            new Project(PROJECT_2, "second", List.of(pathAnchor("/y")))),
                    new StoreExporter(lifecycle), exportDir, null);

            String result = tools.export();

            assertThat(result).contains("# Exported second: ");
            assertThat(result).contains("broken").contains("FAILED");
            assertThat(exportDir.resolve(timestamp).resolve("second__store-export-tools-test-2.trig")).exists();
            assertThat(exportDir.resolve(timestamp).resolve("broken__store-export-tools-test-1.trig")).doesNotExist();
            assertThat(exportDir.resolve(timestamp).resolve("broken__store-export-tools-test-1.trig.tmp"))
                    .doesNotExist();
        } finally {
            lifecycle.close(new DatasetId(PROJECT_2.value()));
        }
    }

    @Test
    void exportOfNoRegisteredProjectsRendersAPlaceholderInsteadOfAnEmptyString() {
        StoreExportTools tools = new StoreExportTools(listProjectsOf(), new StoreExporter(lifecycle), exportDir, null);

        assertThat(tools.export()).contains("no projects");
    }

    private static void blockFileWithADirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
