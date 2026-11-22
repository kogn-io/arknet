// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.hauschel.arknet.actor.application.ActorService;
import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.adr.application.AdrService;
import de.hauschel.arknet.adr.application.port.in.AddAdr.NewAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.adr.domain.NewConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.ArknetMcpConfiguration;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.pr.shared.RequirementCode;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.ConstraintService;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.AcceptanceCriterionTextPatch;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.MissingAcceptanceCriteriaException;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.analysis.adapter.mcp.StoreCheckMcpTools;
import de.hauschel.arknet.analysis.adapter.mcp.TraceabilityMcpTools;
import de.hauschel.arknet.mcp.store.StoreReportTools;
import de.hauschel.arknet.prj.application.ProjectService;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.UseCaseCorrection;
import de.hauschel.arknet.uc.domain.MissingPrimaryRoleException;
import de.hauschel.arknet.uc.domain.RoleRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.Term;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.versioning.PublishState;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

/**
 * The startup migration of the earlier storage layout, against the real store under a
 * {@link TempDir}: a model with every resource type and every child kind is written through this
 * version's services, rewritten into the earlier layout by {@link LegacyLayoutFixture}, and then
 * migrated - by the daemon's own composition root where the promise concerns what a tool sees, by
 * {@link LegacyLayoutMigration} directly where it concerns the report.
 */
class LegacyLayoutMigrationTest {

    private static final ProjectId PROJECT = new ProjectId("legacy-migration");
    private static final DatasetId DATASET = new DatasetId(PROJECT.value());
    private static final String EN = "en";
    private static final String GERMAN_TITLE = "Kunde gibt eine Bestellung auf";
    /** The datatype suffix N-Triples gives a plain, untagged literal. */
    private static final String XSD_STRING = "^^<http://www.w3.org/2001/XMLSchema#string>";

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    /** What the services read before the store was rewritten into the earlier layout. */
    private record Model(Map<String, Object> byCode, String requirementIri, String untaggedUseCaseIri) {
    }

    @Test
    void everyServiceReadsTheSameContentAfterTheMigrationAsBefore() {
        Model before = legacyStore();

        inDaemon(context -> {
            Map<String, Object> after = readAll(context);
            assertThat(after.keySet()).isEqualTo(before.byCode().keySet());
            before.byCode().forEach((code, resource) -> {
                if (!code.equals("UC2")) {
                    assertThat(after.get(code)).as(code).isEqualTo(resource);
                }
            });
            Requirement german = context.getBean(RequirementService.class)
                    .get(PROJECT, new RequirementCode("FR-1"), "de").orElseThrow();
            assertThat(german.title()).isEqualTo(GERMAN_TITLE);
        });
    }

    @Test
    void noOldGraphIsLeftAndTheGuardHandsTheDatasetOut() {
        legacyStore();

        inDaemon(context -> {
            try (DatasetHandle handle = context.getBean(DatasetLifecycle.class).acquire(DATASET)) {
                assertThat(LegacyLayoutRefusingDatasetLifecycle.holdsLegacyLayout(handle.sparqlQuery())).isFalse();
            }
        });
    }

    @Test
    void everyResourceIsPublishedExactlyOnceAndTakesARegularUpdate() {
        legacyStore();

        inDaemon(context -> {
            VersionedWriteFunnel funnel = context.getBean("requirementsWriteFunnel", VersionedWriteFunnel.class);
            List<String> resources = resourceIris(context.getBean(DatasetLifecycle.class));
            assertThat(resources).hasSize(13);
            for (String resource : resources) {
                assertThat(funnel.history(DATASET, resource)).as(resource).hasSize(1);
                assertThat(funnel.publishState(DATASET, resource)).as(resource)
                        .isEqualTo(PublishState.PUBLISHED_UNCHANGED);
            }

            Requirement linked = context.getBean(RequirementService.class)
                    .linkTerm(PROJECT, new RequirementCode("FR-1"), "TERM-2", EN);
            assertThat(linked.usesTerms()).hasSize(2);
            assertThat(funnel.history(DATASET, linked.id().value().value())).hasSize(2);
        });
    }

