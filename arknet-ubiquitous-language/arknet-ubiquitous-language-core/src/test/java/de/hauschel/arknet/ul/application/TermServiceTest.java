package de.hauschel.arknet.ul.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Policy tests for {@link TermService}: identity assignment, listing and lookup,
 * exercised against an in-memory fake repository.
 */
class TermServiceTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;

    private InMemoryTermRepository repository;
    private TermService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTermRepository();
        service = new TermService(repository);
    }

    @Test
    void addAssignsFirstIdentity() {
        Term added = service.add(WS, new NewTerm("Gutschrift",
                "Rueckerstattung eines bereits gezahlten Betrags.", null));

        assertEquals(new TermId("TERM-1"), added.id());
        assertEquals("Gutschrift", added.prefLabel());
        assertEquals("Rueckerstattung eines bereits gezahlten Betrags.", added.definition());
        assertEquals(added, repository.findById(WS, added.id()).orElseThrow());
    }

    @Test
    void addNumbersRunSequentially() {
        TermId first = service.add(WS, new NewTerm("Gutschrift", "def a", null)).id();
        TermId second = service.add(WS, new NewTerm("Bestellung", "def b", null)).id();

        assertEquals(new TermId("TERM-1"), first);
        assertEquals(new TermId("TERM-2"), second);
    }

    @Test
    void addIsScopedPerWorkspace() {
        WorkspaceId other = new WorkspaceId("other");
        service.add(WS, new NewTerm("Gutschrift", "def a", null));

        Term inOther = service.add(other, new NewTerm("Bestellung", "def b", null));

        assertEquals(new TermId("TERM-1"), inOther.id());
        assertTrue(service.list(other).stream().allMatch(t -> t.prefLabel().equals("Bestellung")));
        assertEquals(1, service.list(WS).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewTerm("Gutschrift", "def a", null));
        service.add(WS, new NewTerm("Bestellung", "def b", null));

        List<Term> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals("Gutschrift", all.get(0).prefLabel());
        assertEquals("Bestellung", all.get(1).prefLabel());
    }

    @Test
    void getReturnsPersistedTerm() {
        TermId id = service.add(WS, new NewTerm("Gutschrift", "def a", null)).id();

        assertTrue(service.get(WS, id).isPresent());
        assertEquals("Gutschrift", service.get(WS, id).orElseThrow().prefLabel());
    }

    @Test
    void getIsEmptyForUnknownId() {
        assertFalse(service.get(WS, new TermId("TERM-99")).isPresent());
    }

    @Test
    void addPassesThroughActorFacet() {
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");

        Term added = service.add(WS, new NewTerm("Kunde", "Person, die eine Bestellung aufgibt.", facet));

        assertEquals(facet, added.actorFacet());
        assertEquals(facet, repository.findById(WS, added.id()).orElseThrow().actorFacet());
    }

    @Test
    void addWithoutActorFacetLeavesItNull() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null));

        assertNull(added.actorFacet());
    }
}
