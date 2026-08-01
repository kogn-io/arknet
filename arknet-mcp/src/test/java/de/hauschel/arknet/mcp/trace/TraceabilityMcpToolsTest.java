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

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.ArknetMcpConfiguration;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.prj.application.ProjectService;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;

/**
 * End-to-end tests of the three traceability tools against a real kognio-rdf store
 * shared by all three bounded contexts - proving {@link TraceabilityMcpTools} is wired into
 * {@link ArknetMcpConfiguration} and actually reads what {@code req_add}/{@code term_add}/{@code
 * uc_add} wrote. Seeds data exclusively through the real application services (the same path
 * {@link de.hauschel.arknet.mcp.CrossBoundedContextStoreWiringTest} uses), never via raw
 * triples.
 */
class TraceabilityMcpToolsTest {

    /**
     * The anchor every tool call below addresses its project by. Registered per test through the
     * real {@link ProjectService}, exactly as a client would - which is also why the project's
     * {@link ProjectId} is no longer a constant here: it is minted by the registration.
     *
     * <p>These tests used to pin the project through {@code arknet.workspace.id} instead. That
     * property is gone with the rest of the derived resolution path (ADR-016 decision 9), and
     * replacing it with a stubbed resolver would have been the weaker test: routing a real tool
     * call through the real registry is precisely the behaviour the switch-over changed.</p>
     */
    private static final String ANCHOR = "/home/dev/projects/trace-tools-test";

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    /**
     * {@code storageDir} is only injected after field initializers run, so the storage property is
     * appended here rather than in the {@link #contextRunner} field (mirrors
     * {@link de.hauschel.arknet.mcp.CrossBoundedContextStoreWiringTest}).
     */
    private ApplicationContextRunner runner() {
        return contextRunner.withPropertyValues(
                "arknet.rdf.storage=" + storageDir.toAbsolutePath());
    }

    /**
     * Registers the project {@link #ANCHOR} resolves to and returns its id, so the seeding calls
     * below address the very dataset the tools will read through that anchor.
     */
    private static ProjectId registerProject(final org.springframework.context.ApplicationContext context) {
        return context.getBean(ProjectService.class)
                .register("trace-tools-test", new Anchor(ANCHOR, AnchorType.PATH))
                .id();
    }

    @Test
    void traceMatrixReportsUsedTermsAndRealisingUseCasesPerRequirement() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId project = registerProject(context);
            RequirementService requirements = context.getBean(RequirementService.class);
            TermService terms = context.getBean(TermService.class);
            UseCaseService useCases = context.getBean(UseCaseService.class);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            Term term = terms.add(project, new NewTerm("Anmeldung", "The act of proving one's identity.", null));
            terms.add(project, new NewTerm("Customer", "A person placing an order.",
                    new ActorFacet(ActorKind.HUMAN, "orderer")));