    @Test
    void aCodeDeletedUnderTheEarlierLayoutIsNotHandedOutAgain() {
        legacyStore();

        inDaemon(context -> {
            Requirement next = context.getBean(RequirementService.class).add(PROJECT,
                    new NewRequirement("Another", "Another requirement.", null, RequirementType.FUNCTIONAL, null,
                            null, List.of("It exists"), EN, List.of()), EN);
            assertThat(next.code().value()).isEqualTo("FR-4");
        });
    }

    /** Issue #675's case: a use case with untagged legacy literals takes an edge-only change. */
    @Test
    void aLegacyUseCaseWithUntaggedLiteralsTakesAnEdgeOnlyChange() {
        legacyStore();

        inDaemon(context -> {
            UseCase linked = context.getBean(UseCaseService.class)
                    .linkTerm(PROJECT, new de.hauschel.arknet.uc.domain.UseCaseCode("UC2"), "TERM-2", EN);
            assertThat(linked.title()).isEqualTo("Cancel order");
        });
    }

    @Test
    void aBlankNodeEdgeIsDroppedAndListedInTheReport() {
        Model before = legacyStore();
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try {
            LegacyLayoutMigrationReport report = migration(raw).migrate(raw, DATASET);

            assertThat(report.outcome()).isEqualTo(LegacyLayoutMigrationReport.Outcome.MIGRATED);
            assertThat(report.droppedEdges()).singleElement().satisfies(edge -> {
                assertThat(edge.subject()).isEqualTo(before.requirementIri());
                assertThat(edge.code()).isEqualTo("FR-1");
                assertThat(edge.property()).isEqualTo("https://w3id.org/arknet/requirements#usesTerm");
                assertThat(edge.reason()).isEqualTo(LegacyLayoutMigrationReport.DropReason.BLANK_NODE);
            });
            assertThat(report.retainedCodes()).containsExactly("FR-3");
            assertThat(report.resourcesByType()).containsEntry("requirement", 2).containsEntry("use case", 2)
                    .containsEntry("glossary term", 2).containsEntry("bounded context", 2)
                    .containsEntry("context relationship", 1).containsEntry("architecture decision", 1)
                    .containsEntry("actor", 1).containsEntry("role", 1).containsEntry("constraint", 1);
            assertThat(report.droppedSubjects()).anySatisfy(subject -> assertThat(subject).startsWith("_:"))
                    .anySatisfy(subject -> assertThat(subject).contains("model/glossary"));
        } finally {
            raw.shutDownAll();
        }
    }

    @Test
    void aSecondRunFindsNothingToDoAndChangesNothing() {
        legacyStore();
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try {
            migration(raw).migrate(raw, DATASET);
            long migrated = tripleCount(raw, DATASET);

            LegacyLayoutMigrationReport second = migration(raw).migrate(raw, DATASET);

            assertThat(second.outcome()).isEqualTo(LegacyLayoutMigrationReport.Outcome.NOT_NEEDED);
            assertThat(tripleCount(raw, DATASET)).isEqualTo(migrated);
        } finally {
            raw.shutDownAll();
        }
    }

    @Test
    void aTechnicalFailureRollsTheWholeDatasetBack() {
        Model before = legacyStore();
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try {
            // an intermediate build's draft next to the old graphs: the import only creates
            update(raw, DATASET, "INSERT DATA { GRAPH <" + ContentGraph.GRAPH_IRI + "> { <"
                    + before.requirementIri() + "> <http://purl.org/dc/terms/title> \"Draft\" } }");
            long legacy = tripleCount(raw, DATASET);

            LegacyLayoutMigrationReport report = migration(raw).migrate(raw, DATASET);

            assertThat(report.outcome()).isEqualTo(LegacyLayoutMigrationReport.Outcome.FAILED);
            assertThat(report.failure()).contains(before.requirementIri());
            assertThat(tripleCount(raw, DATASET)).isEqualTo(legacy);
            try (DatasetHandle handle = raw.acquire(DATASET)) {
                assertThat(LegacyLayoutRefusingDatasetLifecycle.holdsLegacyLayout(handle.sparqlQuery())).isTrue();
            }
        } finally {
            raw.shutDownAll();
        }
    }

