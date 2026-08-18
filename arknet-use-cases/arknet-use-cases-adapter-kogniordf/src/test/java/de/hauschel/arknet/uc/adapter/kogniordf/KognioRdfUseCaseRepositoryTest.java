// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
import org.junit.jupiter.api.io.TempDir;

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
import de.hauschel.arknet.uc.application.port.out.RevisionToken;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.ConstraintRef;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.TermRef;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseConcurrentlyModifiedException;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Integration test for {@link KognioRdfUseCaseRepository} against an in-memory RDF4J-backed
 * kognio-rdf store. Requirement and actor resources referenced by a use case are seeded
 * directly into the same project graphs (as the requirements / ubiquitous-language adapters
 * would write them), so the write path can be exercised without a cross-bounded-context test
 * dependency.
 *
 * <p>Identity is opaque: the use-case subject IRI is minted above the store and
 * carried on the {@link UseCase}; the human-readable {@link UseCaseCode} ({@code UC1}) is a
 * separate {@code dcterms:identifier} triple and is what a caller looks up by.</p>
 *
 * <p><strong>References arrive pre-resolved.</strong> {@link ActorRef}/
 * {@link RequirementRef} now carry the referenced resource's opaque {@link ResourceId} directly
 * - resolving a human-typed label against the shared store is no longer this repository's job
 * (it moved to {@code KognioRdfActorLookup}/{@code KognioRdfRequirementLookup}, exercised in
 * {@code KognioRdfActorLookupTest}/{@code KognioRdfRequirementLookupTest}). This test therefore no
 * longer pins unknown/ambiguous-label rejection at the repository level - that behaviour now
 * lives exclusively in the two lookup adapter tests, mirroring how
 * {@code KognioRdfRequirementRepositoryTest} dropped its own resolution-rejection tests once
 * {@code usesTerm} resolution moved out of the requirement repository's write path.</p>
 */
class KognioRdfUseCaseRepositoryTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");

    private static final String USE_CASES_GRAPH = "https://w3id.org/arknet/model/use-cases";
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";
    private static final String CONSTRAINTS_GRAPH = "https://w3id.org/arknet/model/constraints";

    private static final UseCaseId ID_1 = new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/uc-1"));
    private static final UseCaseId ID_2 = new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/uc-2"));
    private static final UseCaseCode CODE_1 = new UseCaseCode("UC1");
    private static final UseCaseCode CODE_2 = new UseCaseCode("UC2");

    private static final ResourceId FR_1 = ResourceId.of("https://w3id.org/arknet/model/requirement/FR-1");
    private static final ActorRef CUSTOMER = new ActorRef(ResourceId.of("https://w3id.org/arknet/model/term/customer"));
    private static final ActorRef PAYMENT_PROVIDER =
            new ActorRef(ResourceId.of("https://w3id.org/arknet/model/term/payment-provider"));
    private static final RequirementRef FR_1_REF = new RequirementRef(FR_1);
    private static final ResourceId TERM_1 = ResourceId.of("https://w3id.org/arknet/model/term/term-1");
    private static final TermRef TERM_1_REF = new TermRef(TERM_1);
    private static final ResourceId TCON_1 = ResourceId.of("https://w3id.org/arknet/model/constraint/tcon-1");
    private static final ConstraintRef TCON_1_REF = new ConstraintRef(TCON_1);

    /**
     * The store's on-disk home, managed by JUnit rather than {@code Files.createTempDirectory},
     * which left its directories behind - harmless while the store is {@code IN_MEMORY}, but
     * still an inode left in {@code /tmp} for every test run. Deleted after {@link #tearDown()}
     * has shut the store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private UseCaseRepository repository;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        repository = KognioRdfUseCaseRepositoryFactory.over(
                lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    /**
     * Test convenience for call sites that only need "replace this by identity" and do not
     * exercise the compare-and-set guard itself: reads {@code updated}'s current
     * head via {@link UseCaseRepository#findCurrentByCode} and immediately applies {@code updated}
     * through it - there is no unconditional {@code update} left on the port. Mirrors
     * {@code KognioRdfRequirementRepositoryTest#replaceViaCompareAndUpdate}.
     */
    private void replaceViaCompareAndUpdate(ProjectId projectId, UseCase updated) {
        RevisionToken head = repository.findCurrentByCode(projectId, updated.code())
                .map(UseCaseRepository.CurrentUseCase::head)
                .orElse(null);
        repository.compareAndUpdate(projectId, head, updated, null, null, null, null, null, null,
                java.util.Map.of(), java.util.Map.of(), null, Integer.MAX_VALUE);
    }

    private void seed(ProjectId project, String graph, String triples) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <" + graph + "> { " + triples + " } }");
                return null;
            });
        }
    }

    /** Counts {@code arkreq:Step} resources in the use-cases graph - guards delete-by-edge. */
    private long countSteps(ProjectId project) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.value()))) {
            return handle.sparqlQuery().select("SELECT ?s WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                    + "?s a <https://w3id.org/arknet/requirements#Step> } }").count();
        }
    }

    private void seedRequirement(ProjectId project, String label) {
        seed(project, REQUIREMENTS_GRAPH,
                "<https://w3id.org/arknet/model/requirement/" + label + "> "
                        + "a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                        + "<http://purl.org/dc/terms/identifier> \"" + label + "\" .");
    }

    private void seedHumanActor(ProjectId project, String slug, String prefLabel) {
        seed(project, TERMS_GRAPH,
                "<https://w3id.org/arknet/model/term/" + slug + "> "
                        + "a <http://www.w3.org/2004/02/skos/core#Concept> , "
                        + "<https://w3id.org/arknet/process#HumanActor> ; "
                        + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"" + prefLabel + "\" .");
    }

    private void seedSystemActor(ProjectId project, String slug, String prefLabel) {
        seed(project, TERMS_GRAPH,
                "<https://w3id.org/arknet/model/term/" + slug + "> "
                        + "a <http://www.w3.org/2004/02/skos/core#Concept> , "
                        + "<https://w3id.org/arknet/process#SystemActor> ; "
                        + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"" + prefLabel + "\" .");
    }

    private void seedReferences(ProjectId project) {
        seedRequirement(project, "FR-1");
        seedHumanActor(project, "customer", "Customer");
        seedSystemActor(project, "payment-provider", "PaymentProvider");
    }

    private void seedTerm(ProjectId project, String slug, String prefLabel) {
        seed(project, TERMS_GRAPH,
                "<https://w3id.org/arknet/model/term/" + slug + "> "
                        + "a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                        + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"" + prefLabel + "\" .");
    }

    private void seedConstraint(ProjectId project, String slug) {
        seed(project, CONSTRAINTS_GRAPH,
                "<https://w3id.org/arknet/model/constraint/" + slug + "> "
                        + "a <https://w3id.org/arknet/requirements#TechnicalConstraint> ; "
                        + "<http://purl.org/dc/terms/title> \"" + slug + "\" ; "
                        + "<https://w3id.org/arknet/requirements#constraintStatement> \"Must hold.\" .");
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
                List.of("2a. Payment declined -> use case ends in failure"), List.of(), List.of());
    }

    @Test
    void createsAndFindsUseCaseByCodeWithStepsAndReferences() {
        seedReferences(PROJECT_A);

        repository.create(PROJECT_A, placeOrder(), null);
        Optional<UseCase> found = repository.findByCode(PROJECT_A, CODE_1, null);

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
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        UseCase second = new UseCase(ID_2, CODE_2, "Reset password", "User resets password",
                null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "User requests a reset link", List.of())), List.of(), List.of(), List.of());
        repository.create(PROJECT_A, second, null);

        List<UseCase> all = repository.findAll(PROJECT_A, null);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(uc -> uc.code().equals(CODE_1)));
        assertTrue(all.stream().anyMatch(uc -> uc.code().equals(CODE_2)));
    }

    @Test
    void updateReplacesByIdentityAndLeavesNoOrphanSteps() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);
        // placeOrder has 2 main steps + 1 extension step = 3 step resources.
        assertEquals(3, countSteps(PROJECT_A));

        UseCase revised = new UseCase(ID_1, CODE_1, "Place order (revised)", "Customer places an order",
                null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "Customer selects items", List.of())), List.of(), List.of(), List.of());
        replaceViaCompareAndUpdate(PROJECT_A, revised);

        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        UseCase found = repository.findByCode(PROJECT_A, CODE_1, null).orElseThrow();
        assertEquals("Place order (revised)", found.title());
        assertEquals(1, found.steps().size());
        assertTrue(found.supportingActors().isEmpty());
        assertTrue(found.extensions().isEmpty());
        // The old opaque step resources must be gone - delete follows mainStep/extensionStep edges.
        assertEquals(1, countSteps(PROJECT_A));
    }

    /**
     * {@code compareAndUpdate} rebuilds {@code stepRealises} wholesale from {@code steps()} on
     * every write (issue #255's {@code uc_update} realises-correction path is built entirely on
     * this existing behaviour, with no repository change): a step that had no {@code realises}
     * reference can gain one, and a step that had one can lose it, in the same call.
     */
    @Test
    void compareAndUpdateReplacesAStepsRealisesWholesaleAddingAndClearingInTheSameWrite() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        UseCase revised = new UseCase(ID_1, CODE_1, "Place order", "Customer places an order",
                "Webshop", "Customer opens the cart", CUSTOMER,
                List.of(PAYMENT_PROVIDER), "Customer is logged in", "Order is recorded",
                List.of(
                        new Step(1, "Customer selects items", List.of()),
                        new Step(2, "Customer confirms and pays", List.of(FR_1_REF))),
                List.of("2a. Payment declined -> use case ends in failure"), List.of(), List.of());
        replaceViaCompareAndUpdate(PROJECT_A, revised);

        UseCase found = repository.findByCode(PROJECT_A, CODE_1, null).orElseThrow();
        assertEquals(List.of(), found.steps().get(0).realises());
        assertEquals(List.of(FR_1_REF), found.steps().get(1).realises());
    }

    @Test
    void createRejectsExistingIdentity() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        ResourceAlreadyExistsException ex = assertThrows(ResourceAlreadyExistsException.class,
                () -> repository.create(PROJECT_A, placeOrder(ID_1, CODE_2), null));

        assertEquals(ID_1.value(), ex.id());
        assertEquals(1, repository.findAll(PROJECT_A, null).size());
    }

    @Test
    void createRejectsDuplicateCode() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        DuplicateUseCaseCodeException ex = assertThrows(DuplicateUseCaseCodeException.class,
                () -> repository.create(PROJECT_A, placeOrder(ID_2, CODE_1), null));

        assertEquals(CODE_1, ex.code());
        assertEquals(1, repository.findAll(PROJECT_A, null).size());
    }

    @Test
    void updateRejectsMissingIdentity() {
        seedReferences(PROJECT_A);

        assertThrows(UseCaseNotFoundException.class,
                () -> repository.compareAndUpdate(PROJECT_A, null, placeOrder(), null, null, null, null, null, null,
                        java.util.Map.of(), java.util.Map.of(), null, Integer.MAX_VALUE));

        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
    }

    // ---- compareAndUpdate: CAS guard against lost updates ----

    @Test
    void compareAndUpdateAppliesWhenExpectedHeadMatchesTheStoredHead() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);
        RevisionToken head = repository.findCurrentByCode(PROJECT_A, CODE_1).orElseThrow().head();

        UseCase revised = new UseCase(ID_1, CODE_1, "Place order (revised)", "Customer places an order",
                null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "Customer selects items", List.of())), List.of(), List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, head, revised, null, null, null, null, null, null,
                java.util.Map.of(), java.util.Map.of(), null, Integer.MAX_VALUE);

        assertEquals(Optional.of(revised), repository.findByCode(PROJECT_A, CODE_1, null));
    }

    /**
     * A stale {@code expectedHead} (no longer matching the head another writer already advanced)
     * must be rejected without mutating the store - the caller re-reads and retries instead of
     * silently overwriting the concurrent change. Mirrors
     * {@code KognioRdfRequirementRepositoryTest#compareAndUpdateThrowsAndPersistsNothingWhenExpectedHeadIsStale}.
     */
    @Test
    void compareAndUpdateThrowsAndPersistsNothingWhenExpectedHeadIsStale() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);
        RevisionToken staleHead = repository.findCurrentByCode(PROJECT_A, CODE_1).orElseThrow().head();
        // Simulates a concurrent writer that already committed a change since staleHead was read.
        UseCase concurrentlyRevised = new UseCase(ID_1, CODE_1, "Place order (concurrently revised)",
                "Customer places an order", null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "Customer selects items", List.of())), List.of(), List.of(), List.of());
        replaceViaCompareAndUpdate(PROJECT_A, concurrentlyRevised);

        UseCase staleAttempt = new UseCase(ID_1, CODE_1, "Place order (stale attempt)",
                "Customer places an order", null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "Customer selects items", List.of())), List.of(), List.of(), List.of());

        assertThrows(UseCaseConcurrentlyModifiedException.class,
                () -> repository.compareAndUpdate(PROJECT_A, staleHead, staleAttempt, null, null, null, null, null, null,
                        java.util.Map.of(), java.util.Map.of(), null, Integer.MAX_VALUE));
        assertEquals(Optional.of(concurrentlyRevised), repository.findByCode(PROJECT_A, CODE_1, null));
    }

    @Test
    void compareAndUpdateThrowsWhenTheIdentityDoesNotExistAtAll() {
        seedReferences(PROJECT_A);

        assertThrows(UseCaseNotFoundException.class,
                () -> repository.compareAndUpdate(PROJECT_A, null, placeOrder(), null, null, null, null, null, null,
                        java.util.Map.of(), java.util.Map.of(), null, Integer.MAX_VALUE));
        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
        assertEquals(Optional.empty(), repository.findCurrentByCode(PROJECT_A, CODE_1));
    }

    @Test
    void findByCodeReturnsEmptyForUnknownCode() {
        assertEquals(Optional.empty(), repository.findByCode(PROJECT_A, new UseCaseCode("UC99"), null));
    }

    @Test
    void projectsAreIsolated() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        assertTrue(repository.findAll(PROJECT_B, null).isEmpty());
    }

    /**
     * Regression test: a use case whose primary
     * actor's {@code skos:prefLabel} is deleted after creation must still be readable in full -
     * the reference no longer depends on the label at all, unlike the old read path, which
     * mandatorily joined into the terms graph on {@code skos:prefLabel} and silently dropped the
     * whole use case ({@code findByCode} returned empty, {@code findAll} dropped it via
     * {@code Optional::ifPresent}) the moment that join failed to bind.
     */
    @Test
    void useCaseSurvivesItsPrimaryActorsPrefLabelBeingDeleted() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        deletePrefLabel(PROJECT_A, CUSTOMER.value());

        Optional<UseCase> byCode = repository.findByCode(PROJECT_A, CODE_1, null);
        assertTrue(byCode.isPresent(), "findByCode must still return the use case");
        assertEquals(CUSTOMER, byCode.orElseThrow().primaryActor());

        List<UseCase> all = repository.findAll(PROJECT_A, null);
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
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        renamePrefLabel(PROJECT_A, CUSTOMER.value(), "Customer", "Kunde");

        UseCase found = repository.findByCode(PROJECT_A, CODE_1, null).orElseThrow();
        assertEquals(CUSTOMER, found.primaryActor());
    }

    private void deletePrefLabel(ProjectId project, ResourceId subject) {
        String delete = "DELETE WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "<" + subject.value() + "> <http://www.w3.org/2004/02/skos/core#prefLabel> ?label } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(delete);
                return null;
            });
        }
    }

    private void renamePrefLabel(ProjectId project, ResourceId subject, String oldLabel, String newLabel) {
        String update = "DELETE { GRAPH <" + TERMS_GRAPH + "> { <" + subject.value()
                + "> <http://www.w3.org/2004/02/skos/core#prefLabel> \"" + oldLabel + "\" } } "
                + "INSERT { GRAPH <" + TERMS_GRAPH + "> { <" + subject.value()
                + "> <http://www.w3.org/2004/02/skos/core#prefLabel> \"" + newLabel + "\" } } WHERE {}";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(update);
                return null;
            });
        }
    }

    /**
     * Regression test for the {@code primaryActor} blank-node bug:
     * {@code arkreq:primaryActor} carries no {@code sh:nodeKind} constraint, so a store-first
     * (ADR-005) use case may legally target a blank node with it. Reading such a use case back
     * must not throw a {@link ClassCastException} out of {@code readBySubject} - and, because
     * {@code primaryActor} is a required (non-{@code OPTIONAL}) triple pattern in the scalar
     * read, the malformed use case is treated as "not found" rather than crashing the rest of
     * {@link UseCaseRepository#findAll}'s result list.
     */
    @Test
    void findAllSkipsUseCaseWithBlankNodePrimaryActorInsteadOfFailingTheWholeList() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        UseCaseCode orphanCode = new UseCaseCode("UC-ORPHAN");
        seed(PROJECT_A, USE_CASES_GRAPH,
                "<https://w3id.org/arknet/id/uc-orphan> "
                        + "a <https://w3id.org/arknet/requirements#UseCase> ; "
                        + "<http://purl.org/dc/terms/title> \"Orphan use case\" ; "
                        + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Some goal\" ; "
                        + "<https://w3id.org/arknet/requirements#primaryActor> _:orphanActor ; "
                        + "<http://purl.org/dc/terms/identifier> \"" + orphanCode.value() + "\" .");

        List<UseCase> all = repository.findAll(PROJECT_A, null);
        assertEquals(1, all.size());
        assertEquals(CODE_1, all.get(0).code());

        assertEquals(Optional.empty(), repository.findByCode(PROJECT_A, orphanCode, null));
    }

    /**
     * Regression test: {@code arkreq:mainStep} is only
     * {@code sh:Warning} severity at {@code sh:minCount 1} in the SHACL shapes, so
     * {@link ShaclWriteGate#enforce} lets a store-first (ADR-005) use case with zero main-step
     * triples through. Reading such a use case back must not let {@link UseCase}'s "at least one
     * step" invariant throw out of {@code readBySubject} - mirroring the blank-node
     * {@code primaryActor} guard, the malformed use case is treated as "not found" rather than
     * crashing the rest of {@link UseCaseRepository#findAll}'s result list.
     */
    @Test
    void findAllSkipsUseCaseWithNoStepsInsteadOfFailingTheWholeList() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        UseCaseCode noStepsCode = new UseCaseCode("UC-NO-STEPS");
        seed(PROJECT_A, USE_CASES_GRAPH,
                "<https://w3id.org/arknet/id/uc-no-steps> "
                        + "a <https://w3id.org/arknet/requirements#UseCase> ; "
                        + "<http://purl.org/dc/terms/title> \"No steps use case\" ; "
                        + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Some goal\" ; "
                        + "<https://w3id.org/arknet/requirements#primaryActor> "
                        + "<" + CUSTOMER.value().value() + "> ; "
                        + "<http://purl.org/dc/terms/identifier> \"" + noStepsCode.value() + "\" .");

        List<UseCase> all = repository.findAll(PROJECT_A, null);
        assertEquals(1, all.size());
        assertEquals(CODE_1, all.get(0).code());

        assertEquals(Optional.empty(), repository.findByCode(PROJECT_A, noStepsCode, null));
    }

    /**
     * Regression test: nothing in SHACL prevents two distinct
     * {@code arkreq:Step} nodes under the same use case's {@code arkreq:mainStep} from sharing
     * the same {@code arkreq:position} - uniqueness is only enforced in-process by
     * {@code UseCase.requireConsecutiveStepPositions}, and store-first data (ADR-005) never runs
     * through that. Correlating a step's {@code arkreq:stepRealises} edges by the derived
     * position integer instead of the step's own IRI would silently merge the two steps'
     * requirement references under one key, then throw a duplicate-position
     * {@link IllegalArgumentException} out of {@link UseCase}'s constructor - crashing the rest
     * of {@link UseCaseRepository#findAll}'s result list, the same class of bug already
     * fixed for {@code supportingActor}/{@code stepRealises} elsewhere in this adapter.
     */
    @Test
    void findAllSkipsUseCaseWithDuplicateStepPositionsInsteadOfFailingTheWholeList() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);

        UseCaseCode duplicatePositionCode = new UseCaseCode("UC-DUP-POSITION");
        seed(PROJECT_A, USE_CASES_GRAPH,
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
        seed(PROJECT_A, USE_CASES_GRAPH,
                "<https://w3id.org/arknet/id/uc-dup-position-step-1> "
                        + "a <https://w3id.org/arknet/requirements#Step> ; "
                        + "<https://w3id.org/arknet/requirements#position> \"1\"^^<"
                        + XSD.INTEGER + "> ; "
                        + "<https://w3id.org/arknet/requirements#stepText> \"Customer selects items\" ; "
                        + "<https://w3id.org/arknet/requirements#stepRealises> <" + FR_1.value() + "> .");
        seed(PROJECT_A, USE_CASES_GRAPH,
                "<https://w3id.org/arknet/id/uc-dup-position-step-2> "
                        + "a <https://w3id.org/arknet/requirements#Step> ; "
                        + "<https://w3id.org/arknet/requirements#position> \"1\"^^<"
                        + XSD.INTEGER + "> ; "
                        + "<https://w3id.org/arknet/requirements#stepText> \"Customer confirms and pays\" .");

        List<UseCase> all = repository.findAll(PROJECT_A, null);
        assertEquals(1, all.size());
        assertEquals(CODE_1, all.get(0).code());

        assertEquals(Optional.empty(), repository.findByCode(PROJECT_A, duplicatePositionCode, null));
    }

    @Test
    void createRejectsStepViolatingShaclShapes() {
        seedRequirement(PROJECT_A, "FR-1");
        seedHumanActor(PROJECT_A, "customer", "Customer");

        // stepText "ok" is non-blank (valid domain) but below the shape's minLength of 3.
        UseCase invalid = new UseCase(ID_1, CODE_1, "Bad", "Some goal", null, null,
                CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "ok", List.of(FR_1_REF))), List.of(), List.of(), List.of());

        assertThrows(WriteConstraintViolationException.class,
                () -> repository.create(PROJECT_A, invalid, null));
        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
    }

    /**
     * Regression test: {@code rshapes:UseCase-primaryActor} carries
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
     * {@code rshapes:UseCase-title} carries {@code sh:uniqueLang true} (formerly
     * {@code sh:maxCount 1}): two language-tagged titles sharing the exact same non-empty tag are
     * rejected, but {@link UseCase#title()} stays single-valued at the domain level - a second
     * title is unreachable through {@link UseCaseRepository#create}, same rationale as
     * {@link #gateRejectsUseCaseWithTwoPrimaryActors}. Two plain, <em>untagged</em> titles are
     * deliberately <strong>not</strong> covered here: {@code sh:uniqueLang} per the SHACL spec only
     * ever compares literals that carry a non-empty language tag (mirroring the sibling
     * requirements adapter's identical note).
     */
    @Test
    void gateRejectsUseCaseWithTwoTitlesSharingTheSameLanguageTag() {
        ValueFactory vf = SimpleValueFactory.getInstance();
        IRI useCase = vf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI actor = vf.createIRI("https://w3id.org/arknet/model/term/actor-1");
        IRI useCaseClass = vf.createIRI("https://w3id.org/arknet/requirements#UseCase");
        IRI actorClass = vf.createIRI("https://w3id.org/arknet/process#Actor");
        IRI primaryActor = vf.createIRI("https://w3id.org/arknet/requirements#primaryActor");

        Model twoTitles = new LinkedHashModel();
        twoTitles.add(useCase, RDF.TYPE, useCaseClass);
        twoTitles.add(useCase, DCTERMS.IDENTIFIER, vf.createLiteral("UC-1"));
        twoTitles.add(useCase, DCTERMS.TITLE, vf.createLiteral("Place order", "en"));
        twoTitles.add(useCase, DCTERMS.TITLE, vf.createLiteral("Submit order", "en"));
        twoTitles.add(useCase, primaryActor, actor);
        twoTitles.add(actor, RDF.TYPE, actorClass);

        ShaclWriteGate gate = KognioRdfUseCaseRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(new RDF4JGraph(twoTitles)));
        assertTrue(ex.getMessage().contains("title"), ex.getMessage());
    }

    /**
     * Durchstich test (issue #75): {@code rshapes:UseCase-title} carries an {@code sh:message} with
     * both an {@code @en} and a {@code @de} literal since the bilingual translation pass. This
     * proves the fallback chain in {@link DisplayLocale#select} actually discriminates between the
     * two against the REAL {@code ShaclValidationRdf4j} engine (not the recording fake used in
     * {@code ShaclWriteGateTest}) - a requested English locale must surface the English sentence,
     * a requested German locale the German one, from the very same shape violation.
     */
    @Test
    void gateSelectsMessageLanguageAccordingToDisplayLocale() {
        ValueFactory vf = SimpleValueFactory.getInstance();
        IRI useCase = vf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI actor = vf.createIRI("https://w3id.org/arknet/model/term/actor-1");
        IRI useCaseClass = vf.createIRI("https://w3id.org/arknet/requirements#UseCase");
        IRI actorClass = vf.createIRI("https://w3id.org/arknet/process#Actor");
        IRI primaryActor = vf.createIRI("https://w3id.org/arknet/requirements#primaryActor");

        Model twoTitles = new LinkedHashModel();
        twoTitles.add(useCase, RDF.TYPE, useCaseClass);
        twoTitles.add(useCase, DCTERMS.IDENTIFIER, vf.createLiteral("UC-1"));
        twoTitles.add(useCase, DCTERMS.TITLE, vf.createLiteral("Place order", "en"));
        twoTitles.add(useCase, DCTERMS.TITLE, vf.createLiteral("Submit order", "en"));
        twoTitles.add(useCase, primaryActor, actor);
        twoTitles.add(actor, RDF.TYPE, actorClass);

        ShaclWriteGate englishGate = KognioRdfUseCaseRepositoryFactory
                .buildGate(new DisplayLocale(Locale.ENGLISH, Locale.ENGLISH));
        WriteConstraintViolationException englishEx = assertThrows(WriteConstraintViolationException.class,
                () -> englishGate.enforce(new RDF4JGraph(twoTitles)));
        assertTrue(englishEx.getMessage().contains("A Use Case needs at least one dcterms:title"),
                englishEx.getMessage());

        ShaclWriteGate germanGate = KognioRdfUseCaseRepositoryFactory
                .buildGate(new DisplayLocale(Locale.GERMAN, Locale.ENGLISH));
        WriteConstraintViolationException germanEx = assertThrows(WriteConstraintViolationException.class,
                () -> germanGate.enforce(new RDF4JGraph(twoTitles)));
        assertTrue(germanEx.getMessage().contains("Use Case braucht mindestens eine dcterms:title"),
                germanEx.getMessage());
    }

    /**
     * {@code rshapes:UseCase-goal} carries {@code sh:uniqueLang true}: two language-tagged goals
     * sharing the exact same non-empty tag are rejected, mirroring
     * {@link #gateRejectsUseCaseWithTwoTitlesSharingTheSameLanguageTag}. {@link UseCase#goal()} is
     * single-valued, so a second value is unreachable through {@link UseCaseRepository#create}.
     */
    @Test
    void gateRejectsUseCaseWithTwoGoalsSharingTheSameLanguageTag() {
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
        twoGoals.add(useCase, useCaseGoal, vf.createLiteral("Customer wants the order placed quickly", "en"));
        twoGoals.add(useCase, useCaseGoal, vf.createLiteral("Customer wants a confirmation email", "en"));
        twoGoals.add(useCase, primaryActor, actor);
        twoGoals.add(actor, RDF.TYPE, actorClass);

        ShaclWriteGate gate = KognioRdfUseCaseRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(new RDF4JGraph(twoGoals)));
        assertTrue(ex.getMessage().contains("useCaseGoal"), ex.getMessage());
    }

    /**
     * {@code rshapes:Step-text} carries {@code sh:uniqueLang true}: two language-tagged step texts
     * sharing the exact same non-empty tag are rejected. {@link Step#text()} is single-valued, so
     * a second {@code stepText} is unreachable through {@link UseCaseRepository#create} -
     * exercised directly against a synthetic {@code arkreq:Step} candidate graph, since a step has
     * no standalone read/write entry point of its own (it is only ever reached through its owning
     * {@link UseCase}).
     */
    @Test
    void gateRejectsStepWithTwoTextsSharingTheSameLanguageTag() {
        ValueFactory vf = SimpleValueFactory.getInstance();
        IRI step = vf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI stepClass = vf.createIRI("https://w3id.org/arknet/requirements#Step");
        IRI position = vf.createIRI("https://w3id.org/arknet/requirements#position");
        IRI stepText = vf.createIRI("https://w3id.org/arknet/requirements#stepText");

        Model twoTexts = new LinkedHashModel();
        twoTexts.add(step, RDF.TYPE, stepClass);
        twoTexts.add(step, position, vf.createLiteral("1", XSD.INTEGER));
        twoTexts.add(step, stepText, vf.createLiteral("Customer places the order", "en"));
        twoTexts.add(step, stepText, vf.createLiteral("Customer submits the order", "en"));

        ShaclWriteGate gate = KognioRdfUseCaseRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(new RDF4JGraph(twoTexts)));
        assertTrue(ex.getMessage().contains("stepText"), ex.getMessage());
    }

    // ---- revision trail (ADR-014): one revision per write, head queryable ----------------

    /**
     * ADR-014 revision basis for this bounded context's funnel write paths: {@code create} and
     * {@code compareAndUpdate} each record exactly one immutable revision of the use case, and
     * the head is queryable per resource. The step resources the body writes alongside get no
     * revisions of their own - the revision hangs off the funnel's subject, the use case. Since
     * {@code uc_update} was wired through {@code compareAndUpdate}, this is no longer a
     * path exercised only directly on the out-port - every {@code UseCaseService#update} call
     * moves the head too, mirroring {@code
     * KognioRdfRequirementRepositoryTest#createAndCompareAndUpdateEachRecordExactlyOneRevisionWithAQueryableHead}.
     */
    @Test
    void createAndCompareAndUpdateEachRecordExactlyOneRevisionWithAQueryableHead() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);
        String subject = ID_1.value().value();

        assertEquals(1, revisionsOf(subject).size(), "create must record exactly one revision");
        RevisionToken headAfterCreate = repository.findCurrentByCode(PROJECT_A, CODE_1).orElseThrow().head();

        UseCase revised = new UseCase(ID_1, CODE_1, "Place order (revised)", "Customer places an order",
                null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, "Customer selects items", List.of())), List.of(), List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, headAfterCreate, revised, null, null, null, null, null, null,
                java.util.Map.of(), java.util.Map.of(), null, Integer.MAX_VALUE);

        List<String> revisions = revisionsOf(subject);
        assertEquals(2, revisions.size(), "compareAndUpdate must record exactly one more revision");
        List<String> heads = selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH
                + "> { <" + subject + "> <" + ArkprovVocabulary.HEAD + "> ?v } }");
        assertEquals(1, heads.size(), "the head is rewritten, never duplicated");
        assertTrue(revisions.contains(heads.get(0)), "the head must be one of the resource's revisions");
        assertEquals(heads.get(0), repository.findCurrentByCode(PROJECT_A, CODE_1).orElseThrow().head().value(),
                "findCurrentByCode must observe the advanced head");
    }

    private List<String> revisionsOf(String subjectIri) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?v a <" + ArkprovVocabulary.REVISION_TYPE + "> ; "
                + "<" + ArkprovVocabulary.SPECIALIZATION_OF + "> <" + subjectIri + "> } }");
    }

    private List<String> selectIris(String query) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((io.kogn.rdf.terms.IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .toList();
        }
    }

    // ---- usesTerm / constrainedBy (issue #329) -------------------------------------------

    private static UseCase placeOrderWithTermAndConstraint() {
        UseCase base = placeOrder();
        return new UseCase(base.id(), base.code(), base.title(), base.goal(), base.scope(), base.trigger(),
                base.primaryActor(), base.supportingActors(), base.precondition(), base.postcondition(),
                base.steps(), base.extensions(), List.of(TERM_1_REF), List.of(TCON_1_REF));
    }

    @Test
    void createsAndFindsUseCaseWithUsesTermAndConstrainedByEdges() {
        seedReferences(PROJECT_A);
        seedTerm(PROJECT_A, "term-1", "Cart");
        seedConstraint(PROJECT_A, "tcon-1");

        repository.create(PROJECT_A, placeOrderWithTermAndConstraint(), null);
        UseCase found = repository.findByCode(PROJECT_A, CODE_1, null).orElseThrow();

        assertEquals(List.of(TERM_1_REF), found.usesTerms());
        assertEquals(List.of(TCON_1_REF), found.constrainedBy());
    }

    /**
     * {@code compareAndUpdate} rebuilds the whole subject by identity - {@code usesTerms}/
     * {@code constrainedBy} must survive an update that does not itself touch them, the same way
     * {@code KognioRdfRequirementRepositoryTest} pins for {@code usesTerm}/{@code constrainedBy}.
     */
    @Test
    void usesTermAndConstrainedByEdgesSurviveAnUnrelatedUpdate() {
        seedReferences(PROJECT_A);
        seedTerm(PROJECT_A, "term-1", "Cart");
        seedConstraint(PROJECT_A, "tcon-1");
        repository.create(PROJECT_A, placeOrderWithTermAndConstraint(), null);
        UseCase current = repository.findByCode(PROJECT_A, CODE_1, null).orElseThrow();

        UseCase revised = new UseCase(current.id(), current.code(), "Place order (revised)", current.goal(),
                current.scope(), current.trigger(), current.primaryActor(), current.supportingActors(),
                current.precondition(), current.postcondition(), current.steps(), current.extensions(),
                current.usesTerms(), current.constrainedBy());
        replaceViaCompareAndUpdate(PROJECT_A, revised);

        UseCase found = repository.findByCode(PROJECT_A, CODE_1, null).orElseThrow();
        assertEquals("Place order (revised)", found.title());
        assertEquals(List.of(TERM_1_REF), found.usesTerms());
        assertEquals(List.of(TCON_1_REF), found.constrainedBy());
    }

    /**
     * A blank-node {@code usesTerm}/{@code constrainedBy} target is store-first-only (ADR-005;
     * neither property carries {@code sh:nodeKind sh:IRI} on {@code usesTerm}, and {@code
     * constrainedBy}'s {@code sh:nodeKind} only guards this adapter's own writes) - {@link
     * UseCase#usesTerms()}/{@link UseCase#constrainedBy()} can never carry it, so an update that
     * never reads it back must still not silently drop it from the store, mirroring
     * {@code KognioRdfRequirementRepositoryTest}'s identical regression test.
     */
    @Test
    void updatePreservesABlankNodeUsesTermAndConstrainedByTargetItCannotReadBack() {
        seedReferences(PROJECT_A);
        repository.create(PROJECT_A, placeOrder(), null);
        seed(PROJECT_A, USE_CASES_GRAPH,
                "<" + ID_1.value().value() + "> "
                        + "<https://w3id.org/arknet/requirements#usesTerm> _:blankTerm ; "
                        + "<http://open-services.net/ns/rm#constrainedBy> _:blankConstraint .");
        UseCase current = repository.findByCode(PROJECT_A, CODE_1, null).orElseThrow();
        assertTrue(current.usesTerms().isEmpty(), "a blank-node target is never read back into the domain type");
        assertTrue(current.constrainedBy().isEmpty());

        UseCase revised = new UseCase(current.id(), current.code(), "Place order (revised)", current.goal(),
                current.scope(), current.trigger(), current.primaryActor(), current.supportingActors(),
                current.precondition(), current.postcondition(), current.steps(), current.extensions(),
                current.usesTerms(), current.constrainedBy());
        replaceViaCompareAndUpdate(PROJECT_A, revised);

        assertEquals(1, countBlankUsesTermEdges(PROJECT_A), "the blank-node usesTerm edge must survive the update");
        assertEquals(1, countBlankConstrainedByEdges(PROJECT_A),
                "the blank-node constrainedBy edge must survive the update");
    }

    private long countBlankUsesTermEdges(ProjectId project) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.value()))) {
            return handle.sparqlQuery().select("SELECT ?term WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                    + "<" + ID_1.value().value() + "> <https://w3id.org/arknet/requirements#usesTerm> ?term . "
                    + "FILTER(!isIRI(?term)) } }").count();
        }
    }

    private long countBlankConstrainedByEdges(ProjectId project) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.value()))) {
            return handle.sparqlQuery().select("SELECT ?constraint WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                    + "<" + ID_1.value().value() + "> <http://open-services.net/ns/rm#constrainedBy> ?constraint . "
                    + "FILTER(!isIRI(?constraint)) } }").count();
        }
    }

    /**
     * A referenced constraint carrying no triples at all in {@code CONSTRAINTS_GRAPH} (a dangling
     * identity) is accepted here, unlike the sibling requirements bounded context's
     * {@code KognioRdfRequirementRepository}, which re-verifies a {@code constrainedBy} target by
     * copying its real triples into the SHACL gate's asserted context - {@code rshapes:ConstraintShape}
     * is active in that adapter's (unfiltered) gate and would otherwise fail a bare type assertion.
     * This adapter's gate ({@link KognioRdfUseCaseRepositoryFactory#buildGate}) loads the same
     * shapes file filtered down to only {@code arkreq:UseCase}/{@code arkreq:Step} node shapes -
     * {@code ConstraintShape} loses its target in that filtering and never fires here regardless of
     * what the asserted context carries, so a bare {@code rdf:type arkreq:Constraint} assertion is
     * (and remains) sufficient; unreachable via the MCP tools all the same, since
     * {@code uc_link_constraint} always resolves an existing constraint first via
     * {@code ConstraintLookup}.
     */
    @Test
    void createAcceptsAConstrainedByEdgeToADanglingConstraintIdentity() {
        seedReferences(PROJECT_A);
        ResourceId danglingConstraint = ResourceId.of("https://w3id.org/arknet/model/constraint/does-not-exist");
        UseCase base = placeOrder();
        UseCase withDanglingConstraint = new UseCase(base.id(), base.code(), base.title(), base.goal(),
                base.scope(), base.trigger(), base.primaryActor(), base.supportingActors(), base.precondition(),
                base.postcondition(), base.steps(), base.extensions(), List.of(),
                List.of(new ConstraintRef(danglingConstraint)));

        repository.create(PROJECT_A, withDanglingConstraint, null);

        UseCase found = repository.findByCode(PROJECT_A, CODE_1, null).orElseThrow();
        assertEquals(List.of(new ConstraintRef(danglingConstraint)), found.constrainedBy());
    }
}
