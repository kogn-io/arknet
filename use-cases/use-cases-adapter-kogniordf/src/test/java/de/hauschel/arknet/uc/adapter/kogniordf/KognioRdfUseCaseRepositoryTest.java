package de.hauschel.arknet.uc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * Integration test for {@link KognioRdfUseCaseRepository} against an in-memory RDF4J-backed
 * kognio-rdf store. Requirement and actor resources referenced by a use case are seeded
 * directly into the same workspace graphs (as the requirements / ubiquitous-language adapters
 * would write them), so the strict lookup-by-label resolution can be exercised without a
 * cross-bounded-context test dependency.
 */
class KognioRdfUseCaseRepositoryTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("a");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("b");

    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";

    private DatasetLifecycleRdf4j lifecycle;
    private UseCaseRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-uc-it");
        lifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        repository = KognioRdfUseCaseRepositoryFactory.over(lifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private void seed(WorkspaceId workspace, String graph, String triples) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspace.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <" + graph + "> { " + triples + " } }");
                return null;
            });
        }
    }

    private void seedRequirement(WorkspaceId workspace, String label) {
        seed(workspace, REQUIREMENTS_GRAPH,
                "<https://w3id.org/arknet/model/requirement/" + label + "> "
                        + "a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                        + "<http://purl.org/dc/terms/identifier> \"" + label + "\" .");
    }

    private void seedHumanActor(WorkspaceId workspace, String slug, String prefLabel) {
        seed(workspace, TERMS_GRAPH,
                "<https://w3id.org/arknet/model/term/" + slug + "> "
                        + "a <http://www.w3.org/2004/02/skos/core#Concept> , "
                        + "<https://w3id.org/arknet/process#HumanActor> ; "
                        + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"" + prefLabel + "\" .");
    }

    private void seedSystemActor(WorkspaceId workspace, String slug, String prefLabel) {
        seed(workspace, TERMS_GRAPH,
                "<https://w3id.org/arknet/model/term/" + slug + "> "
                        + "a <http://www.w3.org/2004/02/skos/core#Concept> , "
                        + "<https://w3id.org/arknet/process#SystemActor> ; "
                        + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"" + prefLabel + "\" .");
    }

    private static UseCase placeOrder() {
        return new UseCase(
                new UseCaseId("UC1"), "Place order", "Customer places an order",
                "Webshop", "Customer opens the cart", new ActorRef("Customer"),
                List.of(new ActorRef("PaymentProvider")), "Customer is logged in", "Order is recorded",
                List.of(
                        new Step(1, "Customer selects items", List.of(new RequirementRef("FR-1"))),
                        new Step(2, "Customer confirms and pays", List.of())),
                List.of("2a. Payment declined -> use case ends in failure"));
    }

    @Test
    void savesAndFindsUseCaseByIdWithStepsAndResolvedReferences() {
        seedRequirement(WORKSPACE_A, "FR-1");
        seedHumanActor(WORKSPACE_A, "customer", "Customer");
        seedSystemActor(WORKSPACE_A, "payment-provider", "PaymentProvider");

        repository.save(WORKSPACE_A, placeOrder());
        Optional<UseCase> found = repository.findById(WORKSPACE_A, new UseCaseId("UC1"));

        assertTrue(found.isPresent());
        UseCase uc = found.orElseThrow();
        assertEquals("Place order", uc.title());
        assertEquals("Customer places an order", uc.goal());
        assertEquals("Webshop", uc.scope());
        assertEquals("Customer opens the cart", uc.trigger());
        assertEquals(new ActorRef("Customer"), uc.primaryActor());
        assertEquals(List.of(new ActorRef("PaymentProvider")), uc.supportingActors());
        assertEquals("Customer is logged in", uc.precondition());
        assertEquals("Order is recorded", uc.postcondition());
        assertEquals(2, uc.steps().size());
        assertEquals(1, uc.steps().get(0).position());
        assertEquals("Customer selects items", uc.steps().get(0).text());
        assertEquals(List.of(new RequirementRef("FR-1")), uc.steps().get(0).realises());
        assertEquals(2, uc.steps().get(1).position());
        assertEquals(List.of(), uc.steps().get(1).realises());
        assertEquals(List.of("2a. Payment declined -> use case ends in failure"), uc.extensions());
    }

    @Test
    void findAllContainsAllSavedUseCases() {
        seedRequirement(WORKSPACE_A, "FR-1");
        seedHumanActor(WORKSPACE_A, "customer", "Customer");
        seedSystemActor(WORKSPACE_A, "payment-provider", "PaymentProvider");
        repository.save(WORKSPACE_A, placeOrder());

        UseCase second = new UseCase(new UseCaseId("UC2"), "Reset password", "User resets password",
                null, null, new ActorRef("Customer"), List.of(), null, null,
                List.of(new Step(1, "User requests a reset link", List.of())), List.of());
        repository.save(WORKSPACE_A, second);

        List<UseCase> all = repository.findAll(WORKSPACE_A);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(uc -> uc.id().equals(new UseCaseId("UC1"))));
        assertTrue(all.stream().anyMatch(uc -> uc.id().equals(new UseCaseId("UC2"))));
    }

    @Test
    void saveReplacesByIdentityInsteadOfDuplicating() {
        seedRequirement(WORKSPACE_A, "FR-1");
        seedHumanActor(WORKSPACE_A, "customer", "Customer");
        seedSystemActor(WORKSPACE_A, "payment-provider", "PaymentProvider");
        repository.save(WORKSPACE_A, placeOrder());

        UseCase revised = new UseCase(new UseCaseId("UC1"), "Place order (revised)", "Customer places an order",
                null, null, new ActorRef("Customer"), List.of(), null, null,
                List.of(new Step(1, "Customer selects items", List.of())), List.of());
        repository.save(WORKSPACE_A, revised);

        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        UseCase found = repository.findById(WORKSPACE_A, new UseCaseId("UC1")).orElseThrow();
        assertEquals("Place order (revised)", found.title());
        assertEquals(1, found.steps().size());
        assertTrue(found.supportingActors().isEmpty());
        assertTrue(found.extensions().isEmpty());
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertEquals(Optional.empty(), repository.findById(WORKSPACE_A, new UseCaseId("UC99")));
    }

    @Test
    void workspacesAreIsolated() {
        seedRequirement(WORKSPACE_A, "FR-1");
        seedHumanActor(WORKSPACE_A, "customer", "Customer");
        seedSystemActor(WORKSPACE_A, "payment-provider", "PaymentProvider");
        repository.save(WORKSPACE_A, placeOrder());

        assertTrue(repository.findAll(WORKSPACE_B).isEmpty());
    }

    @Test
    void saveRejectsUnknownRequirementLabelWithDidacticMessage() {
        seedHumanActor(WORKSPACE_A, "customer", "Customer");
        seedSystemActor(WORKSPACE_A, "payment-provider", "PaymentProvider");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> repository.save(WORKSPACE_A, placeOrder()));

        assertTrue(ex.getMessage().contains("FR-1"));
        assertTrue(ex.getMessage().contains("req_add"));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void saveRejectsUnknownActorLabelWithDidacticMessage() {
        seedRequirement(WORKSPACE_A, "FR-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> repository.save(WORKSPACE_A, placeOrder()));

        assertTrue(ex.getMessage().contains("Customer"));
        assertTrue(ex.getMessage().contains("term_add"));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void saveRejectsAmbiguousRequirementLabel() {
        seedRequirement(WORKSPACE_A, "FR-1");
        seed(WORKSPACE_A, REQUIREMENTS_GRAPH,
                "<https://w3id.org/arknet/model/requirement/duplicate-fr-1> "
                        + "a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                        + "<http://purl.org/dc/terms/identifier> \"FR-1\" .");
        seedHumanActor(WORKSPACE_A, "customer", "Customer");
        seedSystemActor(WORKSPACE_A, "payment-provider", "PaymentProvider");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> repository.save(WORKSPACE_A, placeOrder()));

        assertTrue(ex.getMessage().contains("ambiguous"));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void saveRejectsStepViolatingShaclShapes() {
        seedRequirement(WORKSPACE_A, "FR-1");
        seedHumanActor(WORKSPACE_A, "customer", "Customer");

        // stepText "ok" is non-blank (valid domain) but below the shape's minLength of 3.
        UseCase invalid = new UseCase(new UseCaseId("UC1"), "Bad", "Some goal", null, null,
                new ActorRef("Customer"), List.of(), null, null,
                List.of(new Step(1, "ok", List.of(new RequirementRef("FR-1")))), List.of());

        assertThrows(WriteConstraintViolationException.class,
                () -> repository.save(WORKSPACE_A, invalid));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }
}
