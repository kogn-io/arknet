package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;

import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Integration test for {@link KognioRdfRequirementRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 */
class KognioRdfRequirementRepositoryTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("a");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("b");

    private DatasetLifecycleRdf4j lifecycle;
    private RequirementRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-req-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = KognioRdfRequirementRepositoryFactory.over(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void savesAndFindsFunctionalRequirementById() {
        Requirement requirement = new Requirement(
                new RequirementId("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null);

        repository.save(WORKSPACE_A, requirement);
        Optional<Requirement> found = repository.findById(WORKSPACE_A, new RequirementId("FR-1"));

        assertEquals(Optional.of(requirement), found);
        assertEquals("The system shall authenticate a user.", found.orElseThrow().description());
    }

    @Test
    void findAllContainsAllSavedRequirements() {
        Requirement first = new Requirement(
                new RequirementId("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null);

        repository.save(WORKSPACE_A, first);
        assertEquals(1, repository.findAll(WORKSPACE_A).size());

        Requirement second = new Requirement(
                new RequirementId("FR-2"), "Logout", "The system shall end a user session.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null);
        repository.save(WORKSPACE_A, second);

        List<Requirement> all = repository.findAll(WORKSPACE_A);
        assertEquals(2, all.size());
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void saveReplacesByIdentityInsteadOfDuplicating() {
        RequirementId id = new RequirementId("FR-1");
        Requirement proposed = new Requirement(id, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null);
        Requirement accepted = new Requirement(id, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED, null, null, null);

        repository.save(WORKSPACE_A, proposed);
        repository.save(WORKSPACE_A, accepted);

        assertEquals(Optional.of(accepted), repository.findById(WORKSPACE_A, id));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(accepted, repository.findAll(WORKSPACE_A).get(0));
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertEquals(Optional.empty(), repository.findById(WORKSPACE_A, new RequirementId("FR-99")));
    }

    @Test
    void workspacesAreIsolated() {
        Requirement requirement = new Requirement(
                new RequirementId("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null);

        repository.save(WORKSPACE_A, requirement);

        assertTrue(repository.findAll(WORKSPACE_B).isEmpty());
    }

    @Test
    void savesAndFindsNonFunctionalRequirement() {
        Requirement requirement = new Requirement(
                new RequirementId("NFR-1"), "Response time < 200ms",
                "95% of requests shall complete in under 200ms.",
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null);

        repository.save(WORKSPACE_A, requirement);
        Optional<Requirement> found = repository.findById(WORKSPACE_A, new RequirementId("NFR-1"));

        assertEquals(Optional.of(requirement), found);
        assertEquals(RequirementType.NON_FUNCTIONAL, found.get().type());
    }

    @Test
    void savesAndFindsPriorityMotivatedByAndQualityCategory() {
        Requirement requirement = new Requirement(
                new RequirementId("NFR-1"), "Response time < 200ms",
                "95% of requests shall complete in under 200ms.",
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED,
                Priority.MUST_HAVE, "https://w3id.org/arknet/model/goal/fast-ux", "performance");

        repository.save(WORKSPACE_A, requirement);
        Optional<Requirement> found = repository.findById(WORKSPACE_A, new RequirementId("NFR-1"));

        assertEquals(Optional.of(requirement), found);
        assertEquals(Priority.MUST_HAVE, found.orElseThrow().priority());
        assertEquals("https://w3id.org/arknet/model/goal/fast-ux", found.orElseThrow().motivatedBy());
        assertEquals("performance", found.orElseThrow().qualityCategory());
        assertTrue(repository.findAll(WORKSPACE_A).contains(requirement));
    }

    @Test
    void savedWithoutOptionalFieldsAreFoundWithNullOptionalFields() {
        Requirement requirement = new Requirement(
                new RequirementId("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null);

        repository.save(WORKSPACE_A, requirement);
        Optional<Requirement> found = repository.findById(WORKSPACE_A, new RequirementId("FR-1"));
        Requirement foundViaFindAll = repository.findAll(WORKSPACE_A).get(0);

        assertEquals(Optional.of(requirement), found);
        assertEquals(requirement, foundViaFindAll);
        assertNull(found.orElseThrow().priority());
        assertNull(found.orElseThrow().motivatedBy());
        assertNull(found.orElseThrow().qualityCategory());
    }

    /**
     * Regression test for the RDFS gotcha: {@code RequirementShape} targets the abstract
     * {@code arkreq:Requirement}, while the adapter types instances as the concrete
     * {@code arkreq:FunctionalRequirement}. The write-gate must reason the subclass axioms
     * from {@code arknet-requirements.ttl} into the validated data graph, otherwise the
     * shape silently never fires and this test would pass with an invalid requirement saved.
     */
    @Test
    void saveRejectsRequirementViolatingShaclShapes() {
        Requirement tooShortDescription = new Requirement(
                new RequirementId("FR-1"), "Login", "Hi", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null);

        assertThrows(WriteConstraintViolationException.class,
                () -> repository.save(WORKSPACE_A, tooShortDescription));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }
}