    /**
     * Decision for P1 of round 1: a record the current gate rejects is carried over and listed;
     * the retired actor edges of a use case are dropped and listed with the actor they named, and
     * literal acceptance criteria are reshaped into criterion resources and listed with their text.
     */
    @Test
    void recordsTheCurrentGateRejectsAreCarriedOverListedAndTheRestMigratesNormally() {
        Model before = legacyStoreWithLegacyOnlyForms();
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try {
            LegacyLayoutMigrationReport report = migration(raw).migrate(raw, DATASET);

            assertThat(report.outcome()).isEqualTo(LegacyLayoutMigrationReport.Outcome.MIGRATED);
            assertThat(report.resourcesByType()).containsEntry("requirement", 6).containsEntry("use case", 3);
            assertThat(report.carriedPastGate()).hasSize(2)
                    .anySatisfy(line -> assertThat(line).startsWith("requirement FR-10 (")
                            .contains(LegacyLayoutFixture.WITHOUT_CRITERION).contains("acceptanceCriterion"))
                    .anySatisfy(line -> assertThat(line).startsWith("use case UC9 (")
                            .contains(LegacyLayoutFixture.ACTOR_USE_CASE).contains("primaryRole"));
            assertThat(report.summary()).contains("carried over past the current write gate: 2")
                    .contains("requirements whose literal acceptance criteria became criterion resources: 2");
            assertThat(report.convertedCriteria()).hasSize(2)
                    .anySatisfy(line -> assertThat(line).startsWith("FR-11 (")
                            .contains(LegacyLayoutFixture.LITERAL_CRITERION).contains("2 literal")
                            .contains("1. \"" + LegacyLayoutFixture.LITERAL_CRITERIA.get(0) + "\"")
                            .contains("2. \"" + LegacyLayoutFixture.LITERAL_CRITERIA.get(1) + "\""))
                    .anySatisfy(line -> assertThat(line).startsWith("FR-13 (")
                            .contains("1. \"" + LegacyLayoutFixture.TAGGED_CRITERION_TEXT + "\"@de"));
            assertThat(report.droppedEdges()).noneSatisfy(edge -> assertThat(edge.property())
                    .isEqualTo("https://w3id.org/arknet/requirements#acceptanceCriterion"));
            assertThat(report.droppedEdges()).filteredOn(edge -> "UC9".equals(edge.code()))
                    .extracting(LegacyLayoutMigrationReport.DroppedEdge::property)
                    .containsExactlyInAnyOrder("https://w3id.org/arknet/requirements#primaryActor",
                            "https://w3id.org/arknet/requirements#supportingActor");
            assertThat(report.droppedEdges()).filteredOn(edge -> "UC9".equals(edge.code())).allSatisfy(edge -> {
                assertThat(edge.reason()).isEqualTo(LegacyLayoutMigrationReport.DropReason.RETIRED_PROPERTY);
                assertThat(edge.value()).isEqualTo("<" + LegacyLayoutFixture.LEGACY_ACTOR + "> (ACTOR-9)");
                assertThat(edge.line()).contains("retired property");
            });
        } finally {
            raw.shutDownAll();
        }

        inDaemon(context -> {
            try (DatasetHandle handle = context.getBean(DatasetLifecycle.class).acquire(DATASET)) {
                assertThat(LegacyLayoutRefusingDatasetLifecycle.holdsLegacyLayout(handle.sparqlQuery())).isFalse();
            }
            Map<String, Object> after = readAll(context);
            before.byCode().forEach((code, resource) -> {
                if (!code.equals("UC2")) {
                    assertThat(after.get(code)).as(code).isEqualTo(resource);
                }
            });
            VersionedWriteFunnel funnel = context.getBean("requirementsWriteFunnel", VersionedWriteFunnel.class);
            for (String resource : resourceIris(context.getBean(DatasetLifecycle.class))) {
                assertThat(funnel.history(DATASET, resource)).as(resource).hasSize(1);
                assertThat(funnel.publishState(DATASET, resource)).as(resource)
                        .isEqualTo(PublishState.PUBLISHED_UNCHANGED);
            }
        });
    }

