// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.prj.adapter.mcp.ProjectMcpTools;
import de.hauschel.arknet.prj.application.ProjectService;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.ul.adapter.mcp.UbiquitousLanguageMcpTools;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.Term;

/**
 * Verifies the composition root wiring in isolation: {@link ArknetMcpConfiguration}
 * must assemble the requirements hexagon (kognio-rdf repository via factory ->
 * service -> MCP tools) from just the {@code arknet.rdf.storage} property, and the
 * wired stack must actually persist and read a requirement back.
 *
 * <p>Uses {@link ApplicationContextRunner} to load only this configuration - the full
 * Spring AI MCP server auto-configuration and its HTTP transport are out of scope here
 * (their tool registration was established in #27).</p>
 */
class ArknetMcpConfigurationTest {

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    @Test
    void wiresRequirementsHexagonFromStoragePropertyAndRoundTrips() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir,
                        "arknet.workspace.id=test-workspace")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RequirementMcpTools.class);

                    RequirementService service = context.getBean(RequirementService.class);
                    Requirement created = service.add(ProjectId.DEFAULT,
                            new NewRequirement("Wired via composition root",
                                    "The composition root shall wire the requirements hexagon.",
                                    RequirementType.FUNCTIONAL, null, null, null,
                                    List.of("The requirement round-trips through the store")));

                    assertThat(created.code().value()).isEqualTo("FR-1");
                    assertThat(service.get(ProjectId.DEFAULT, created.code()))
                            .isEqualTo(Optional.of(created));
                });
    }

    @Test
    void wiresUbiquitousLanguageHexagonFromStoragePropertyAndRoundTrips() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir,
                        "arknet.workspace.id=test-workspace")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UbiquitousLanguageMcpTools.class);

                    TermService service = context.getBean(TermService.class);
                    Term created = service.add(ProjectId.DEFAULT,
                            new NewTerm("Gutschrift", "Rueckerstattung eines bereits gezahlten Betrags.", null));

                    assertThat(created.code().value()).isEqualTo("TERM-1");
                    assertThat(service.get(ProjectId.DEFAULT, created.code()))
                            .isEqualTo(Optional.of(created));
                });
    }

    /**
     * The project hexagon (ADR-016) round-trips through the composition root, and its two write
     * targets land where they must: the registry in the reserved system dataset, the
     * self-description in the project's <em>own</em> dataset. Both go through the same shared
     * {@link io.kogn.rdf.dataset.hosting.DatasetLifecycle} bean as every other hexagon, so this
     * also pins that the reserved dataset coexists with the project datasets in one store rather
     * than needing a second one.
     *
     * <p>Wired without a {@link ProjectResolver} on purpose - the anchor is looked up, never
     * derived - so this test deliberately pins {@code arknet.workspace.id} to a value that has
     * nothing to do with the anchor below: were the project hexagon secretly routed through the
     * derived-workspace path, the anchor lookup would not survive it.</p>
     */
    @Test
    void wiresProjectHexagonAndWritesRegistryAndSelfDescriptionToDistinctDatasets() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir,
                        "arknet.workspace.id=irrelevant-for-the-registry")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProjectMcpTools.class);

                    ProjectService service = context.getBean(ProjectService.class);
                    Anchor anchor = new Anchor("/home/somebody/DEV/arknet", AnchorType.PATH);
                    Project registered = service.register("arknet", anchor);

                    assertThat(service.resolve(anchor))
                            .as("the very anchor that was registered must resolve back to its project")
                            .isEqualTo(registered);
                    assertThat(service.list()).containsExactly(registered);

                    DatasetLifecycle lifecycle = context.getBean(DatasetLifecycle.class);
                    assertThat(graphSize(lifecycle, ProjectId.RESERVED_SYSTEM_DATASET,
                            ArkprjVocabulary.REGISTRY_GRAPH))
                            .as("the registry belongs in the reserved system dataset")
                            .isPositive();
                    assertThat(graphSize(lifecycle, registered.id().value(),
                            ArkprjVocabulary.IDENTITY_GRAPH))
                            .as("and the self-description in the project's own dataset (ADR-016 point 7), "
                                    + "so a restored backup carries its identity with it")
                            .isPositive();
                    assertThat(graphSize(lifecycle, registered.id().value(),
                            ArkprjVocabulary.REGISTRY_GRAPH))
                            .as("the registry must not be duplicated into the project's dataset")
                            .isZero();
                });
    }

    /** Counts the statements of one named graph in one dataset, straight from the store. */
    private static long graphSize(DatasetLifecycle lifecycle, String datasetId, String graphIri) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(datasetId))) {
            return handle.sparqlQuery()
                    .select("SELECT ?s ?p ?o WHERE { GRAPH <" + graphIri + "> { ?s ?p ?o } }")
                    .count();
        }
    }

    /**
     * With an explicit {@code arknet.workspace.id} pinned, the per-call {@link ProjectResolver}
     * (issue #137) resolves every call to that fixed workspace regardless of the call's origin
     * directory - the override wins over any directory-derived name.
     */
    @Test
    void resolvesProjectIdFromExplicitProperty() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir,
                        "arknet.workspace.id=noistill")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProjectResolver resolver = context.getBean(ProjectResolver.class);
                    assertThat(resolver.resolve(null)).isEqualTo(new ProjectId("noistill"));
                    assertThat(resolver.resolve("/some/other/dir")).isEqualTo(new ProjectId("noistill"));
                });
    }
}
