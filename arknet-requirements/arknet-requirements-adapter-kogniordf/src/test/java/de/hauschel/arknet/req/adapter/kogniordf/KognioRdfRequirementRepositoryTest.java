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
        Requirement requirement = requirementUsing(termRef("TERM-1"));

        repository.create(WORKSPACE_A, requirement);

        assertEquals(Optional.of(requirement),
                repository.findByCode(WORKSPACE_A, new RequirementCode("FR-1")));
        assertEquals(List.of(termRef("TERM-1")),
                repository.findAll(WORKSPACE_A).get(0).usesTerms());
    }

    @Test
    void createsAndFindsSeveralUsesTermEdges() {
        givenTerm(WORKSPACE_A, "TERM-1");
        givenTerm(WORKSPACE_A, "TERM-2");

        repository.create(WORKSPACE_A, requirementUsing(termRef("TERM-1"), termRef("TERM-2")));

        List<TermRef> found = repository.findByCode(WORKSPACE_A, new RequirementCode("FR-1"))
                .orElseThrow().usesTerms();
        assertEquals(2, found.size());
        assertTrue(found.containsAll(List.of(termRef("TERM-1"), termRef("TERM-2"))));
    }

    /**
     * Issue #77: term references arrive pre-resolved. This adapter no longer looks the term up
     * (that strict, identifier-based resolution now lives in {@code KognioRdfTermLookup}, called
     * once by the application service when a term is linked) - it trusts the identity it is
     * handed, the same way it trusts {@code motivatedBy} without re-resolving it. A target that
     * does not exist at all in the store is therefore persisted just the same as one that does;
     * see {@code KognioRdfTermLookupTest} for the strict-resolution behaviour this used to be
     * (and still is, just one layer up).
     */
    @Test
    void createPersistsAUsesTermEdgeEvenWhenItsTargetDoesNotExistInTheStore() {
        TermRef doesNotExist = termRef("TERM-99");

        repository.create(WORKSPACE_A, requirementUsing(doesNotExist));

        assertEquals(List.of(doesNotExist),
                repository.findByCode(WORKSPACE_A, new RequirementCode("FR-1")).orElseThrow().usesTerms());
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
        repository.create(WORKSPACE_A, requirementUsing(termRef("TERM-1")));

        Requirement reloaded = repository.findByCode(WORKSPACE_A, code).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms());
        repository.update(WORKSPACE_A, accepted);

        Requirement found = repository.findByCode(WORKSPACE_A, code).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, found.status());
        assertEquals(List.of(termRef("TERM-1")), found.usesTerms());
    }

    @Test
    void unlinkingATermRemovesTheEdge() {
        givenTerm(WORKSPACE_A, "TERM-1");
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementUsing(termRef("TERM-1"));
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

    // ---- usesTerm: reading is identity-based, not join-based, since #77 -----------------

    /**
     * Issue #77 fixes the actual defect behind #65: {@link #readUsesTerms} no longer joins into
     * the terms graph at all, so a target's missing {@code dcterms:identifier} can no longer
     * hide the edge. What used to require the {@code write()} preservation mechanism (below) is
     * now handled by the ordinary read-and-replace path, without any special-casing.
     */
    @Test
    void usesTermEdgeToATermWithoutIdentifierIsReadableAndSurvivesAnOrdinaryUpdate() {
        String termIri = givenTermWithoutIdentifier(WORKSPACE_A);
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementUsing();
        repository.create(WORKSPACE_A, created);
        givenUsesTermEdge(WORKSPACE_A, created.id(), termIri);
        TermRef expected = new TermRef(ResourceId.of(termIri));

        Requirement reloaded = repository.findByCode(WORKSPACE_A, code).orElseThrow();
        assertEquals(List.of(expected), reloaded.usesTerms(),
                "reading no longer joins into the terms graph, so a missing dcterms:identifier "
                        + "on the target no longer hides the edge");

        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms());
        repository.update(WORKSPACE_A, accepted);

        assertEquals(List.of(expected), repository.findByCode(WORKSPACE_A, code).orElseThrow().usesTerms(),
                "the edge is now part of the ordinary record, carried forward by the replacing "
                        + "update without needing the #65 preservation mechanism at all");
    }

    // ---- usesTerm: store-first edges to a non-IRI target the strict read cannot represent ----

    /**
     * Store-first regression test: {@code arkreq:usesTerm} is not range-constrained to
     * {@code IRI} at the RDF level (the SHACL {@code sh:class skos:Concept} constraint accepts
     * a blank node just as readily as an IRI), so a store-first edge can legally target a
     * blank node - {@code [ a skos:Concept ]} written directly into the requirements graph.
     * {@link de.hauschel.arknet.kernel.ResourceId} cannot represent a blank node, so it is
     * exactly the kind of edge the preservation query in {@code write()} must still capture
     * (issue #65, narrowed to this one case by #77). Casting the captured binding to {@link IRI}
     * would throw a {@link ClassCastException} on a blank node, turning the previously silent
     * data loss of issue #65 into a crash on every {@link #update} of the affected requirement -
     * a regression, not a fix.
     */
    @Test
    void storeFirstUsesTermEdgeToABlankNodeSurvivesAReplacingUpdateWithoutCrashing() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementUsing();
        repository.create(WORKSPACE_A, created);
        givenUsesTermEdgeToFreshBlankNodeConcept(WORKSPACE_A, created.id());

        Requirement reloaded = repository.findByCode(WORKSPACE_A, code).orElseThrow();
        assertEquals(List.of(), reloaded.usesTerms(), "blank-node edge must stay invisible to the read");

        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms());
        repository.update(WORKSPACE_A, accepted);

        assertTrue(usesTermEdgeTargetsAConceptBlankNode(WORKSPACE_A, reloaded.id()),
                "blank-node edge must survive the replacing update and still point at its typed node - "
                        + "not merely at some blank node");
    }

    /**
     * Preserving a non-IRI-target edge must not duplicate an ordinary IRI-target one. Both the
     * ordinary rewrite and the preservation query run against the same subject inside the same
     * write transaction; this pins that an IRI-target edge - which the preservation query's
     * {@code FILTER(!isIRI(?term))} must exclude - is still written exactly once.
     */
    @Test
    void ordinaryUsesTermEdgeIsNotDuplicatedByPreservation() {
        givenTerm(WORKSPACE_A, "TERM-1");
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(WORKSPACE_A, requirementUsing(termRef("TERM-1")));

        Requirement reloaded = repository.findByCode(WORKSPACE_A, code).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms());
        repository.update(WORKSPACE_A, accepted);

        String termIri = "https://w3id.org/arknet/model/term/TERM-1";
        assertEquals(1, countUsesTermEdges(WORKSPACE_A, reloaded.id(), termIri));
    }

    private static Requirement requirementUsing(TermRef... terms) {
        return new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(terms));
    }

    /** Builds the {@link TermRef} a term written by {@link #givenTerm} resolves to. */
    private static TermRef termRef(String termId) {
        return new TermRef(ResourceId.of("https://w3id.org/arknet/model/term/" + termId));
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

    /**
     * Writes a glossary term into the sibling terms graph <em>without</em> a
     * {@code dcterms:identifier} - unreachable via {@code term_add}/{@link #givenTerm}, but
     * reachable store-first (ADR-005). {@code KognioRdfTermLookup} cannot resolve such a concept
     * by code (no identifier to look up by), so a test wiring an edge to it must do so directly
     * per raw SPARQL as well. Returns the term's IRI for that purpose.
     */
    private String givenTermWithoutIdentifier(WorkspaceId workspaceId) {
        String termIri = "https://w3id.org/arknet/model/term/" + UUID.randomUUID();
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + termIri + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Anmeldung\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
        return termIri;
    }

    /**
     * Writes an {@code arkreq:usesTerm} edge straight into the requirements graph - the
     * store-first path (ADR-005), unmediated by {@code req_link_term}/{@code KognioRdfTermLookup},
     * so it can point at a term the strict lookup would reject by code.
     */
    private void givenUsesTermEdge(WorkspaceId workspaceId, RequirementId subjectId, String termIri) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + subjectId.value().value() + "> <https://w3id.org/arknet/requirements#usesTerm> <"
                + termIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Counts an {@code arkreq:usesTerm} edge between one subject and one term directly in the
     * requirements graph, bypassing {@code findByCode}/{@code findAll} entirely - the
     * assertion this supports must not rely on the very read path whose blind spot it is
     * proving safe.
     */
    private long countUsesTermEdges(WorkspaceId workspaceId, RequirementId subjectId, String termIri) {
        String select = "SELECT ?term WHERE { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + subjectId.value().value() + "> <https://w3id.org/arknet/requirements#usesTerm> <"
                + termIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return handle.sparqlQuery().select(select).count();
        }
    }

    /**
     * Writes an {@code arkreq:usesTerm} edge straight into the requirements graph, targeting a
     * freshly minted anonymous blank node typed as a {@code skos:Concept} - RDF-legal (the
     * property carries no {@code sh:nodeKind} constraint forcing an IRI object) and reachable
     * only store-first, never via {@code req_link_term}/{@code KognioRdfTermLookup}, which
     * resolve a code to an IRI and therefore cannot even address a blank node.
     */
    private void givenUsesTermEdgeToFreshBlankNodeConcept(WorkspaceId workspaceId, RequirementId subjectId) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + subjectId.value().value() + "> <https://w3id.org/arknet/requirements#usesTerm> "
                + "[ a <http://www.w3.org/2004/02/skos/core#Concept> ] } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Checks - via a single raw SPARQL {@code ASK} joining both patterns on the same variable -
     * that the subject's {@code arkreq:usesTerm} edge still targets a node that itself carries
     * {@code a skos:Concept} in the requirements graph. This is the identity check for #65's
     * blank-node case: {@code DELETE WHERE { <subject> ?p ?o }} only ever removes triples whose
     * subject is the requirement, never the target node's own type triple, so this passing is
     * proof the edge was re-attached to the very same blank node rather than to a dangling or
     * freshly-generated one.
     */
    private boolean usesTermEdgeTargetsAConceptBlankNode(WorkspaceId workspaceId, RequirementId subjectId) {
        String ask = "ASK { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + subjectId.value().value() + "> <https://w3id.org/arknet/requirements#usesTerm> ?term . "
                + "?term a <http://www.w3.org/2004/02/skos/core#Concept> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return handle.sparqlQuery().ask(ask);
        }
    }
}
