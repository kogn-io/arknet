package de.hauschel.arknet.ul.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * Integration test for {@link KognioRdfTermRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 */
class KognioRdfTermRepositoryTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("a");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("b");
    private static final String SKOS_CONCEPT = "http://www.w3.org/2004/02/skos/core#Concept";

    private DatasetLifecycleRdf4j lifecycle;
    private TermRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-ul-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = KognioRdfTermRepositoryFactory.over(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    /** Fresh, valid opaque identity - every test picks its own so ids never collide. */
    private static TermId freshId() {
        return new TermId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    @Test
    void createsAndFindsTermByCode() {
        Term term = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift",
                "Rueckerstattung eines bereits gezahlten Betrags.", null);

        repository.create(WORKSPACE_A, term);
        Optional<Term> found = repository.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertEquals(Optional.of(term), found);
        assertEquals("Gutschrift", found.orElseThrow().prefLabel());
        assertEquals("Rueckerstattung eines bereits gezahlten Betrags.", found.orElseThrow().definition());
    }

    @Test
    void findAllContainsAllCreatedTerms() {
        Term first = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, first);
        assertEquals(1, repository.findAll(WORKSPACE_A).size());

        Term second = new Term(freshId(), new TermCode("TERM-2"), "Bestellung", "def b", null);
        repository.create(WORKSPACE_A, second);

        List<Term> all = repository.findAll(WORKSPACE_A);
        assertEquals(2, all.size());
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void createRejectsAnAlreadyExistingIdentityAndPersistsNothingElse() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, term);

        Term collidingId = new Term(id, new TermCode("TERM-2"), "Bestellung", "def b", null);

        assertThrows(ResourceAlreadyExistsException.class,
                () -> repository.create(WORKSPACE_A, collidingId));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(Optional.of(term), repository.findByCode(WORKSPACE_A, new TermCode("TERM-1")));
    }

    /**
     * Identity collision and code collision are distinct failure modes: two different, freshly
     * minted identities both claiming {@code TERM-1} must be rejected by code, not by identity -
     * the sibling requirements BC relies on {@code dcterms:identifier} being unique (#36).
     */
    @Test
    void createRejectsADuplicateCodeUnderADifferentIdentityAndPersistsNothingElse() {
        TermCode code = new TermCode("TERM-1");
        Term first = new Term(freshId(), code, "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, first);

        Term collidingCode = new Term(freshId(), code, "Bestellung", "def b", null);

        assertThrows(DuplicateTermCodeException.class,
                () -> repository.create(WORKSPACE_A, collidingCode));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(Optional.of(first), repository.findByCode(WORKSPACE_A, code));
    }

    @Test
    void updateRejectsAMissingIdentity() {
        Term neverCreated = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);

        assertThrows(TermNotFoundException.class,
                () -> repository.update(WORKSPACE_A, neverCreated));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void updateReplacesByIdentityInsteadOfDuplicating() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        Term original = new Term(id, code, "Gutschrift", "Erste Definition.", null);
        Term revised = new Term(id, code, "Gutschrift", "Ueberarbeitete Definition.", null);

        repository.create(WORKSPACE_A, original);
        repository.update(WORKSPACE_A, revised);

        assertEquals(Optional.of(revised), repository.findByCode(WORKSPACE_A, code));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(revised, repository.findAll(WORKSPACE_A).get(0));
    }

    /** The opaque identity is preserved across an update - only the term's state changes. */
    @Test
    void updatePreservesTheOpaqueIdentity() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(WORKSPACE_A, new Term(id, code, "Gutschrift", "Erste Definition.", null));

        repository.update(WORKSPACE_A, new Term(id, code, "Gutschrift", "Ueberarbeitete Definition.", null));

        assertEquals(id, repository.findByCode(WORKSPACE_A, code).orElseThrow().id());
    }

    @Test
    void findByCodeReturnsEmptyForUnknownCode() {
        assertEquals(Optional.empty(), repository.findByCode(WORKSPACE_A, new TermCode("TERM-99")));
    }

    @Test
    void workspacesAreIsolated() {
        Term term = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);

        repository.create(WORKSPACE_A, term);

        assertTrue(repository.findAll(WORKSPACE_B).isEmpty());
    }

    /**
     * Gate-level regression test: {@code TermShape} targets {@code skos:Concept} directly (no
     * RDFS reasoning needed, unlike the requirements shapes), but the {@link Term} domain record
     * forbids a blank {@code prefLabel}, so no violation is reachable through
     * {@link TermRepository#create}. This test bypasses the domain and drives the gate with a
     * hand-built {@code skos:Concept} that has no {@code skos:prefLabel}, proving the shapes
     * actually load and {@code targetClass skos:Concept} fires (no silent pass).
     */
    @Test
    void gateRejectsConceptWithoutPrefLabel() {
        ShaclWriteGate gate = KognioRdfTermRepositoryFactory.buildGate();
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph invalidConcept = rdf.createGraph();
        invalidConcept.add(subject, VocabRdf.TYPE, rdf.createIRI(SKOS_CONCEPT));

        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(invalidConcept));
    }

    @Test
    void createsAndFindsTermWithHumanActorFacet() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Kunde", "Person, die eine Bestellung aufgibt.",
                new ActorFacet(ActorKind.HUMAN, "Besteller"));

        repository.create(WORKSPACE_A, term);
        Optional<Term> found = repository.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertEquals(Optional.of(term), found);
        ActorFacet facet = found.orElseThrow().actorFacet();
        assertEquals(ActorKind.HUMAN, facet.kind());
        assertEquals("Besteller", facet.role());
        assertTrue(subjectHasType(WORKSPACE_A, id, "https://w3id.org/arknet/process#HumanActor"));
    }

    @Test
    void createsAndFindsTermWithSystemActorFacet() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Zahlungsdienst", "Verarbeitet Zahlungen.",
                new ActorFacet(ActorKind.SYSTEM, "PaymentService"));

        repository.create(WORKSPACE_A, term);
        Optional<Term> found = repository.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertEquals(Optional.of(term), found);
        ActorFacet facet = found.orElseThrow().actorFacet();
        assertEquals(ActorKind.SYSTEM, facet.kind());
        assertEquals("PaymentService", facet.role());
        assertTrue(subjectHasType(WORKSPACE_A, id, "https://w3id.org/arknet/process#SystemActor"));
    }

    @Test
    void createsAndFindsTermWithoutActorFacet() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Gutschrift", "def a", null);

        repository.create(WORKSPACE_A, term);
        Optional<Term> found = repository.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertNull(found.orElseThrow().actorFacet());
        assertFalse(subjectHasType(WORKSPACE_A, id, "https://w3id.org/arknet/process#HumanActor"));
        assertFalse(subjectHasType(WORKSPACE_A, id, "https://w3id.org/arknet/process#SystemActor"));
    }

    @Test
    void findAllReconstructsActorFacet() {
        Term withFacet = new Term(freshId(), new TermCode("TERM-1"), "Kunde", "def a",
                new ActorFacet(ActorKind.HUMAN, "Besteller"));
        repository.create(WORKSPACE_A, withFacet);

        List<Term> all = repository.findAll(WORKSPACE_A);

        assertEquals(1, all.size());
        assertEquals(new ActorFacet(ActorKind.HUMAN, "Besteller"), all.get(0).actorFacet());
    }

    // ---- findByIds: batch resolution for ResolveTerms (issue #77 nachtrag) --------------

    @Test
    void findByIdsResolvesKnownIdentitiesInOneQuery() {
        Term first = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        Term second = new Term(freshId(), new TermCode("TERM-2"), "Bestellung", "def b", null);
        repository.create(WORKSPACE_A, first);
        repository.create(WORKSPACE_A, second);

        List<Term> resolved = repository.findByIds(WORKSPACE_A, List.of(first.id().value(), second.id().value()));

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(first));
        assertTrue(resolved.contains(second));
    }

    /** An id absent from the workspace is simply absent from the result, never an error. */
    @Test
    void findByIdsSilentlyOmitsUnknownIdentities() {
        Term known = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, known);
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/does-not-exist");

        List<Term> resolved = repository.findByIds(WORKSPACE_A, List.of(known.id().value(), unknown));

        assertEquals(List.of(known), resolved);
    }

    @Test
    void findByIdsWithEmptyIdsReturnsAnEmptyListWithoutQuerying() {
        assertEquals(List.of(), repository.findByIds(WORKSPACE_A, List.of()));
    }

    @Test
    void findByIdsIsScopedPerWorkspace() {
        Term inWorkspaceA = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, inWorkspaceA);

        assertEquals(List.of(), repository.findByIds(WORKSPACE_B, List.of(inWorkspaceA.id().value())));
    }

    @Test
    void findByIdsReconstructsActorFacet() {
        Term withFacet = new Term(freshId(), new TermCode("TERM-1"), "Kunde", "def a",
                new ActorFacet(ActorKind.HUMAN, "Besteller"));
        repository.create(WORKSPACE_A, withFacet);

        List<Term> resolved = repository.findByIds(WORKSPACE_A, List.of(withFacet.id().value()));

        assertEquals(1, resolved.size());
        assertEquals(new ActorFacet(ActorKind.HUMAN, "Besteller"), resolved.get(0).actorFacet());
    }

    private boolean subjectHasType(WorkspaceId workspaceId, TermId id, String typeIri) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + id.value().value() + "> a <" + typeIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }
}