    @Test
    void aRecordCarriedPastTheGateIsReadableThroughGetAndList() {
        legacyStoreWithLegacyOnlyForms();

        inDaemon(context -> {
            RequirementService requirements = context.getBean(RequirementService.class);
            Requirement withoutCriterion = requirements.get(PROJECT, new RequirementCode("FR-10"), EN).orElseThrow();
            Requirement literalTermEdge = requirements.get(PROJECT, new RequirementCode("FR-12"), EN).orElseThrow();

            // the read path shows the earlier adapter's placeholder where no criterion resource exists
            assertThat(withoutCriterion.title()).isEqualTo("Legacy without criterion");
            assertThat(withoutCriterion.acceptanceCriteria()).hasSize(1);
            assertThat(withoutCriterion.acceptanceCriteria().get(0).text()).contains("Altdatensatz");
            // a literal term edge is not a reference and is not shown as one
            assertThat(literalTermEdge.usesTerms()).isEmpty();
            assertThat(literalTermEdge.acceptanceCriteria().get(0).text()).isEqualTo("It is linked");
            assertThat(requirements.list(PROJECT, EN)).extracting(found -> found.code().value())
                    .contains("FR-10", "FR-11", "FR-12", "FR-13");

            // a use case without a primary role shows the placeholder role instead of vanishing
            UseCaseService useCases = context.getBean(UseCaseService.class);
            UseCase actorUseCase = useCases.get(PROJECT, new UseCaseCode("UC9"), EN).orElseThrow();
            assertThat(actorUseCase.title()).isEqualTo("Legacy actor use case");
            assertThat(actorUseCase.primaryRole()).isEqualTo(RoleRef.LEGACY_PLACEHOLDER);
            assertThat(actorUseCase.supportingRoles()).isEmpty();
            assertThat(actorUseCase.steps()).extracting(Step::text).containsExactly("Clerk files the order");
            assertThat(useCases.list(PROJECT, EN)).extracting(found -> found.code().value()).contains("UC9");
        });
    }

    /**
     * Decision for the literal criterion: it becomes a criterion resource with its text and
     * language tag unchanged, so the requirement passes the gate and shows its own text.
     */
    @Test
    void aLiteralCriterionBecomesACriterionResourceWithItsTextAndTag() {
        legacyStoreWithLegacyOnlyForms();

        inDaemon(context -> {
            RequirementService requirements = context.getBean(RequirementService.class);
            RequirementCode literal = new RequirementCode("FR-11");
            RequirementCode tagged = new RequirementCode("FR-13");

            assertThat(requirements.get(PROJECT, literal, EN).orElseThrow().acceptanceCriteria())
                    .extracting(criterion -> criterion.text())
                    .containsExactlyElementsOf(LegacyLayoutFixture.LITERAL_CRITERIA);
            assertThat(requirements.get(PROJECT, tagged, "de").orElseThrow().acceptanceCriteria())
                    .extracting(criterion -> criterion.text())
                    .containsExactly(LegacyLayoutFixture.TAGGED_CRITERION_TEXT);
            assertThat(criterionTexts(context.getBean(DatasetLifecycle.class), LegacyLayoutFixture.LITERAL_CRITERION))
                    .containsExactlyInAnyOrder("\"" + LegacyLayoutFixture.LITERAL_CRITERIA.get(0) + "\"" + XSD_STRING,
                            "\"" + LegacyLayoutFixture.LITERAL_CRITERIA.get(1) + "\"" + XSD_STRING);
            assertThat(criterionTexts(context.getBean(DatasetLifecycle.class),
                    LegacyLayoutFixture.TAGGED_LITERAL_CRITERION))
                    .containsExactly("\"" + LegacyLayoutFixture.TAGGED_CRITERION_TEXT + "\"@de");

            // both take a regular update right away, the untagged criteria included
            Requirement renamed = requirements.update(PROJECT, literal, "Renamed literal criterion", null, null,
                    null, null, null, null, null, EN, EN);
            Requirement linked = requirements.linkTerm(PROJECT, tagged, "TERM-1", EN);
            assertThat(renamed.acceptanceCriteria()).extracting(criterion -> criterion.text())
                    .containsExactlyElementsOf(LegacyLayoutFixture.LITERAL_CRITERIA);
            assertThat(linked.usesTerms()).hasSize(1);
            assertThat(criterionTexts(context.getBean(DatasetLifecycle.class),
                    LegacyLayoutFixture.TAGGED_LITERAL_CRITERION))
                    .containsExactly("\"" + LegacyLayoutFixture.TAGGED_CRITERION_TEXT + "\"@de");
        });
    }