            Requirement fr1 = requirements.add(project, new NewRequirement("Login",
                    "The system shall authenticate a user.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Login succeeds with valid credentials")));
            requirements.linkTerm(project, fr1.code(), term.code().value());
            Requirement fr2 = requirements.add(project, new NewRequirement("Logout",
                    "The system shall let a user log out.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Logout succeeds")));

            useCases.add(project, new NewUseCase("Log in", "Customer authenticates", null, null, "Customer",
                    List.of(), null, null,
                    List.of(new NewStep(1, "Customer enters credentials", List.of(fr1.code().value()))),
                    List.of()));

            String matrix = tools.traceMatrix(null, ANCHOR);

            assertThat(matrix).contains("# Traceability matrix -- project " + project.value());
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
            ProjectId project = registerProject(context);
            RequirementService requirements = context.getBean(RequirementService.class);
            TermService terms = context.getBean(TermService.class);
            UseCaseService useCases = context.getBean(UseCaseService.class);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            terms.add(project, new NewTerm("Passwort", "A secret credential.", null));
            terms.add(project, new NewTerm("Customer", "A person placing an order.",
                    new ActorFacet(ActorKind.HUMAN, "orderer")));

            Requirement fr1 = requirements.add(project, new NewRequirement("Login",
                    "The system shall authenticate a user.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Login succeeds with valid credentials")));
            Requirement fr2 = requirements.add(project, new NewRequirement("Logout",
                    "The system shall let a user log out.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Logout succeeds")));

            useCases.add(project, new NewUseCase("Log in", "Customer authenticates", null, null, "Customer",
                    List.of(), null, null,
                    List.of(new NewStep(1, "Customer enters credentials", List.of(fr1.code().value()))),
                    List.of()));

            String report = tools.orphanCheck(null, ANCHOR);

            assertThat(report).contains("# Orphan check -- project " + project.value());
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
            ProjectId project = registerProject(context);
            RequirementService requirements = context.getBean(RequirementService.class);
            TermService terms = context.getBean(TermService.class);
            UseCaseService useCases = context.getBean(UseCaseService.class);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            Term term = terms.add(project, new NewTerm("Anmeldung", "The act of proving one's identity.", null));
            terms.add(project, new NewTerm("Customer", "A person placing an order.",
                    new ActorFacet(ActorKind.HUMAN, "orderer")));

            Requirement fr1 = requirements.add(project, new NewRequirement("Login",
                    "The system shall authenticate a user.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Login succeeds with valid credentials")));
            requirements.linkTerm(project, fr1.code(), term.code().value());

            useCases.add(project, new NewUseCase("Log in", "Customer authenticates", null, null, "Customer",
                    List.of(), null, null,
                    List.of(new NewStep(1, "Customer enters credentials", List.of(fr1.code().value()))),
                    List.of()));

            String impact = tools.impactAnalysis(null, term.code().value(), ANCHOR);

            assertThat(impact).contains("# Impact analysis -- project " + project.value());
            assertThat(impact).contains("target: " + term.code().value());
            assertThat(impact).contains("## Transitively affected (2)");
            assertThat(impact).contains(fr1.code().value()).contains("UC1");
        });
    }

    @Test
    void impactAnalysisRejectsAnUnknownBareIdWithGuidance() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId project = registerProject(context);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            assertThatThrownBy(() -> tools.impactAnalysis(null, "FR-999", ANCHOR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No resource found");
        });
    }

    /**
     * Unlike a bare id, a CURIE with a known prefix ({@code req:FR-999}) resolves syntactically
     * via {@link de.hauschel.arknet.mcp.store.Prefixes#toIri} without ever checking the store, so
     * {@link de.hauschel.arknet.mcp.store.HandleResolver} happily hands {@code impact_analysis} an
     * IRI for a requirement that was never written. Before issue #135 this rendered a fully formed
     * "Transitively affected (0)" report instead of surfacing the unknown handle - the false
     * "nothing depends on this" this test guards against.
     */
    @Test
    void impactAnalysisReportsNotFoundForAResolvableButUnknownCurie() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId project = registerProject(context);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            String impact = tools.impactAnalysis(null, "req:FR-999", ANCHOR);

            assertThat(impact).startsWith("Resource not found (no statements): req:FR-999");
            assertThat(impact).doesNotContain("Transitively affected");
        });
    }

    @Test
    void actorUseCaseMatrixReportsTheActorAndItsUseCaseInBothDirections() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId ws = registerProject(context);
            TermService terms = context.getBean(TermService.class);
            UseCaseService useCases = context.getBean(UseCaseService.class);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            Term actor = terms.add(ws, new NewTerm("Customer", "A person placing an order.",
                    new ActorFacet(ActorKind.HUMAN, "orderer")));
            useCases.add(ws, new NewUseCase("Log in", "Customer authenticates", null, null, "Customer",
                    List.of(), null, null,
                    List.of(new NewStep(1, "Customer enters credentials", List.of())),
                    List.of()));

            String matrix = tools.actorUseCaseMatrix(null, ANCHOR);

            assertThat(matrix).contains("# Actor/use-case matrix -- project " + ws.value());
            String actorsSection = matrix.substring(matrix.indexOf("## Actors"), matrix.indexOf("## Use cases"));
            assertThat(actorsSection).contains(actor.code().value()).contains("use cases : UC1");
            String useCasesSection = matrix.substring(matrix.indexOf("## Use cases"));
            assertThat(useCasesSection).contains("UC1").contains("actors    : " + actor.code().value());
        });
    }

    @Test
    void termCooccurrenceFindsTermsNamedTogetherInRequirementAndUseCaseText() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ProjectId ws = registerProject(context);
            RequirementService requirements = context.getBean(RequirementService.class);
            TermService terms = context.getBean(TermService.class);
            UseCaseService useCases = context.getBean(UseCaseService.class);
            TraceabilityMcpTools tools = context.getBean(TraceabilityMcpTools.class);

            Term kunde = terms.add(ws, new NewTerm("Kunde", "A customer.",
                    new ActorFacet(ActorKind.HUMAN, "orderer")));
            Term bestellung = terms.add(ws, new NewTerm("Bestellung", "An order.", null));
            terms.add(ws, new NewTerm("Vertrag", "A binding agreement.", null));

            requirements.add(ws, new NewRequirement("Bestandsdaten",
                    "Der Kunde sieht seine Bestellung ein.", RequirementType.FUNCTIONAL, null, null, null,
                    List.of("Die Bestandsdaten werden korrekt angezeigt")));
            useCases.add(ws, new NewUseCase("View order", "Kunde bestaetigt die Bestellung", null, null,
                    "Kunde", List.of(), null, null,
                    List.of(new NewStep(1, "Kunde ruft die Bestellung auf", List.of())),
                    List.of()));

            String report = tools.termCooccurrence(null, ANCHOR);

            assertThat(report).contains("# Term co-occurrence -- project " + ws.value());
            assertThat(report).contains("## Term pairs named together in the same text (1)");
            assertThat(report).contains(kunde.code().value()).contains(bestellung.code().value());
            assertThat(report).contains("2 text(s)");
        });
    }
}
