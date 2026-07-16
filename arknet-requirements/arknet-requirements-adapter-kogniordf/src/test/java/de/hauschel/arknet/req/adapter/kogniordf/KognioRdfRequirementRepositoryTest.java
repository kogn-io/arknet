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
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.DuplicateRequirementCodeException;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.req.domain.TermRef;

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

    /** Fresh, valid opaque identity - every test picks its own so ids never collide. */
    private static RequirementId freshId() {
        return new RequirementId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    @Test
    void createsAndFindsFunctionalRequirementByCode() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null);

        repository.create(WORKSPACE_A, requirement);
        Optional<Requirement> found = repository.findByCode(WORKSPACE_A, new RequirementCode("FR-1"));

        assertEquals(Optional.of(requirement), found);
        assertEquals("The system shall authenticate a user.", found.orElseThrow().description());
    }

    @Test
    void findAllContainsAllCreatedRequirements() {
        Requirement first = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null);

        repository.create(WORKSPACE_A, first);
        assertEquals(1, repository.findAll(WORKSPACE_A).size());

        Requirement second = new Requirement(
                freshId(), new RequirementCode("FR-2"), "Logout", "The system shall end a user session.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null);
        repository.create(WORKSPACE_A, second);

        List<Requirement> all = repository.findAll(WORKSPACE_A);
        assertEquals(2, all.size());
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void createRejectsAnAlreadyExistingIdentityAndPersistsNothingElse() {
        RequirementId id = freshId();
        Requirement requirement = new Requirement(id, new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null);
        repository.create(WORKSPACE_A, requirement);

        Requirement collidingId = new Requirement(id, new RequirementCode("FR-2"), "Logout",
                "The system shall end a user session.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null);

        assertThrows(ResourceAlreadyExistsException.class,
                () -> repository.create(WORKSPACE_A, collidingId));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(Optional.of(requirement), repository.findByCode(WORKSPACE_A, new RequirementCode("FR-1")));
    }

    /**
     * Identity collision and code collision are distinct failure modes: two different, freshly
     * minted identities both claiming {@code FR-1} must be rejected by code, not by identity.
     */
    @Test
    void createRejectsADuplicateCodeUnderADifferentIdentityAndPersistsNothingElse() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement first = new Requirement(freshId(), code, "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null);
        repository.create(WORKSPACE_A, first);

        Requirement collidingCode = new Requirement(freshId(), code, "Logout",
                "The system shall end a user session.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null);

        assertThrows(DuplicateRequirementCodeException.class,
                () -> repository.create(WORKSPACE_A, collidingCode));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(Optional.of(first), repository.findByCode(WORKSPACE_A, code));
    }

    @Test
    void updateRejectsAMissingIdentity() {
        Requirement neverCreated = new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null);

        assertThrows(RequirementNotFoundException.class,
                () -> repository.update(WORKSPACE_A, neverCreated));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void updateReplacesByIdentityInsteadOfDuplicating() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        Requirement proposed = new Requirement(id, code, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null);
        Requirement accepted = new Requirement(id, code, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED, null, null, null, null);

        repository.create(WORKSPACE_A, proposed);
        repository.update(WORKSPACE_A, accepted);

        assertEquals(Optional.of(accepted), repository.findByCode(WORKSPACE_A, code));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(accepted, repository.findAll(WORKSPACE_A).get(0));
    }

    /** The opaque identity is preserved across an update - only the requirement's state changes. */
    @Test
    void updatePreservesTheOpaqueIdentity() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(WORKSPACE_A, new Requirement(id, code, "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null));

        repository.update(WORKSPACE_A, new Requirement(id, code, "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.ACCEPTED, null, null, null, null));

        assertEquals(id, repository.findByCode(WORKSPACE_A, code).orElseThrow().id());
    }

    @Test
    void findByCodeReturnsEmptyForUnknownCode() {
        assertEquals(Optional.empty(), repository.findByCode(WORKSPACE_A, new RequirementCode("FR-99")));
    }

    @Test
    void workspacesAreIsolated() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null);

        repository.create(WORKSPACE_A, requirement);

        assertTrue(repository.findAll(WORKSPACE_B).isEmpty());
    }

    @Test
    void createsAndFindsNonFunctionalRequirement() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("NFR-1"), "Response time < 200ms",
                "95% of requests shall complete in under 200ms.",
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null);

        repository.create(WORKSPACE_A, requirement);
        Optional<Requirement> found = repository.findByCode(WORKSPACE_A, new RequirementCode("NFR-1"));

        assertEquals(Optional.of(requirement), found);
        assertEquals(RequirementType.NON_FUNCTIONAL, found.get().type());
    }

    @Test
    void createsAndFindsPriorityMotivatedByAndQualityCategory() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("NFR-1"), "Response time < 200ms",
                "95% of requests shall complete in under 200ms.",
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED,
                Priority.MUST_HAVE, "https://w3id.org/arknet/model/goal/fast-ux", "performance", null);

        repository.create(WORKSPACE_A, requirement);
        Optional<Requirement> found = repository.findByCode(WORKSPACE_A, new RequirementCode("NFR-1"));

        assertEquals(Optional.of(requirement), found);
        assertEquals(Priority.MUST_HAVE, found.orElseThrow().priority());
        assertEquals("https://w3id.org/arknet/model/goal/fast-ux", found.orElseThrow().motivatedBy());
        assertEquals("performance", found.orElseThrow().qualityCategory());
        assertTrue(repository.findAll(WORKSPACE_A).contains(requirement));
    }

    @Test
    void createdWithoutOptionalFieldsAreFoundWithNullOptionalFields() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null);

        repository.create(WORKSPACE_A, requirement);
        Optional<Requirement> found = repository.findByCode(WORKSPACE_A, new RequirementCode("FR-1"));
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
    void createRejectsRequirementViolatingShaclShapes() {
        Requirement tooShortDescription = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "Hi", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null);

        assertThrows(WriteConstraintViolationException.class,
                () -> repository.create(WORKSPACE_A, tooShortDescription));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    // ---- usesTerm: the cross-BC requirement -> glossary-term edge (#36) -----------------

    @Test
    void createsAndFindsUsesTermEdge() {
        givenTerm(WORKSPACE_A, "TERM-1");
        Requirement requirement = requirementUsing(new TermRef("TERM-1"));

        repository.create(WORKSPACE_A, requirement);

        assertEquals(Optional.of(requirement),
                repository.findByCode(WORKSPACE_A, new RequirementCode("FR-1")));
        assertEquals(List.of(new TermRef("TERM-1")),
                repository.findAll(WORKSPACE_A).get(0).usesTerms());
    }

    @Test
    void createsAndFindsSeveralUsesTermEdges() {
        givenTerm(WORKSPACE_A, "TERM-1");
        givenTerm(WORKSPACE_A, "TERM-2");

        repository.create(WORKSPACE_A, requirementUsing(new TermRef("TERM-1"), new TermRef("TERM-2")));

        List<TermRef> found = repository.findByCode(WORKSPACE_A, new RequirementCode("FR-1"))
                .orElseThrow().usesTerms();
        assertEquals(2, found.size());
        assertTrue(found.containsAll(List.of(new TermRef("TERM-1"), new TermRef("TERM-2"))));
    }

    /**
     * The edge resolves via the term's {@code dcterms:identifier}, never its
     * {@code skos:prefLabel} - so an unknown identity is rejected even though a concept with
     * that text as a label exists.
     */
    @Test
    void createRejectsAnUnknownTermAndPersistsNothing() {
        givenTerm(WORKSPACE_A, "TERM-1");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> repository.create(WORKSPACE_A, requirementUsing(new TermRef("TERM-99"))));

        assertTrue(ex.getMessage().contains("TERM-99"), ex.getMessage());
        assertTrue(ex.getMessage().contains("term_add"), ex.getMessage());
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    /** A term of another workspace must not satisfy this workspace's reference. */
    @Test
    void createRejectsATermFromAnotherWorkspace() {
        givenTerm(WORKSPACE_B, "TERM-1");

        assertThrows(UnresolvedReferenceException.class,
                () -> repository.create(WORKSPACE_A, requirementUsing(new TermRef("TERM-1"))));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    /**
     * Regression guard for the replace-by-identity write path: {@code update} wipes the
     * subject's triples before re-writing them, so a read-modify-write round trip must carry
     * the usesTerm edges along instead of silently dropping them.
     */
    @Test
    void usesTermEdgesSurviveAReplacingUpdate() {
        givenTerm(WORKSPACE_A, "TERM-1");
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(WORKSPACE_A, requirementUsing(new TermRef("TERM-1")));

        Requirement reloaded = repository.findByCode(WORKSPACE_A, code).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms());
        repository.update(WORKSPACE_A, accepted);

        Requirement found = repository.findByCode(WORKSPACE_A, code).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, found.status());
        assertEquals(List.of(new TermRef("TERM-1")), found.usesTerms());
    }

    @Test
    void unlinkingATermRemovesTheEdge() {
        givenTerm(WORKSPACE_A, "TERM-1");
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementUsing(new TermRef("TERM-1"));
        repository.create(WORKSPACE_A, created);

        repository.update(WORKSPACE_A, new Requirement(created.id(), created.code(), created.title(),
                created.description(), created.type(), created.status(), created.priority(),
                created.motivatedBy(), created.qualityCategory(), List.of()));

        assertEquals(List.of(), repository.findByCode(WORKSPACE_A, code).orElseThrow().usesTerms());
    }

    /**
     * Proves the {@code sh:class skos:Concept} constraint on {@code arkreq:usesTerm} actually
     * fires rather than silently passing: RDFS reasoning is on and the ontology declares
     * {@code arkreq:usesTerm rdfs:range skos:Concept}, which - had range inference applied -
     * would type every link target as a concept and make the shape vacuous.
     *
     * <p>It does not, so the adapter must feed the resolved terms' type triples into the
     * validation graph; otherwise every legitimate link would be rejected. This test pins
     * that contract at gate level, where {@link #createsAndFindsUsesTermEdge} pins the other
     * side of it.</p>
     */
    @Test
    void gateRejectsUsesTermPointingAtSomethingThatIsNotAConcept() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#FunctionalRequirement"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("FR-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Login"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("The system shall authenticate a user."));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#status"),
                rdf.createIRI("https://w3id.org/arknet/requirements#Proposed"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#usesTerm"),
                rdf.createIRI("https://example.org/not-a-concept"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate().enforce(candidate));

        assertTrue(ex.getMessage().contains("usesTerm"), ex.getMessage());
    }

    private static Requirement requirementUsing(TermRef... terms) {
        return new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(terms));
    }

    /**
     * Writes a glossary term straight into the sibling terms graph of the shared workspace
     * dataset - deliberately via raw SPARQL rather than the ubiquitous-language adapter, so
     * this test does not couple the two bounded contexts. The cross-BC wiring itself is
     * covered by {@code CrossBoundedContextStoreWiringTest} in arknet-mcp.
     */
    private void givenTerm(WorkspaceId workspaceId, String termId) {
        String termIri = "https://w3id.org/arknet/model/term/" + termId;
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + termIri + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + termId + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Anmeldung\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }
}
