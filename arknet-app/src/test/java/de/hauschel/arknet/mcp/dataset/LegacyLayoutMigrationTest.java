// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import static org.assertj.core.api.Assertions.assertThat;

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
import de.hauschel.arknet.pr.shared.RequirementCode;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.ConstraintService;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.domain.UseCase;
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
            assertThat(report.droppedEdges()).containsExactly(
                    "<" + before.requirementIri() + "> <https://w3id.org/arknet/requirements#usesTerm>");
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
    void aResourceFailingTheGateRollsTheWholeDatasetBack() {
        Model before = legacyStore();
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try {
            update(raw, DATASET, "DELETE WHERE { GRAPH <" + LegacyLayoutFixture.BASE + "requirements> { <"
                    + before.requirementIri() + "> <http://purl.org/dc/terms/title> ?title } }");
            long legacy = tripleCount(raw, DATASET);

            LegacyLayoutMigrationReport report = migration(raw).migrate(raw, DATASET);

            assertThat(report.outcome()).isEqualTo(LegacyLayoutMigrationReport.Outcome.FAILED);
            assertThat(report.failure()).contains("FR-1").contains(before.requirementIri());
            assertThat(tripleCount(raw, DATASET)).isEqualTo(legacy);
            try (DatasetHandle handle = raw.acquire(DATASET)) {
                assertThat(LegacyLayoutRefusingDatasetLifecycle.holdsLegacyLayout(handle.sparqlQuery())).isTrue();
                assertThat(handle.sparqlQuery().ask("ASK { GRAPH <" + ContentGraph.GRAPH_IRI + "> { ?s ?p ?o } }"))
                        .isFalse();
            }
        } finally {
            raw.shutDownAll();
        }
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
