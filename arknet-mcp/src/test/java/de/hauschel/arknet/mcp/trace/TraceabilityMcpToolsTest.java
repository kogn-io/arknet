// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.mcp.ArknetMcpConfiguration;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;

/**
 * End-to-end tests of the three traceability tools (issue #131) against a real kognio-rdf store
 * shared by all three bounded contexts - proving {@link TraceabilityMcpTools} is wired into
 * {@link ArknetMcpConfiguration} and actually reads what {@code req_add}/{@code term_add}/{@code
 * uc_add} wrote. Seeds data exclusively through the real application services (the same path
 * {@link de.hauschel.arknet.mcp.CrossBoundedContextStoreWiringTest} uses), never via raw
 * triples.
 */
class TraceabilityMcpToolsTest {

    private static final WorkspaceId WS = new WorkspaceId("trace-tools-test");

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    /**
     * {@code storageDir} is only injected after field initializers run, so the workspace/storage
     * property values are appended here rather than in the {@link #contextRunner} field
     * (mirrors {@link de.hauschel.arknet.mcp.CrossBoundedContextStoreWiringTest}).
     */
    private ApplicationContextRunner runner() {
        return contextRunner.withPropertyValues(
                "arknet.rdf.storage=" + storageDir.toAbsolutePath(),
                "arknet.workspace.id=" + WS.value());
    }

    @Test
    void traceMatrixReportsUsedTermsAndRealisingUseCasesPerRequirement() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            RequirementService requirements = context.getBean(RequirementService.class);
            TermService terms = context.getBean(TermService.class);
            UseCaseService useCases = context.getBean(UseCaseService.class);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            Term term = terms.add(WS, new NewTerm("Anmeldung", "The act of proving one's identity.", null));
            terms.add(WS, new NewTerm("Customer", "A person placing an order.",
                    new ActorFacet(ActorKind.HUMAN, "orderer")));

            Requirement fr1 = requirements.add(WS, new NewRequirement("Login",
                    "The system shall authenticate a user.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Login succeeds with valid credentials")));
            requirements.linkTerm(WS, fr1.code(), term.code().value());
            Requirement fr2 = requirements.add(WS, new NewRequirement("Logout",
                    "The system shall let a user log out.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Logout succeeds")));

            useCases.add(WS, new NewUseCase("Log in", "Customer authenticates", null, null, "Customer",
                    List.of(), null, null,
                    List.of(new NewStep(1, "Customer enters credentials", List.of(fr1.code().value()))),
                    List.of()));

            String matrix = tools.traceMatrix(null, null);

            assertThat(matrix).contains("# Traceability matrix -- workspace " + WS.value());
            assertThat(matrix).contains(fr1.code().value()).contains(fr2.code().value());
            assertThat(matrix).contains("uses terms  : " + term.code().value());
            assertThat(matrix).contains("realised by : UC1");
            // FR-2 uses nothing and is realised by nothing.
            String fr2Block = matrix.substring(matrix.indexOf(fr2.code().value()));
            assertThat(fr2Block).contains("uses terms  : (none)").contains("realised by : (none)");
        });
    }

    @Test
    void orphanCheckListsUnrealisedRequirementsAndUnusedTermsButNotTheActor() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            RequirementService requirements = context.getBean(RequirementService.class);
            TermService terms = context.getBean(TermService.class);
            UseCaseService useCases = context.getBean(UseCaseService.class);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            terms.add(WS, new NewTerm("Passwort", "A secret credential.", null));
            terms.add(WS, new NewTerm("Customer", "A person placing an order.",
                    new ActorFacet(ActorKind.HUMAN, "orderer")));

            Requirement fr1 = requirements.add(WS, new NewRequirement("Login",
                    "The system shall authenticate a user.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Login succeeds with valid credentials")));
            Requirement fr2 = requirements.add(WS, new NewRequirement("Logout",
                    "The system shall let a user log out.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Logout succeeds")));

            useCases.add(WS, new NewUseCase("Log in", "Customer authenticates", null, null, "Customer",
                    List.of(), null, null,
                    List.of(new NewStep(1, "Customer enters credentials", List.of(fr1.code().value()))),
                    List.of()));

            String report = tools.orphanCheck(null, null);

            assertThat(report).contains("# Orphan check -- workspace " + WS.value());
            assertThat(report).contains("## Requirements without a realising use case (1)");
            assertThat(report).contains(fr2.code().value());
            String requirementsSection = report.substring(
                    report.indexOf("Requirements without"), report.indexOf("## Terms never referenced"));
            assertThat(requirementsSection).doesNotContain(fr1.code().value() + " ");

            assertThat(report).contains("## Terms never referenced (1)");
            assertThat(report).contains("Passwort");
            assertThat(report).doesNotContain("Customer");
        });
    }

    @Test
    void impactAnalysisResolvesABareBusinessIdAndReportsTransitiveDependents() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            RequirementService requirements = context.getBean(RequirementService.class);
            TermService terms = context.getBean(TermService.class);
            UseCaseService useCases = context.getBean(UseCaseService.class);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            Term term = terms.add(WS, new NewTerm("Anmeldung", "The act of proving one's identity.", null));
            terms.add(WS, new NewTerm("Customer", "A person placing an order.",
                    new ActorFacet(ActorKind.HUMAN, "orderer")));

            Requirement fr1 = requirements.add(WS, new NewRequirement("Login",
                    "The system shall authenticate a user.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Login succeeds with valid credentials")));
            requirements.linkTerm(WS, fr1.code(), term.code().value());

            useCases.add(WS, new NewUseCase("Log in", "Customer authenticates", null, null, "Customer",
                    List.of(), null, null,
                    List.of(new NewStep(1, "Customer enters credentials", List.of(fr1.code().value()))),
                    List.of()));

            String impact = tools.impactAnalysis(null, term.code().value(), null);

            assertThat(impact).contains("# Impact analysis -- workspace " + WS.value());
            assertThat(impact).contains("target: " + term.code().value());
            assertThat(impact).contains("## Transitively affected (2)");
            assertThat(impact).contains(fr1.code().value()).contains("UC1");
        });
    }

    @Test
    void impactAnalysisRejectsAnUnknownBareIdWithGuidance() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            assertThatThrownBy(() -> tools.impactAnalysis(null, "FR-999", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No resource found");
        });
    }
}
