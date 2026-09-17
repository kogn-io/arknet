// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.ArknetMcpConfiguration;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.prj.application.ProjectService;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;

/**
 * End-to-end tests of {@code text_search} (kogn-io/arknet#594 Part 1) against a real kognio-rdf
 * store, seeded through the real application services - the same fixture style as {@code
 * TraceabilityMcpToolsTest}, proving {@link TextSearchMcpTools} is wired into
 * {@link ArknetMcpConfiguration} and actually finds what {@code req_add}/{@code uc_add} wrote.
 */
class TextSearchMcpToolsTest {

    private static final String ANCHOR = "/home/dev/projects/text-search-tools-test";

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    private ApplicationContextRunner runner() {
        return contextRunner.withPropertyValues("arknet.rdf.storage=" + storageDir.toAbsolutePath());
    }

    private static ProjectId registerProject(final org.springframework.context.ApplicationContext context) {
        return context.getBean(ProjectService.class)
                .register("text-search-tools-test", new Anchor(ANCHOR, AnchorType.PATH), null, null, null, null)
                .id();
    }

    @Test
    void hitsOnTitleAndRationaleAreGroupedUnderTheSameResource() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId project = registerProject(context);
            RequirementService requirements = context.getBean(RequirementService.class);
            TextSearchMcpTools tools = context.getBean(TextSearchMcpTools.class);

            Requirement fr1 = requirements.add(project, new NewRequirement("Login form",
                    "The system shall authenticate a user.", "Users must be able to log in securely.",
                    RequirementType.FUNCTIONAL, null, null, List.of("Nothing to do with the search term"), null),
                    "en");

            String report = tools.textSearch(null, "log", ANCHOR);

            assertThat(report).contains("## " + fr1.code().value());
            assertThat(report).contains("dcterms:title");
            assertThat(report).contains("arkreq:rationale");
            assertThat(Arrays.stream(report.split("\n"))
                    .filter(line -> line.startsWith("## " + fr1.code().value())).count())
                    .as("both matches must land under one group, not two")
                    .isEqualTo(1);
        });
    }

    @Test
    void matchingIsCaseInsensitive() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId project = registerProject(context);
            RequirementService requirements = context.getBean(RequirementService.class);
            TextSearchMcpTools tools = context.getBean(TextSearchMcpTools.class);

            Requirement fr1 = requirements.add(project, new NewRequirement("Login",
                    "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL, null, null,
                    List.of("Login succeeds with valid credentials"), null), "en");

            String report = tools.textSearch(null, "LOGIN", ANCHOR);

            assertThat(report).contains(fr1.code().value());
        });
    }

    @Test
    void reportsTheLanguageTagOfTheMatchedLiteral() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId project = registerProject(context);
            RequirementService requirements = context.getBean(RequirementService.class);
            TextSearchMcpTools tools = context.getBean(TextSearchMcpTools.class);

            requirements.add(project, new NewRequirement("Anmeldung",
                    "Das System muss einen Benutzer authentifizieren.", null, RequirementType.FUNCTIONAL, null, null,
                    List.of("Anmeldung gelingt mit gueltigen Zugangsdaten"), null), "de");

            String report = tools.textSearch(null, "Anmeldung", ANCHOR);

            assertThat(report).contains("[de]");
        });
    }

    @Test
    void aStepMatchIsReportedUnderItsOwningUseCase() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId project = registerProject(context);
            UseCaseService useCases = context.getBean(UseCaseService.class);
            RoleService roles = context.getBean(RoleService.class);
            TextSearchMcpTools tools = context.getBean(TextSearchMcpTools.class);

            RoleDetail role = roles.add(project, new NewRole("Customer", null, List.of(), "en"), "en");
            UseCase uc1 = useCases.add(project, new NewUseCase("Log in", "Customer authenticates", null, null,
                    role.role().code().value(), List.of(), null, null,
                    List.of(new NewStep(1, "Customer enters a zzzcredential token", List.of())),
                    List.of(), null), "en");

            String report = tools.textSearch(null, "zzzcredential", ANCHOR);

            assertThat(report).contains("## " + uc1.code().value());
            assertThat(report).contains("arkreq:stepText");
            assertThat(report).containsPattern("via arkreq:\\w*[Ss]tep");
        });
    }

    @Test
    void anAcceptanceCriterionMatchIsReportedUnderItsOwningRequirement() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId project = registerProject(context);
            RequirementService requirements = context.getBean(RequirementService.class);
            TextSearchMcpTools tools = context.getBean(TextSearchMcpTools.class);

            Requirement fr1 = requirements.add(project, new NewRequirement("Login",
                    "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL, null, null,
                    List.of("Login succeeds with a zzzvalidcredential"), null), "en");

            String report = tools.textSearch(null, "zzzvalidcredential", ANCHOR);

            assertThat(report).contains("## " + fr1.code().value());
            assertThat(report).contains("arkreq:criterionText");
            assertThat(report).contains("via arkreq:acceptanceCriterion");
        });
    }

    @Test
    void noHitsReportsCleanlyInsteadOfAnEmptyGroupList() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            registerProject(context);
            TextSearchMcpTools tools = context.getBean(TextSearchMcpTools.class);

            String report = tools.textSearch(null, "nothing-matches-this-zzz", ANCHOR);

            assertThat(report).contains("No hits");
        });
    }

    @Test
    void emptyQueryIsRejectedWithAHelpfulMessage() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            registerProject(context);
            TextSearchMcpTools tools = context.getBean(TextSearchMcpTools.class);

            assertThatThrownBy(() -> tools.textSearch(null, "", ANCHOR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
            assertThatThrownBy(() -> tools.textSearch(null, "   ", ANCHOR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        });
    }

    @Test
    void declaresExactlyOneReadOnlyTool() {
        List<McpTool> tools = Arrays.stream(TextSearchMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .toList();

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("text_search");
            assertThat(tool.annotations().readOnlyHint()).isTrue();
        });
    }

    /** Schema-budget discipline (kogn-io/arknet#522): only the description reliably reaches an agent. */
    @Test
    void statesInItsOwnDescriptionWhatItSeesThatImpactAnalysisDoesNot() {
        McpTool tool = Arrays.stream(TextSearchMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .findFirst()
                .orElseThrow();

        assertThat(tool.description()).contains("impact_analysis");
    }
}
