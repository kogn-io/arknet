package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import de.hauschel.arknet.kernel.WorkspaceId;
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
 * Spring AI MCP server auto-configuration and stdio transport are out of scope here
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
                    Requirement created = service.add(WorkspaceId.DEFAULT,
                            new NewRequirement("Wired via composition root",
                                    "The composition root shall wire the requirements hexagon.",
                                    RequirementType.FUNCTIONAL, null, null, null));

                    assertThat(created.id().value()).isEqualTo("FR-1");
                    assertThat(service.get(WorkspaceId.DEFAULT, created.id()))
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
                    Term created = service.add(WorkspaceId.DEFAULT,
                            new NewTerm("Gutschrift", "Rueckerstattung eines bereits gezahlten Betrags.", null));

                    assertThat(created.id().value()).isEqualTo("TERM-1");
                    assertThat(service.get(WorkspaceId.DEFAULT, created.id()))
                            .isEqualTo(Optional.of(created));
                });
    }

    @Test
    void resolvesWorkspaceIdFromExplicitProperty() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir,
                        "arknet.workspace.id=noistill")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(WorkspaceId.class)
                            .isEqualTo(new WorkspaceId("noistill"));
                });
    }
}
