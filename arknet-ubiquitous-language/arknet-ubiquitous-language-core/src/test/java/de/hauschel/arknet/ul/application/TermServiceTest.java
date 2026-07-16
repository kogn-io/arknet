package de.hauschel.arknet.ul.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Policy tests for {@link TermService}: opaque identity minting, code assignment, listing and
 * lookup-by-code, exercised against an in-memory fake repository.
 */
class TermServiceTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;

    private InMemoryTermRepository repository;
    private TermService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTermRepository();
        service = new TermService(repository, new UuidResourceIdFactory());
    }

    @Test
    void addAssignsFirstCode() {
        Term added = service.add(WS, new NewTerm("Gutschrift",
                "Rueckerstattung eines bereits gezahlten Betrags.", null));

        assertEquals(new TermCode("TERM-1"), added.code());
        assertEquals("Gutschrift", added.prefLabel());
        assertEquals("Rueckerstattung eines bereits gezahlten Betrags.", added.definition());
        assertEquals(added, repository.findByCode(WS, added.code()).orElseThrow());
    }

    @Test
    void addMintsAFreshOpaqueIdentityPerTerm() {
        Term first = service.add(WS, new NewTerm("Gutschrift", "def a", null));
        Term second = service.add(WS, new NewTerm("Bestellung", "def b", null));

        // Identity is opaque and minted once - never derived from the (sequential) code.
        assertNotEquals(first.id(), second.id());
        assertTrue(first.id().value().value().startsWith("https://"));
    }

    @Test
    void addNumbersRunSequentially() {
        TermCode first = service.add(WS, new NewTerm("Gutschrift", "def a", null)).code();
        TermCode second = service.add(WS, new NewTerm("Bestellung", "def b", null)).code();

        assertEquals(new TermCode("TERM-1"), first);
        assertEquals(new TermCode("TERM-2"), second);
    }

    @Test
    void addIsScopedPerWorkspace() {
        WorkspaceId other = new WorkspaceId("other");
        service.add(WS, new NewTerm("Gutschrift", "def a", null));

        Term inOther = service.add(other, new NewTerm("Bestellung", "def b", null));

        assertEquals(new TermCode("TERM-1"), inOther.code());
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
        TermCode code = service.add(WS, new NewTerm("Gutschrift", "def a", null)).code();

        assertTrue(service.get(WS, code).isPresent());
        assertEquals("Gutschrift", service.get(WS, code).orElseThrow().prefLabel());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new TermCode("TERM-99")).isPresent());
    }

    @Test
    void addPassesThroughActorFacet() {
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");

        Term added = service.add(WS, new NewTerm("Kunde", "Person, die eine Bestellung aufgibt.", facet));

        assertEquals(facet, added.actorFacet());
        assertEquals(facet, repository.findByCode(WS, added.code()).orElseThrow().actorFacet());
    }

    @Test
    void addWithoutActorFacetLeavesItNull() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null));

        assertNull(added.actorFacet());
    }
}
