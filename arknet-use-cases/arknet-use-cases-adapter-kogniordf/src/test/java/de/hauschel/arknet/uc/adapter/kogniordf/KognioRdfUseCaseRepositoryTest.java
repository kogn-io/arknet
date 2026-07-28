// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Integration test for {@link KognioRdfUseCaseRepository} against an in-memory RDF4J-backed
 * kognio-rdf store. Requirement and actor resources referenced by a use case are seeded
 * directly into the same workspace graphs (as the requirements / ubiquitous-language adapters
 * would write them), so the write path can be exercised without a cross-bounded-context test
 * dependency.
 *
 * <p>Identity is opaque (issue #72): the use-case subject IRI is minted above the store and
 * carried on the {@link UseCase}; the human-readable {@link UseCaseCode} ({@code UC1}) is a
 * separate {@code dcterms:identifier} triple and is what a caller looks up by.</p>
 *
 * <p><strong>References arrive pre-resolved (issue #89).</strong> {@link ActorRef}/
 * {@link RequirementRef} now carry the referenced resource's opaque {@link ResourceId} directly
 * - resolving a human-typed label against the shared store is no longer this repository's job
 * (it moved to {@code KognioRdfActorLookup}/{@code KognioRdfRequirementLookup}, exercised in
 * {@code KognioRdfActorLookupTest}/{@code KognioRdfRequirementLookupTest}). This test therefore no
 * longer pins unknown/ambiguous-label rejection at the repository level - that behaviour now
 * lives exclusively in the two lookup adapter tests, mirroring how
 * {@code KognioRdfRequirementRepositoryTest} dropped its own resolution-rejection tests once
 * issue #77 moved {@code usesTerm} resolution out of the requirement repository's write path.</p>
 */
class KognioRdfUseCaseRepositoryTest {

    private static final ProjectId WORKSPACE_A = new ProjectId("a");
    private static final ProjectId WORKSPACE_B = new ProjectId("b");

    private static final String USE_CASES_GRAPH = "https://w3id.org/arknet/model/use-cases";
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";

    private static final UseCaseId ID_1 = new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/uc-1"));
    private static final UseCaseId ID_2 = new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/uc-2"));
    private static final UseCaseCode CODE_1 = new UseCaseCode("UC1");
    private static final UseCaseCode CODE_2 = new UseCaseCode("UC2");

    private static final ResourceId FR_1 = ResourceId.of("https://w3id.org/arknet/model/requirement/FR-1");
    private static final ActorRef CUSTOMER = new ActorRef(ResourceId.of("https://w3id.org/arknet/model/term/customer"));
    private static final ActorRef PAYMENT_PROVIDER =
            new ActorRef(ResourceId.of("https://w3id.org/arknet/model/term/payment-provider"));
    private static final RequirementRef FR_1_REF = new RequirementRef(FR_1);

    private DatasetLifecycleRdf4j lifecycle;
    private UseCaseRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-uc-it");
        lifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        repository = KognioRdfUseCaseRepositoryFactory.over(
                lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private void seed(ProjectId workspace, String graph, String triples) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspace.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <" + graph + "> { " + triples + " } }");
                return null;
            });
        }
    }

    /** Counts {@code arkreq:Step} resources in the use-cases graph - guards delete-by-edge. */
    private long countSteps(ProjectId workspace) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspace.value()))) {
            return handle.sparqlQuery().select("SELECT ?s WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                    + "?s a <https://w3id.org/arknet/requirements#Step> } }").count();
        }
    }

    private void seedRequirement(ProjectId workspace, String label) {
        seed(workspace, REQUIREMENTS_GRAPH,
                "<https://w3id.org/arknet/model/requirement/" + label + "> "
                        + "a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                        + "<http://purl.org/dc/terms/identifier> \"" + label + "\" .");
    }

    private void seedHumanActor(ProjectId workspace, String slug, String prefLabel) {
        seed(workspace, TERMS_GRAPH,
                "<https://w3id.org/arknet/model/term/" + slug + "> "
                        + "a <http://www.w3.org/2004/02/skos/core#Concept> , "
                        + "<https://w3id.org/arknet/process#HumanActor> ; "
                        + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"" + prefLabel + "\" .");
    }

    private void seedSystemActor(ProjectId workspace, String slug, String prefLabel) {
        seed(workspace, TERMS_GRAPH,
                "<https://w3id.org/arknet/model/term/" + slug + "> "
                        + "a <http://www.w3.org/2004/02/skos/core#Concept> , "
                        + "<https://w3id.org/arknet/process#SystemActor> ; "
                        + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"" + prefLabel + "\" .");
    }

    private void seedReferences(ProjectId workspace) {
        seedRequirement(workspace, "FR-1");
        seedHumanActor(workspace, "customer", "Customer");
        seedSystemActor(workspace, "payment-provider", "PaymentProvider");
    }

    private static UseCase placeOrder() {
        return placeOrder(ID_1, CODE_1);
    }

    private static UseCase placeOrder(UseCaseId id, UseCaseCode code) {
        return new UseCase(
                id, code, "Place order", "Customer places an order",
                "Webshop", "Customer opens the cart", CUSTOMER,
                List.of(PAYMENT_PROVIDER), "Customer is logged in", "Order is recorded",
                List.of(
                        new Step(1, "Customer selects items", List.of(FR_1_REF)),
                        new Step(2, "Customer confirms and pays", List.of())),
                List.of("2a. Payment declined -> use case ends in failure"));
    }

    @Test
    void createsAndFindsUseCaseByCodeWithStepsAndReferences() {
        seedReferences(WORKSPACE_A);

        repository.create(WORKSPACE_A, placeOrder());
        Optional<UseCase> found = repository.findByCode(WORKSPACE_A, CODE_1);

        assertTrue(found.isPresent());
        UseCase uc = found.orElseThrow();
        assertEquals(ID_1, uc.id());
        assertEquals(CODE_1, uc.code());
        assertEquals("Place order", uc.title());
        assertEquals("Customer places an order", uc.goal());
        assertEquals("Webshop", uc.scope());
        assertEquals("Customer opens the cart", uc.trigger());
        assertEquals(CUSTOMER, uc.primaryActor());
        assertEquals(List.of(PAYMENT_PROVIDER), uc.supportingActors());
        assertEquals("Customer is logged in", uc.precondition());
        assertEquals("Order is recorded", uc.postcondition());
        assertEquals(2, uc.steps().size());
        assertEquals(1, uc.steps().get(0).position());
        assertEquals("Customer selects items", uc.steps().get(0).text());
        assertEquals(List.of(FR_1_REF), uc.steps().get(0).realises());
        assertEquals(2, uc.steps().get(1).position());
        assertEquals(List.of(), uc.steps().get(1).realises());
        assertEquals(List.of("2a. Payment declined -> use case ends in failure"), uc.extensions());
    }

    @Test
    void findAllContainsAllSavedUseCases() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        UseCase second = new UseCase(ID_2, CODE_2, "Reset password", "User resets password",
                null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "User requests a reset link", List.of())), List.of());
        repository.create(WORKSPACE_A, second);

        List<UseCase> all = repository.findAll(WORKSPACE_A);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(uc -> uc.code().equals(CODE_1)));
        assertTrue(all.stream().anyMatch(uc -> uc.code().equals(CODE_2)));
    }

    @Test
    void updateReplacesByIdentityAndLeavesNoOrphanSteps() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());
        // placeOrder has 2 main steps + 1 extension step = 3 step resources.
        assertEquals(3, countSteps(WORKSPACE_A));

        UseCase revised = new UseCase(ID_1, CODE_1, "Place order (revised)", "Customer places an order",
                null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "Customer selects items", List.of())), List.of());
        repository.update(WORKSPACE_A, revised);

        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        UseCase found = repository.findByCode(WORKSPACE_A, CODE_1).orElseThrow();
        assertEquals("Place order (revised)", found.title());
        assertEquals(1, found.steps().size());
        assertTrue(found.supportingActors().isEmpty());
        assertTrue(found.extensions().isEmpty());
        // The old opaque step resources must be gone - delete follows mainStep/extensionStep edges.
        assertEquals(1, countSteps(WORKSPACE_A));
    }

    @Test
    void createRejectsExistingIdentity() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        ResourceAlreadyExistsException ex = assertThrows(ResourceAlreadyExistsException.class,
                () -> repository.create(WORKSPACE_A, placeOrder(ID_1, CODE_2)));

        assertEquals(ID_1.value(), ex.id());
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
    }

    @Test
    void createRejectsDuplicateCode() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        DuplicateUseCaseCodeException ex = assertThrows(DuplicateUseCaseCodeException.class,
                () -> repository.create(WORKSPACE_A, placeOrder(ID_2, CODE_1)));

        assertEquals(CODE_1, ex.code());
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
    }

    @Test
    void updateRejectsMissingIdentity() {
        seedReferences(WORKSPACE_A);

        assertThrows(UseCaseNotFoundException.class,
                () -> repository.update(WORKSPACE_A, placeOrder()));

        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void findByCodeReturnsEmptyForUnknownCode() {
        assertEquals(Optional.empty(), repository.findByCode(WORKSPACE_A, new UseCaseCode("UC99")));
    }

    @Test
    void workspacesAreIsolated() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        assertTrue(repository.findAll(WORKSPACE_B).isEmpty());
    }

    /**
     * Regression test (issue #89) for the bug this issue fixes: a use case whose primary
     * actor's {@code skos:prefLabel} is deleted after creation must still be readable in full -
     * the reference no longer depends on the label at all, unlike the old read path, which
     * mandatorily joined into the terms graph on {@code skos:prefLabel} and silently dropped the
     * whole use case ({@code findByCode} returned empty, {@code findAll} dropped it via
     * {@code Optional::ifPresent}) the moment that join failed to bind.
     */
    @Test
    void useCaseSurvivesItsPrimaryActorsPrefLabelBeingDeleted() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        deletePrefLabel(WORKSPACE_A, CUSTOMER.value());

        Optional<UseCase> byCode = repository.findByCode(WORKSPACE_A, CODE_1);
        assertTrue(byCode.isPresent(), "findByCode must still return the use case");
        assertEquals(CUSTOMER, byCode.orElseThrow().primaryActor());

        List<UseCase> all = repository.findAll(WORKSPACE_A);
        assertEquals(1, all.size(), "findAll must not silently drop the use case");
        assertEquals(CUSTOMER, all.get(0).primaryActor());
    }

    /**
     * Same regression, but for a rename rather than a deletion: relabelling the actor after the
     * use case was created must not affect the reference, since the edge's target IRI - not the
     * label - is the {@link ActorRef}.
     */
    @Test
    void useCaseSurvivesItsPrimaryActorBeingRenamed() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        renamePrefLabel(WORKSPACE_A, CUSTOMER.value(), "Customer", "Kunde");

        UseCase found = repository.findByCode(WORKSPACE_A, CODE_1).orElseThrow();
        assertEquals(CUSTOMER, found.primaryActor());
    }

    private void deletePrefLabel(ProjectId workspace, ResourceId subject) {
        String delete = "DELETE WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "<" + subject.value() + "> <http://www.w3.org/2004/02/skos/core#prefLabel> ?label } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspace.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(delete);
                return null;
            });
        }
    }

    private void renamePrefLabel(ProjectId workspace, ResourceId subject, String oldLabel, String newLabel) {
        String update = "DELETE { GRAPH <" + TERMS_GRAPH + "> { <" + subject.value()
                + "> <http://www.w3.org/2004/02/skos/core#prefLabel> \"" + oldLabel + "\" } } "
                + "INSERT { GRAPH <" + TERMS_GRAPH + "> { <" + subject.value()
                + "> <http://www.w3.org/2004/02/skos/core#prefLabel> \"" + newLabel + "\" } } WHERE {}";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspace.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(update);
                return null;
            });
        }
    }

    /**
     * Regression test for the {@code primaryActor} blank-node bug found in the #95 review:
     * {@code arkreq:primaryActor} carries no {@code sh:nodeKind} constraint, so a store-first
     * (ADR-005) use case may legally target a blank node with it. Reading such a use case back
     * must not throw a {@link ClassCastException} out of {@code readBySubject} - and, because
     * {@code primaryActor} is a required (non-{@code OPTIONAL}) triple pattern in the scalar
     * read, the malformed use case is treated as "not found" rather than crashing the rest of
     * {@link UseCaseRepository#findAll}'s result list.
     */
    @Test
    void findAllSkipsUseCaseWithBlankNodePrimaryActorInsteadOfFailingTheWholeList() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        UseCaseCode orphanCode = new UseCaseCode("UC-ORPHAN");
        seed(WORKSPACE_A, USE_CASES_GRAPH,
                "<https://w3id.org/arknet/id/uc-orphan> "
                        + "a <https://w3id.org/arknet/requirements#UseCase> ; "
                        + "<http://purl.org/dc/terms/title> \"Orphan use case\" ; "
                        + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Some goal\" ; "
                        + "<https://w3id.org/arknet/requirements#primaryActor> _:orphanActor ; "
                        + "<http://purl.org/dc/terms/identifier> \"" + orphanCode.value() + "\" .");

        List<UseCase> all = repository.findAll(WORKSPACE_A);
        assertEquals(1, all.size());
        assertEquals(CODE_1, all.get(0).code());

        assertEquals(Optional.empty(), repository.findByCode(WORKSPACE_A, orphanCode));
    }

    /**
     * Regression test (issue #102, Befund 1): {@code arkreq:mainStep} is only
     * {@code sh:Warning} severity at {@code sh:minCount 1} in the SHACL shapes, so
     * {@link ShaclWriteGate#enforce} lets a store-first (ADR-005) use case with zero main-step
     * triples through. Reading such a use case back must not let {@link UseCase}'s "at least one
     * step" invariant throw out of {@code readBySubject} - mirroring the blank-node
     * {@code primaryActor} guard, the malformed use case is treated as "not found" rather than
     * crashing the rest of {@link UseCaseRepository#findAll}'s result list.
     */
    @Test
    void findAllSkipsUseCaseWithNoStepsInsteadOfFailingTheWholeList() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        UseCaseCode noStepsCode = new UseCaseCode("UC-NO-STEPS");
        seed(WORKSPACE_A, USE_CASES_GRAPH,
                "<https://w3id.org/arknet/id/uc-no-steps> "
                        + "a <https://w3id.org/arknet/requirements#UseCase> ; "
                        + "<http://purl.org/dc/terms/title> \"No steps use case\" ; "
                        + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Some goal\" ; "
                        + "<https://w3id.org/arknet/requirements#primaryActor> "
                        + "<" + CUSTOMER.value().value() + "> ; "
                        + "<http://purl.org/dc/terms/identifier> \"" + noStepsCode.value() + "\" .");

        List<UseCase> all = repository.findAll(WORKSPACE_A);
        assertEquals(1, all.size());
        assertEquals(CODE_1, all.get(0).code());

        assertEquals(Optional.empty(), repository.findByCode(WORKSPACE_A, noStepsCode));
    }

    /**
     * Regression test (issue #102, Befund 2): nothing in SHACL prevents two distinct
     * {@code arkreq:Step} nodes under the same use case's {@code arkreq:mainStep} from sharing
     * the same {@code arkreq:position} - uniqueness is only enforced in-process by
     * {@code UseCase.requireConsecutiveStepPositions}, and store-first data (ADR-005) never runs
     * through that. Correlating a step's {@code arkreq:stepRealises} edges by the derived
     * position integer instead of the step's own IRI would silently merge the two steps'
     * requirement references under one key, then throw a duplicate-position
     * {@link IllegalArgumentException} out of {@link UseCase}'s constructor - crashing the rest
     * of {@link UseCaseRepository#findAll}'s result list, the same class of bug issue #89 already
     * fixed for {@code supportingActor}/{@code stepRealises} elsewhere in this adapter.
     */
    @Test
    void findAllSkipsUseCaseWithDuplicateStepPositionsInsteadOfFailingTheWholeList() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        UseCaseCode duplicatePositionCode = new UseCaseCode("UC-DUP-POSITION");
        seed(WORKSPACE_A, USE_CASES_GRAPH,
                "<https://w3id.org/arknet/id/uc-dup-position> "
                        + "a <https://w3id.org/arknet/requirements#UseCase> ; "
                        + "<http://purl.org/dc/terms/title> \"Duplicate position use case\" ; "
                        + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Some goal\" ; "
                        + "<https://w3id.org/arknet/requirements#primaryActor> "
                        + "<" + CUSTOMER.value().value() + "> ; "
                        + "<https://w3id.org/arknet/requirements#mainStep> "
                        + "<https://w3id.org/arknet/id/uc-dup-position-step-1> , "
                        + "<https://w3id.org/arknet/id/uc-dup-position-step-2> ; "
                        + "<http://purl.org/dc/terms/identifier> \"" + duplicatePositionCode.value() + "\" .");
        seed(WORKSPACE_A, USE_CASES_GRAPH,
                "<https://w3id.org/arknet/id/uc-dup-position-step-1> "
                        + "a <https://w3id.org/arknet/requirements#Step> ; "
                        + "<https://w3id.org/arknet/requirements#position> \"1\"^^<"
                        + XSD.INTEGER + "> ; "
                        + "<https://w3id.org/arknet/requirements#stepText> \"Customer selects items\" ; "
                        + "<https://w3id.org/arknet/requirements#stepRealises> <" + FR_1.value() + "> .");
        seed(WORKSPACE_A, USE_CASES_GRAPH,
                "<https://w3id.org/arknet/id/uc-dup-position-step-2> "
                        + "a <https://w3id.org/arknet/requirements#Step> ; "
                        + "<https://w3id.org/arknet/requirements#position> \"1\"^^<"
                        + XSD.INTEGER + "> ; "
                        + "<https://w3id.org/arknet/requirements#stepText> \"Customer confirms and pays\" .");

        List<UseCase> all = repository.findAll(WORKSPACE_A);
        assertEquals(1, all.size());
        assertEquals(CODE_1, all.get(0).code());

        assertEquals(Optional.empty(), repository.findByCode(WORKSPACE_A, duplicatePositionCode));
    }

    @Test
    void createRejectsStepViolatingShaclShapes() {
        seedRequirement(WORKSPACE_A, "FR-1");
        seedHumanActor(WORKSPACE_A, "customer", "Customer");

        // stepText "ok" is non-blank (valid domain) but below the shape's minLength of 3.
        UseCase invalid = new UseCase(ID_1, CODE_1, "Bad", "Some goal", null, null,
                CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "ok", List.of(FR_1_REF))), List.of());

        assertThrows(WriteConstraintViolationException.class,
                () -> repository.create(WORKSPACE_A, invalid));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    /**
     * Regression test for issue #82: {@code rshapes:UseCase-primaryActor} now carries
     * {@code sh:maxCount 1}. A second {@code primaryActor} is unreachable through
     * {@link UseCaseRepository#create} - {@link UseCase#primaryActor()} is a single-valued
     * field - so this exercises the wired gate directly against a synthetic candidate graph, the
     * way a store-first (ADR-005) write could still produce two triples.
     */
    @Test
    void gateRejectsUseCaseWithTwoPrimaryActors() {
        ValueFactory vf = SimpleValueFactory.getInstance();
        IRI useCase = vf.createIRI("https://w3id.org/arknet/id/uc-two-primary-actors");
        IRI actor1 = vf.createIRI("https://w3id.org/arknet/model/term/actor-1");
        IRI actor2 = vf.createIRI("https://w3id.org/arknet/model/term/actor-2");
        IRI useCaseClass = vf.createIRI("https://w3id.org/arknet/requirements#UseCase");
        IRI actorClass = vf.createIRI("https://w3id.org/arknet/process#Actor");
        IRI primaryActor = vf.createIRI("https://w3id.org/arknet/requirements#primaryActor");

        Model twoPrimaryActors = new LinkedHashModel();
        twoPrimaryActors.add(useCase, RDF.TYPE, useCaseClass);
        twoPrimaryActors.add(useCase, DCTERMS.IDENTIFIER, vf.createLiteral("UC-TWO-PRIMARY-ACTORS"));
        twoPrimaryActors.add(useCase, primaryActor, actor1);
        twoPrimaryActors.add(useCase, primaryActor, actor2);
        twoPrimaryActors.add(actor1, RDF.TYPE, actorClass);
        twoPrimaryActors.add(actor2, RDF.TYPE, actorClass);

        ShaclWriteGate gate = KognioRdfUseCaseRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(new RDF4JGraph(twoPrimaryActors)));
    }

    /**
     * Issue #99: {@code rshapes:UseCase-title} is a new shape ({@code dcterms:title} had none
     * before). {@link UseCase#title()} is single-valued, so a second title is unreachable through
     * {@link UseCaseRepository#create}, same rationale as
     * {@link #gateRejectsUseCaseWithTwoPrimaryActors}.
     */
    @Test
    void gateRejectsUseCaseWithTwoTitles() {
        ValueFactory vf = SimpleValueFactory.getInstance();
        IRI useCase = vf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI actor = vf.createIRI("https://w3id.org/arknet/model/term/actor-1");
        IRI useCaseClass = vf.createIRI("https://w3id.org/arknet/requirements#UseCase");
        IRI actorClass = vf.createIRI("https://w3id.org/arknet/process#Actor");
        IRI primaryActor = vf.createIRI("https://w3id.org/arknet/requirements#primaryActor");

        Model twoTitles = new LinkedHashModel();
        twoTitles.add(useCase, RDF.TYPE, useCaseClass);
        twoTitles.add(useCase, DCTERMS.IDENTIFIER, vf.createLiteral("UC-1"));
        twoTitles.add(useCase, DCTERMS.TITLE, vf.createLiteral("Place order"));
        twoTitles.add(useCase, DCTERMS.TITLE, vf.createLiteral("Submit order"));
        twoTitles.add(useCase, primaryActor, actor);
        twoTitles.add(actor, RDF.TYPE, actorClass);

        ShaclWriteGate gate = KognioRdfUseCaseRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(new RDF4JGraph(twoTitles)));
        assertTrue(ex.getMessage().contains("title"), ex.getMessage());
    }

    /**
     * Issue #99: {@code rshapes:UseCase-goal-count} is a new, {@code sh:Violation} shape carrying
     * only the {@code sh:maxCount 1} - split out from the pre-existing {@code rshapes:UseCase-goal}
     * (which stays a {@code sh:Warning} best-practice check on presence, unchanged), for the same
     * reason as {@code rshapes:Requirement-motivatedBy-count} in
     * {@code KognioRdfRequirementRepositoryTest}: a {@code sh:Warning}-severity {@code maxCount}
     * never fires {@link WriteConstraintViolationException}. {@link UseCase#goal()} is
     * single-valued, so a second value is unreachable through {@link UseCaseRepository#create}.
     */
    @Test
    void gateRejectsUseCaseWithTwoGoals() {
        ValueFactory vf = SimpleValueFactory.getInstance();
        IRI useCase = vf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI actor = vf.createIRI("https://w3id.org/arknet/model/term/actor-1");
        IRI useCaseClass = vf.createIRI("https://w3id.org/arknet/requirements#UseCase");
        IRI actorClass = vf.createIRI("https://w3id.org/arknet/process#Actor");
        IRI primaryActor = vf.createIRI("https://w3id.org/arknet/requirements#primaryActor");
        IRI useCaseGoal = vf.createIRI("https://w3id.org/arknet/requirements#useCaseGoal");

        Model twoGoals = new LinkedHashModel();
        twoGoals.add(useCase, RDF.TYPE, useCaseClass);
        twoGoals.add(useCase, DCTERMS.IDENTIFIER, vf.createLiteral("UC-1"));
        twoGoals.add(useCase, DCTERMS.TITLE, vf.createLiteral("Place order"));
        twoGoals.add(useCase, useCaseGoal, vf.createLiteral("Customer wants the order placed quickly"));
        twoGoals.add(useCase, useCaseGoal, vf.createLiteral("Customer wants a confirmation email"));
        twoGoals.add(useCase, primaryActor, actor);
        twoGoals.add(actor, RDF.TYPE, actorClass);

        ShaclWriteGate gate = KognioRdfUseCaseRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(new RDF4JGraph(twoGoals)));
        assertTrue(ex.getMessage().contains("useCaseGoal"), ex.getMessage());
    }

    /**
     * Issue #99: {@code rshapes:Step-text} now carries {@code sh:maxCount 1}. {@link Step#text()}
     * is single-valued, so a second {@code stepText} is unreachable through
     * {@link UseCaseRepository#create} - exercised directly against a synthetic {@code arkreq:Step}
     * candidate graph, since a step has no standalone read/write entry point of its own (it is
     * only ever reached through its owning {@link UseCase}).
     */
    @Test
    void gateRejectsStepWithTwoTexts() {
        ValueFactory vf = SimpleValueFactory.getInstance();
        IRI step = vf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI stepClass = vf.createIRI("https://w3id.org/arknet/requirements#Step");
        IRI position = vf.createIRI("https://w3id.org/arknet/requirements#position");
        IRI stepText = vf.createIRI("https://w3id.org/arknet/requirements#stepText");

        Model twoTexts = new LinkedHashModel();
        twoTexts.add(step, RDF.TYPE, stepClass);
        twoTexts.add(step, position, vf.createLiteral("1", XSD.INTEGER));
        twoTexts.add(step, stepText, vf.createLiteral("Customer places the order"));
        twoTexts.add(step, stepText, vf.createLiteral("Customer submits the order"));

        ShaclWriteGate gate = KognioRdfUseCaseRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(new RDF4JGraph(twoTexts)));
        assertTrue(ex.getMessage().contains("stepText"), ex.getMessage());
    }

    // ---- revision trail (ADR-014): one revision per write, head queryable ----------------

    /**
     * ADR-014 revision basis for this bounded context's funnel write paths: {@code create} and
     * {@code update} each record exactly one immutable revision of the use case, and the head
     * is queryable per resource. The step resources the body writes alongside get no revisions
     * of their own - the revision hangs off the funnel's subject, the use case.
     *
     * <p><strong>How far this evidence reaches.</strong> {@code update} is called here directly
     * on the out-port. This bounded context has no in-port reaching it at all - {@code
     * UseCaseService} only ever calls {@code create}, and there is no {@code uc_update} tool -
     * so today every use case's head stays on its create revision. This test proves the funnel
     * behaves on {@code update}, ahead of a write path that would use it.</p>
     */
    @Test
    void createAndUpdateEachRecordExactlyOneRevisionWithAQueryableHead() {
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());
        String subject = ID_1.value().value();

        assertEquals(1, revisionsOf(subject).size(), "create must record exactly one revision");

        UseCase revised = new UseCase(ID_1, CODE_1, "Place order (revised)", "Customer places an order",
                null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "Customer selects items", List.of())), List.of());
        repository.update(WORKSPACE_A, revised);

        List<String> revisions = revisionsOf(subject);
        assertEquals(2, revisions.size(), "update must record exactly one more revision");
        List<String> heads = selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH
                + "> { <" + subject + "> <" + ArkprovVocabulary.HEAD + "> ?v } }");
        assertEquals(1, heads.size(), "the head is rewritten, never duplicated");
        assertTrue(revisions.contains(heads.get(0)), "the head must be one of the resource's revisions");
    }

    private List<String> revisionsOf(String subjectIri) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?v a <" + ArkprovVocabulary.REVISION_TYPE + "> ; "
                + "<" + ArkprovVocabulary.SPECIALIZATION_OF + "> <" + subjectIri + "> } }");
    }

    private List<String> selectIris(String query) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(WORKSPACE_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((io.kogn.rdf.terms.IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .toList();
        }
    }
}