    @Test
    void anUpdateClosingTheGapRepairsACarriedRecordAndOneLeavingItOpenIsRejected() {
        legacyStoreWithLegacyOnlyForms();

        inDaemon(context -> {
            RequirementService requirements = context.getBean(RequirementService.class);
            VersionedWriteFunnel funnel = context.getBean("requirementsWriteFunnel", VersionedWriteFunnel.class);
            RequirementCode requirement = new RequirementCode("FR-10");
            assertThatThrownBy(() -> requirements.linkTerm(PROJECT, requirement, "TERM-1", EN))
                    .isInstanceOf(MissingAcceptanceCriteriaException.class)
                    .hasMessageContaining("FR-10")
                    .hasMessageContaining("placeholder");

            Requirement repaired = requirements.update(PROJECT, requirement, null, null, null, null,
                    List.of(new AcceptanceCriterionTextPatch(1, "An order shows up")), null, null, null, EN, EN);

            assertThat(repaired.acceptanceCriteria()).extracting(criterion -> criterion.text())
                    .containsExactly("An order shows up");
            Requirement linked = requirements.linkTerm(PROJECT, requirement, "TERM-1", EN);
            assertThat(linked.usesTerms()).hasSize(1);
            assertThat(funnel.history(DATASET, linked.id().value().value())).hasSize(3);

            UseCaseService useCases = context.getBean(UseCaseService.class);
            UseCaseCode useCase = new UseCaseCode("UC9");
            assertThatThrownBy(() -> useCases.linkTerm(PROJECT, useCase, "TERM-1", EN))
                    .isInstanceOf(MissingPrimaryRoleException.class)
                    .hasMessageContaining("UC9")
                    .hasMessageContaining("primaryRole");
            assertThatThrownBy(() -> useCases.update(PROJECT, useCase,
                    UseCaseCorrection.builder().title("Still without a role").build(), EN))
                    .isInstanceOf(MissingPrimaryRoleException.class);

            UseCase withRole = useCases.update(PROJECT, useCase,
                    UseCaseCorrection.builder().primaryRole("ROLE-1").build(), EN);

            assertThat(withRole.primaryRole()).isNotEqualTo(RoleRef.LEGACY_PLACEHOLDER);
            UseCase linkedUseCase = useCases.linkTerm(PROJECT, useCase, "TERM-1", EN);
            assertThat(linkedUseCase.usesTerms()).hasSize(1);
            assertThat(funnel.history(DATASET, linkedUseCase.id().value().value())).hasSize(3);
        });
    }

