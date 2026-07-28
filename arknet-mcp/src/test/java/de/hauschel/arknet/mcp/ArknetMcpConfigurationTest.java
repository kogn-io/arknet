// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.prj.adapter.mcp.ProjectMcpTools;
import de.hauschel.arknet.prj.application.ProjectService;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
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

    /**
     * The project the hexagon-wiring tests below write into.
     *
     * <p>They used to pin it through the {@code arknet.workspace.id} property, which is gone with
     * the rest of the derived resolution path (ADR-016 decision 9). Nothing needs to replace it
     * here: these tests drive the application services directly, and a service takes the project
     * as a parameter. The property was only ever pinning the <em>resolver</em>, which these tests
     * never went through - see {@link #resolvesProjectIdByLookingUpARegisteredAnchor} for the one
     * that does.</p>
     */
    private static final ProjectId PROJECT = new ProjectId("test-project");

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    @Test
    void wiresRequirementsHexagonFromStoragePropertyAndRoundTrips() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RequirementMcpTools.class);

                    RequirementService service = context.getBean(RequirementService.class);
                    Requirement created = service.add(PROJECT,
                            new NewRequirement("Wired via composition root",
                                    "The composition root shall wire the requirements hexagon.",
                                    RequirementType.FUNCTIONAL, null, null, null,
                                    List.of("The requirement round-trips through the store")));

                    assertThat(created.code().value()).isEqualTo("FR-1");
                    assertThat(service.get(PROJECT, created.code()))
                            .isEqualTo(Optional.of(created));
                });
    }

    @Test
    void wiresUbiquitousLanguageHexagonFromStoragePropertyAndRoundTrips() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UbiquitousLanguageMcpTools.class);

                    TermService service = context.getBean(TermService.class);
                    Term created = service.add(PROJECT,
                            new NewTerm("Gutschrift", "Rueckerstattung eines bereits gezahlten Betrags.", null));

                    assertThat(created.code().value()).isEqualTo("TERM-1");
                    assertThat(service.get(PROJECT, created.code()))
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
     * derived. That the registered anchor resolves back to its own project is what this asserts
     * first: it is the property every other tool call now depends on for routing.</p>
     */
    @Test
    void wiresProjectHexagonAndWritesRegistryAndSelfDescriptionToDistinctDatasets() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir)
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

    /**
     * The migration path for data written before ADR-016, end to end - and the reason adoption is a
     * tool rather than a startup routine.
     *
     * <p>The setup is the real situation: a dataset already sits under the id {@code arknet}, the
     * slug the old resolver derived from a directory basename, and holds this project's whole model.
     * Nothing points at it. The server cannot repair that by itself - the slug is not invertible, so
     * it cannot know which of possibly several {@code .../arknet} directories once produced it, and
     * guessing is precisely what ADR-016 removes. The person at the keyboard supplies the missing
     * half by naming the dataset; the anchor arrives from their client as it always does.</p>
     *
     * <p>What must hold afterwards is that no data moved: the requirement written before adoption
     * reads back through the ordinary routing path, under the same business code, addressed only by
     * the anchor (ADR-016 decision 5 - pre-existing ids stay valid opaque values and simply gain the
     * anchors they were always reached by).</p>
     */
    @Test
    void adoptsAPreAdr016DatasetSoItsExistingDataStaysReachableByAnchor() {
        contextRunner
                .withPropertyValues("arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProjectId legacy = new ProjectId("arknet");
                    RequirementService requirements = context.getBean(RequirementService.class);
                    Requirement before = requirements.add(legacy,
                            new NewRequirement("Written before ADR-016",
                                    "The system shall keep data written under a derived id reachable.",
                                    RequirementType.FUNCTIONAL, null, null, null,
                                    List.of("The requirement survives adoption")));

                    ProjectService projects = context.getBean(ProjectService.class);
                    ProjectResolver resolver = context.getBean(ProjectResolver.class);

                    assertThat(projects.adoptable())
                            .as("a dataset nothing points at is offered for adoption")
                            .contains(legacy);
                    assertThatThrownBy(() -> resolver.resolve("/home/a/DEV/arknet"))
                            .as("and is unreachable until somebody claims it")
                            .isInstanceOf(UnresolvedProjectAnchorException.class);

                    Project adopted = projects.adopt(
                            legacy, "arknet", new Anchor("/home/a/DEV/arknet", AnchorType.PATH));

                    assertThat(adopted.id()).as("the dataset keeps its identity").isEqualTo(legacy);
                    assertThat(projects.adoptable()).doesNotContain(legacy);

                    ProjectId routed = resolver.resolve("/home/a/DEV/arknet");
                    assertThat(requirements.get(routed, before.code()))
                            .as("the data written before adoption reads back through the routing path")
                            .isEqualTo(Optional.of(before));
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
     * The switch-over itself, wired end to end: the {@link ProjectResolver} bean every model
     * hexagon routes on answers by looking the anchor up in the registry.
     *
     * <p>Three assertions, one per property ADR-016 turns on. A registered anchor resolves to
     * <em>its own</em> project, so a call lands where its data is. An unregistered anchor fails
     * instead of resolving, so a typo or a copied client config cannot silently open a second
     * store. And no anchor at all fails too, rather than falling back to the daemon's working
     * directory - which is what the deleted property used to configure.</p>
     *
     * <p>The second assertion is the one that closes issue #175 at this level: the two anchors
     * differ only in their parent directory and share a basename. Under the old resolver both
     * slugged to {@code arknet} and hit the same dataset; here the unregistered one has no answer
     * at all.</p>
     */
    @Test
    void resolvesProjectIdByLookingUpARegisteredAnchor() {
        contextRunner
                .withPropertyValues("arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProjectService projects = context.getBean(ProjectService.class);
                    Project registered = projects.register(
                            "arknet", new Anchor("/home/a/DEV/arknet", AnchorType.PATH));

                    ProjectResolver resolver = context.getBean(ProjectResolver.class);

                    assertThat(resolver.resolve("/home/a/DEV/arknet"))
                            .as("a registered anchor resolves to its own project")
                            .isEqualTo(registered.id());
                    assertThatThrownBy(() -> resolver.resolve("/home/b/other/arknet"))
                            .as("an identically named directory elsewhere is unknown, not the same "
                                    + "project - the collision of issue #175")
                            .isInstanceOf(UnresolvedProjectAnchorException.class)
                            .hasMessageContaining("/home/b/other/arknet");
                    assertThatThrownBy(() -> resolver.resolve(null))
                            .as("no anchor is an error, never the server's own working directory")
                            .isInstanceOf(UnresolvedProjectAnchorException.class);
                });
    }
}
