package de.hauschel.arknet.ul.adapter.kogniordf;

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

import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

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

    @Test
    void savesAndFindsTermById() {
        Term term = new Term(new TermId("TERM-1"), "Gutschrift",
                "Rueckerstattung eines bereits gezahlten Betrags.");

        repository.save(WORKSPACE_A, term);
        Optional<Term> found = repository.findById(WORKSPACE_A, new TermId("TERM-1"));

        assertEquals(Optional.of(term), found);
        assertEquals("Gutschrift", found.orElseThrow().prefLabel());
        assertEquals("Rueckerstattung eines bereits gezahlten Betrags.", found.orElseThrow().definition());
    }

    @Test
    void findAllContainsAllSavedTerms() {
        Term first = new Term(new TermId("TERM-1"), "Gutschrift", "def a");
        repository.save(WORKSPACE_A, first);
        assertEquals(1, repository.findAll(WORKSPACE_A).size());

        Term second = new Term(new TermId("TERM-2"), "Bestellung", "def b");
        repository.save(WORKSPACE_A, second);

        List<Term> all = repository.findAll(WORKSPACE_A);
        assertEquals(2, all.size());
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void saveReplacesByIdentityInsteadOfDuplicating() {
        TermId id = new TermId("TERM-1");
        Term original = new Term(id, "Gutschrift", "Erste Definition.");
        Term revised = new Term(id, "Gutschrift", "Ueberarbeitete Definition.");

        repository.save(WORKSPACE_A, original);
        repository.save(WORKSPACE_A, revised);

        assertEquals(Optional.of(revised), repository.findById(WORKSPACE_A, id));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(revised, repository.findAll(WORKSPACE_A).get(0));
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertEquals(Optional.empty(), repository.findById(WORKSPACE_A, new TermId("TERM-99")));
    }

    @Test
    void workspacesAreIsolated() {
        Term term = new Term(new TermId("TERM-1"), "Gutschrift", "def a");

        repository.save(WORKSPACE_A, term);

        assertTrue(repository.findAll(WORKSPACE_B).isEmpty());
    }

    /**
     * Gate-level regression test: {@code TermShape} targets {@code skos:Concept} directly (no
     * RDFS reasoning needed, unlike the requirements shapes), but the {@link Term} domain record
     * forbids a blank {@code prefLabel}, so no violation is reachable through
     * {@link TermRepository#save}. This test bypasses the domain and drives the gate with a
     * hand-built {@code skos:Concept} that has no {@code skos:prefLabel}, proving the shapes
     * actually load and {@code targetClass skos:Concept} fires (no silent pass).
     */
    @Test
    void gateRejectsConceptWithoutPrefLabel() {
        ShaclWriteGate gate = KognioRdfTermRepositoryFactory.buildGate();
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/model/term/TERM-INVALID");
        Graph invalidConcept = rdf.createGraph();
        invalidConcept.add(subject, VocabRdf.TYPE, rdf.createIRI(SKOS_CONCEPT));

        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(invalidConcept));
    }
}