    /**
     * Review round 2: the analysis and report tools read a carried record without throwing and
     * show it, and a carried use case that was never repaired can still be deleted.
     */
    @Test
    void theAnalysisAndReportToolsReadACarriedRecordAndItCanBeDeleted() throws Exception {
        legacyStoreWithLegacyOnlyForms();
        Path anchor = java.nio.file.Files.createDirectories(storageDir.resolve("anchor"));
        Path reports = storageDir.resolve("reports");

        contextRunner.withPropertyValues("arknet.rdf.storage=" + storageDir, "arknet.report.dir=" + reports)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(ProjectService.class).adopt(PROJECT, "Legacy migration",
                            new Anchor(anchor.toString(), AnchorType.PATH));

                    String check = context.getBean(StoreCheckMcpTools.class)
                            .storeCheck(null, null, null, anchor.toString());
                    String matrix = context.getBean(TraceabilityMcpTools.class)
                            .traceMatrix(null, null, anchor.toString());
                    String overview = context.getBean(StoreReportTools.class).storeOverview(null, anchor.toString());

                    assertThat(check).isNotBlank();
                    assertThat(matrix).contains("FR-10 \"Legacy without criterion\"")
                            .contains("FR-11 \"Legacy literal criterion\"");
                    assertThat(overview).contains("FR-10").contains("UC9");

                    UseCaseService useCases = context.getBean(UseCaseService.class);
                    useCases.delete(PROJECT, new UseCaseCode("UC9"));
                    assertThat(useCases.get(PROJECT, new UseCaseCode("UC9"), EN)).isEmpty();
                });
    }

    /**
     * Decision for FR-12: a literal on a property whose shape requires another resource is dropped
     * and listed with its value; the resource then passes the gate and takes regular writes.
     */
    @Test
    void aLiteralTermEdgeIsDroppedAndListedAndTheRequirementPassesTheGate() {
        legacyStoreWithLegacyOnlyForms();
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try {
            LegacyLayoutMigrationReport report = migration(raw).migrate(raw, DATASET);

            assertThat(report.droppedEdges()).filteredOn(edge -> "FR-12".equals(edge.code())).singleElement()
                    .satisfies(edge -> {
                        assertThat(edge.subject()).isEqualTo(LegacyLayoutFixture.LITERAL_TERM_EDGE);
                        assertThat(edge.property()).isEqualTo("https://w3id.org/arknet/requirements#usesTerm");
                        assertThat(edge.reason()).isEqualTo(LegacyLayoutMigrationReport.DropReason.LITERAL);
                        assertThat(edge.value()).isEqualTo("\"Kunde\"@de");
                        assertThat(edge.line()).contains("FR-12").contains("\"Kunde\"@de").contains("literal");
                    });
            assertThat(report.carriedPastGate()).noneSatisfy(line -> assertThat(line).contains("FR-12"));
        } finally {
            raw.shutDownAll();
        }

        inDaemon(context -> {
            RequirementService requirements = context.getBean(RequirementService.class);
            RequirementCode code = new RequirementCode("FR-12");
            assertThat(requirements.get(PROJECT, code, EN).orElseThrow().usesTerms()).isEmpty();

            Requirement renamed = requirements.update(PROJECT, code, "Renamed legacy requirement", null, null,
                    null, null, null, null, null, EN, EN);
            Requirement linked = requirements.linkTerm(PROJECT, code, "TERM-2", EN);

            assertThat(renamed.title()).isEqualTo("Renamed legacy requirement");
            assertThat(linked.usesTerms()).hasSize(1);
        });
    }

    @Test
    void theSystemDatasetIsNeverMigrated() {
        legacyStore();
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        DatasetId system = new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET);
        try {
            update(raw, system, "INSERT DATA { GRAPH <" + LegacyLayoutFixture.PROVENANCE
                    + "> { <urn:revision> <urn:p> \"o\" } }");
            long systemTriples = tripleCount(raw, system);

            List<LegacyLayoutMigrationReport> reports = migration(raw).migrateAll(raw);

            assertThat(reports).extracting(LegacyLayoutMigrationReport::dataset).doesNotContain(system.value());
            assertThat(tripleCount(raw, system)).isEqualTo(systemTriples);
        } finally {
            raw.shutDownAll();
        }
    }

    // --- fixture ------------------------------------------------------------------------------

    /**
     * Writes one resource of every type, with every child kind, through this version's services,
     * reads it all back, and rewrites the dataset into the earlier layout: plus a German title, a
     * blank-node edge, the glossary scheme declaration the earlier term path stored, a use case
     * whose literals lost their language tags, and the provenance record of a deleted FR-3.
     */
    private Model legacyStore() {
        Model[] model = new Model[1];
        inDaemon(context -> {
            TermService terms = context.getBean(TermService.class);
            Term order = terms.add(PROJECT, new NewTerm("Order", "A customer's request to buy.", EN, null,
                    List.of()), EN);
            terms.add(PROJECT, new NewTerm("Order line", "One item of an order.", EN, order.code(), List.of()), EN);

            Actor customer = context.getBean(ActorService.class)
                    .add(PROJECT, new NewActor(ActorType.HUMAN, "Customer", "Buys things.", EN), EN);
            RoleDetail buyer = context.getBean(RoleService.class).add(PROJECT,
                    new NewRole("Buyer", "Places orders.", List.of(customer.code().value()), EN), EN);

            RequirementService requirements = context.getBean(RequirementService.class);
            Requirement fr = requirements.add(PROJECT, new NewRequirement("Customer places an order",
                    "The system shall let a customer place an order.", "Core business.",
                    RequirementType.FUNCTIONAL, Priority.MUST_HAVE, null,
                    List.of("An order is placed", "The order is confirmed"), EN, List.of("TERM-1")), EN);
            requirements.add(PROJECT, new NewRequirement("Fast checkout", "Checkout completes quickly.", null,
                    RequirementType.NON_FUNCTIONAL, Priority.SHOULD_HAVE, "performance",
                    List.of("Checkout under two seconds"), EN, List.of()), EN);
            requirements.add(PROJECT, new NewRequirement("Discarded", "Removed again.", null,
                    RequirementType.FUNCTIONAL, null, null, List.of("Removed"), EN, List.of()), EN);
            requirements.delete(PROJECT, new RequirementCode("FR-2"));
            Constraint constraint = context.getBean(ConstraintService.class).add(PROJECT,
                    new NewConstraint("Runs on the JVM", "The system runs on Java 25.", ConstraintType.TECHNICAL, EN),
                    EN);
            requirements.linkConstraint(PROJECT, fr.code(), constraint.code().value(), EN);

            UseCaseService useCases = context.getBean(UseCaseService.class);
            useCases.add(PROJECT, new NewUseCase("Place order", "Customer places an order", "Shop", "Customer wants",
                    buyer.role().code().value(), List.of(), "Logged in", "Order stored",
                    List.of(new NewStep(1, "Customer selects items", List.of("FR-1")),
                            new NewStep(2, "Customer confirms", List.of())),
                    List.of("1a. Item out of stock", "2a. Payment fails"), EN, List.of("TERM-1")), EN);
            useCases.add(PROJECT, new NewUseCase("Cancel order", "Customer cancels an order", null, null,
                    buyer.role().code().value(), List.of(), null, null,
                    List.of(new NewStep(1, "Customer cancels", List.of())), List.of(), EN, List.of()), EN);

            BoundedContextService contexts = context.getBean(BoundedContextService.class);
            BoundedContext ordering = contexts.add(PROJECT, new NewBoundedContext("Ordering", "Takes orders.",
                    Subdomain.CORE_DOMAIN, null, EN), EN);
            BoundedContext billing = contexts.add(PROJECT, new NewBoundedContext("Billing", "Bills orders.",
                    null, null, EN), EN);
            contexts.linkTerm(PROJECT, ordering.code(), "TERM-1", de.hauschel.arknet.bc.domain.TermRelation.USES);
            contexts.linkContext(PROJECT, ordering.code(), billing.code(), RelationshipType.CUSTOMER_SUPPLIER);

            context.getBean(AdrService.class).add(PROJECT, new NewAdr("Use a triple store", "We need a graph.",
                    "We store the model as RDF.",
                    List.of(new NewConsequence("Queries are flexible.", ConsequenceType.POSITIVE),
                            new NewConsequence("A new technology to learn.", ConsequenceType.NEGATIVE)),
                    List.of(new NewConsideredOption("RDF store", "Fits the graph.", OptionOutcome.CHOSEN),
                            new NewConsideredOption("Relational", "Joins get heavy.", OptionOutcome.REJECTED)),
                    EN, List.of("FR-1"), List.of(ordering.code().value()), List.of("TERM-1"), List.of()), EN);

            String untaggedUseCase = useCases.get(PROJECT, new de.hauschel.arknet.uc.domain.UseCaseCode("UC2"), null)
                    .orElseThrow().id().value().value();
            model[0] = new Model(readAll(context), fr.id().value().value(), untaggedUseCase);
        });

        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try {
            new LegacyLayoutFixture().legacify(raw, DATASET, Set.of(model[0].untaggedUseCaseIri()));
            String requirementsGraph = LegacyLayoutFixture.BASE + "requirements";
            update(raw, DATASET, "INSERT DATA { GRAPH <" + requirementsGraph + "> { <" + model[0].requirementIri()
                    + "> <http://purl.org/dc/terms/title> \"" + GERMAN_TITLE + "\"@de ; "
                    + "<https://w3id.org/arknet/requirements#usesTerm> _:ghost . "
                    + "_:ghost <http://www.w3.org/2004/02/skos/core#prefLabel> \"Ghost\" } }");
            update(raw, DATASET, "INSERT DATA { GRAPH <" + LegacyLayoutFixture.BASE + "ubiquitous-language> { "
                    + "<https://w3id.org/arknet/model/glossary> a <http://www.w3.org/2004/02/skos/core#ConceptScheme> } }");
            update(raw, DATASET, LegacyLayoutFixture.deletedRevision("FR-3"));
            try (DatasetHandle handle = raw.acquire(DATASET)) {
                assertThat(LegacyLayoutRefusingDatasetLifecycle.holdsLegacyLayout(handle.sparqlQuery())).isTrue();
            }
        } finally {
            raw.shutDownAll();
        }
        return model[0];
    }

    /** {@link #legacyStore()} plus the frozen legacy-only forms of {@link LegacyLayoutFixture#legacyOnlyForms()}. */
    private Model legacyStoreWithLegacyOnlyForms() {
        Model model = legacyStore();
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try {
            update(raw, DATASET, LegacyLayoutFixture.legacyOnlyForms());
        } finally {
            raw.shutDownAll();
        }
        return model;
    }

    private static Map<String, Object> readAll(ApplicationContext context) {
        Map<String, Object> byCode = new TreeMap<>();
        RequirementService requirements = context.getBean(RequirementService.class);
        for (String code : List.of("FR-1", "NFR-1")) {
            requirements.get(PROJECT, new RequirementCode(code), EN).ifPresent(found -> byCode.put(code, found));
        }
        context.getBean(ConstraintService.class)
                .get(PROJECT, new de.hauschel.arknet.pr.shared.ConstraintCode("TCON-1"), EN)
                .ifPresent(found -> byCode.put("TCON-1", found));
        for (String code : List.of("UC1", "UC2")) {
            context.getBean(UseCaseService.class).get(PROJECT, new de.hauschel.arknet.uc.domain.UseCaseCode(code), EN)
                    .ifPresent(found -> byCode.put(code, found));
        }
        for (String code : List.of("TERM-1", "TERM-2")) {
            context.getBean(TermService.class).get(PROJECT, new TermCode(code), EN)
                    .ifPresent(found -> byCode.put(code, found));
        }
        for (String code : List.of("BC-1", "BC-2")) {
            context.getBean(BoundedContextService.class)
                    .get(PROJECT, new de.hauschel.arknet.bc.domain.BoundedContextCode(code), EN)
                    .ifPresent(found -> byCode.put(code, found));
        }
        context.getBean(AdrService.class).get(PROJECT, new de.hauschel.arknet.adr.domain.AdrCode("ADR-1"), EN)
                .ifPresent(found -> byCode.put("ADR-1", found));
        context.getBean(ActorService.class).get(PROJECT, new de.hauschel.arknet.actor.domain.ActorCode("ACTOR-1"), EN)
                .ifPresent(found -> byCode.put("ACTOR-1", found));
        context.getBean(RoleService.class).get(PROJECT, new de.hauschel.arknet.actor.domain.RoleCode("ROLE-1"), EN)
                .ifPresent(found -> byCode.put("ROLE-1", found));
        return byCode;
    }

    private void inDaemon(Consumer<ApplicationContext> work) {
        contextRunner.withPropertyValues("arknet.rdf.storage=" + storageDir).run(context -> {
            assertThat(context).hasNotFailed();
            work.accept(context);
        });
    }

    private static LegacyLayoutMigration migration(DatasetLifecycle raw) {
        return LegacyLayoutMigration.over(raw, new DisplayLocale(Locale.ENGLISH, Locale.ENGLISH));
    }

    private static List<String> resourceIris(DatasetLifecycle lifecycle) {
        try (DatasetHandle handle = lifecycle.acquire(DATASET)) {
            return handle.sparqlQuery().select("SELECT DISTINCT ?s WHERE { GRAPH <" + ContentGraph.GRAPH_IRI
                    + "> { ?s a ?type } FILTER(!CONTAINS(STR(?s), \"#\")) }")
                    .map(row -> ((IRI) row.getValue("s").orElseThrow()).getIRIString())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /** The {@code criterionText} literals of {@code requirement}'s criteria, in N-Triples form. */
    private static List<String> criterionTexts(DatasetLifecycle lifecycle, String requirement) {
        try (DatasetHandle handle = lifecycle.acquire(DATASET)) {
            return handle.sparqlQuery().select("SELECT ?text WHERE { GRAPH <" + ContentGraph.GRAPH_IRI + "> { <"
                    + requirement + "> <https://w3id.org/arknet/requirements#acceptanceCriterion> ?criterion . "
                    + "?criterion <https://w3id.org/arknet/requirements#criterionText> ?text } }")
                    .map(row -> row.getValue("text").orElseThrow().ntriplesString())
                    .collect(Collectors.toList());
        }
    }

    private static long tripleCount(DatasetLifecycle lifecycle, DatasetId dataset) {
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            return handle.transactor().inTransaction(tx -> tx.count());
        }
    }

    private static void update(DatasetLifecycle lifecycle, DatasetId dataset, String update) {
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            handle.transactor().inTransaction(tx -> {
                tx.update(update);
                return null;
            });
        }
    }
}
