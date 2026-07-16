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

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
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
 * would write them), so the strict lookup-by-label resolution can be exercised without a
 * cross-bounded-context test dependency.
 *
 * <p>Identity is opaque (issue #72): the use-case subject IRI is minted above the store and
 * carried on the {@link UseCase}; the human-readable {@link UseCaseCode} ({@code UC1}) is a
 * separate {@code dcterms:identifier} triple and is what a caller looks up by.</p>
 */
class KognioRdfUseCaseRepositoryTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("a");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("b");

    private static final String USE_CASES_GRAPH = "https://w3id.org/arknet/model/use-cases";
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";

    private static final UseCaseId ID_1 = new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/uc-1"));
    private static final UseCaseId ID_2 = new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/uc-2"));
    private static final UseCaseCode CODE_1 = new UseCaseCode("UC1");
    private static final UseCaseCode CODE_2 = new UseCaseCode("UC2");

    private DatasetLifecycleRdf4j lifecycle;
    private UseCaseRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-uc-it");
        lifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        repository = KognioRdfUseCaseRepositoryFactory.over(lifecycle, new UuidResourceIdFactory());
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

    /** Counts {@code arkreq:Step} resources in the use-cases graph - guards delete-by-edge. */
    private long countSteps(WorkspaceId workspace) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspace.value()))) {
            return handle.sparqlQuery().select("SELECT ?s WHERE { GRAPH <" + USE_CASES_GRAPH + "> { "
                    + "?s a <https://w3id.org/arknet/requirements#Step> } }").count();
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

    private void seedReferences(WorkspaceId workspace) {
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
                "Webshop", "Customer opens the cart", new ActorRef("Customer"),
                List.of(new ActorRef("PaymentProvider")), "Customer is logged in", "Order is recorded",
                List.of(
                        new Step(1, "Customer selects items", List.of(new RequirementRef("FR-1"))),
                        new Step(2, "Customer confirms and pays", List.of())),
                List.of("2a. Payment declined -> use case ends in failure"));
    }

    @Test
    void createsAndFindsUseCaseByCodeWithStepsAndResolvedReferences() {
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
        seedReferences(WORKSPACE_A);
        repository.create(WORKSPACE_A, placeOrder());

        UseCase second = new UseCase(ID_2, CODE_2, "Reset password", "User resets password",
                null, null, new ActorRef("Customer"), List.of(), null, null,
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
                null, null, new ActorRef("Customer"), List.of(), null, null,
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

    @Test
    void createRejectsUnknownRequirementLabelWithDidacticMessage() {
        seedHumanActor(WORKSPACE_A, "customer", "Customer");
        seedSystemActor(WORKSPACE_A, "payment-provider", "PaymentProvider");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> repository.create(WORKSPACE_A, placeOrder()));

        assertTrue(ex.getMessage().contains("FR-1"));
        assertTrue(ex.getMessage().contains("req_add"));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void createRejectsUnknownActorLabelWithDidacticMessage() {
        seedRequirement(WORKSPACE_A, "FR-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> repository.create(WORKSPACE_A, placeOrder()));

        assertTrue(ex.getMessage().contains("Customer"));
        assertTrue(ex.getMessage().contains("term_add"));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void createRejectsAmbiguousRequirementLabel() {
        seedRequirement(WORKSPACE_A, "FR-1");
        seed(WORKSPACE_A, REQUIREMENTS_GRAPH,
                "<https://w3id.org/arknet/model/requirement/duplicate-fr-1> "
                        + "a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                        + "<http://purl.org/dc/terms/identifier> \"FR-1\" .");
        seedHumanActor(WORKSPACE_A, "customer", "Customer");
        seedSystemActor(WORKSPACE_A, "payment-provider", "PaymentProvider");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> repository.create(WORKSPACE_A, placeOrder()));

        assertTrue(ex.getMessage().contains("ambiguous"));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void createRejectsStepViolatingShaclShapes() {
        seedRequirement(WORKSPACE_A, "FR-1");
        seedHumanActor(WORKSPACE_A, "customer", "Customer");

        // stepText "ok" is non-blank (valid domain) but below the shape's minLength of 3.
        UseCase invalid = new UseCase(ID_1, CODE_1, "Bad", "Some goal", null, null,
                new ActorRef("Customer"), List.of(), null, null,
                List.of(new Step(1, "ok", List.of(new RequirementRef("FR-1")))), List.of());

        assertThrows(WriteConstraintViolationException.class,
                () -> repository.create(WORKSPACE_A, invalid));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }
}
