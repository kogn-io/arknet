package de.hauschel.arknet.ul.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Integration test for {@link KognioRdfTermRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 */
class KognioRdfTermRepositoryTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("a");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("b");

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfTermRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-ul-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = new KognioRdfTermRepository(datasetLifecycle);
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
}
