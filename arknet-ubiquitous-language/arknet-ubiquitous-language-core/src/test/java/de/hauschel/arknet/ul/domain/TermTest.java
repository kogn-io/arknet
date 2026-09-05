// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Domain invariant tests for {@link Term}.
 *
 * <p>Pure, framework-free unit tests - they guard the value object's contract.</p>
 */
class TermTest {

    private static final TermId ID = new TermId(ResourceId.of("https://w3id.org/arknet/id/abc-123"));
    private static final TermCode CODE = new TermCode("TERM-1");

    @Test
    void holdsItsValues() {
        Term term = new Term(ID, CODE, "Gutschrift", "Rueckerstattung eines Betrags.");

        assertEquals(ID, term.id());
        assertEquals(CODE, term.code());
        assertEquals("Gutschrift", term.prefLabel());
        assertEquals("Rueckerstattung eines Betrags.", term.definition());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () -> new Term(null, CODE, "Gutschrift", "def"));
    }

    @Test
    void rejectsNullCode() {
        assertThrows(NullPointerException.class, () -> new Term(ID, null, "Gutschrift", "def"));
    }

    @Test
    void rejectsNullPrefLabel() {
        assertThrows(NullPointerException.class, () -> new Term(ID, CODE, null, "def"));
    }

    @Test
    void rejectsBlankPrefLabel() {
        assertThrows(IllegalArgumentException.class, () -> new Term(ID, CODE, " ", "def"));
    }

    @Test
    void rejectsNullDefinition() {
        assertThrows(NullPointerException.class, () -> new Term(ID, CODE, "Gutschrift", null));
    }

    @Test
    void rejectsBlankDefinition() {
        assertThrows(IllegalArgumentException.class, () -> new Term(ID, CODE, "Gutschrift", " "));
    }

    @Test
    void holdsNoBroaderByDefault() {
        Term term = new Term(ID, CODE, "Gutschrift", "Rueckerstattung eines Betrags.");

        assertNull(term.broader());
    }

    @Test
    void holdsBroaderWhenPresent() {
        TermCode broader = new TermCode("TERM-2");
        Term term = new Term(ID, CODE, "Gutschrift", "Rueckerstattung eines Betrags.", broader);

        assertEquals(broader, term.broader());
    }

    /** Domain-level guard: even without seeing the graph, a term cannot claim itself as broader. */
    @Test
    void rejectsItsOwnCodeAsBroader() {
        assertThrows(IllegalArgumentException.class,
                () -> new Term(ID, CODE, "Gutschrift", "def", CODE));
    }

    @Test
    void holdsNoRelatedPeersByDefault() {
        Term term = new Term(ID, CODE, "Gutschrift", "Rueckerstattung eines Betrags.");

        assertEquals(List.of(), term.related());
    }

    @Test
    void holdsRelatedPeersWhenPresent() {
        List<TermCode> related = List.of(new TermCode("TERM-2"), new TermCode("TERM-3"));

        Term term = new Term(ID, CODE, "Gutschrift", "def", null, related);

        assertEquals(related, term.related());
    }

    /** A {@code null} related list is the same thing as no related peers at all, never an NPE. */
    @Test
    void normalizesANullRelatedListToEmpty() {
        Term term = new Term(ID, CODE, "Gutschrift", "def", null, null);

        assertEquals(List.of(), term.related());
    }

    /** The symmetric counterpart of {@link #rejectsItsOwnCodeAsBroader}. */
    @Test
    void rejectsItsOwnCodeAsARelatedPeer() {
        assertThrows(IllegalArgumentException.class,
                () -> new Term(ID, CODE, "Gutschrift", "def", null, List.of(CODE)));
    }

    /**
     * Naming a peer twice is a caller error, not something to silently collapse: RDF set semantics
     * would store one triple either way, so a list that says otherwise means the caller believes
     * something the store cannot represent.
     */
    @Test
    void rejectsTheSameRelatedPeerTwice() {
        List<TermCode> twice = List.of(new TermCode("TERM-2"), new TermCode("TERM-2"));

        assertThrows(IllegalArgumentException.class,
                () -> new Term(ID, CODE, "Gutschrift", "def", null, twice));
    }
}
