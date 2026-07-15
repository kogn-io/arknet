package de.hauschel.arknet.ul.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link Term}.
 *
 * <p>Pure, framework-free unit tests - they guard the value object's contract.</p>
 */
class TermTest {

    private static final TermId ID = new TermId("TERM-1");

    @Test
    void holdsItsValues() {
        Term term = new Term(ID, "Gutschrift", "Rueckerstattung eines Betrags.", null);

        assertEquals(ID, term.id());
        assertEquals("Gutschrift", term.prefLabel());
        assertEquals("Rueckerstattung eines Betrags.", term.definition());
        assertNull(term.actorFacet());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () -> new Term(null, "Gutschrift", "def", null));
    }

    @Test
    void rejectsNullPrefLabel() {
        assertThrows(NullPointerException.class, () -> new Term(ID, null, "def", null));
    }

    @Test
    void rejectsBlankPrefLabel() {
        assertThrows(IllegalArgumentException.class, () -> new Term(ID, " ", "def", null));
    }

    @Test
    void rejectsNullDefinition() {
        assertThrows(NullPointerException.class, () -> new Term(ID, "Gutschrift", null, null));
    }

    @Test
    void rejectsBlankDefinition() {
        assertThrows(IllegalArgumentException.class, () -> new Term(ID, "Gutschrift", " ", null));
    }

    @Test
    void holdsActorFacetWhenPresent() {
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");
        Term term = new Term(ID, "Kunde", "Person, die eine Bestellung aufgibt.", facet);

        assertEquals(facet, term.actorFacet());
    }
}
