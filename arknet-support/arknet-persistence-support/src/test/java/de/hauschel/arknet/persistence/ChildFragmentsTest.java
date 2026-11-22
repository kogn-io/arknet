// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * {@link ChildFragments} names children {@code <parent>#<kind>-<opaque id>} (ADR-64): a kept child
 * keeps its name, a new one gets a fresh id, and a minted name never collides with a child the
 * parent already holds.
 */
class ChildFragmentsTest {

    private static final String PARENT = "https://example.org/req/1";
    private static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RDF rdf = new SimpleRdf();
    private final IRI parent = rdf.createIRI(PARENT);
    private final IRI edge = rdf.createIRI("https://example.org/criterion");
    private final IRI position = rdf.createIRI("https://example.org/position");

    @Test
    void aNewParentNamesEachChildWithTheNextIdOfTheSource() {
        Map<Integer, IRI> named = ChildFragments.of(parent, rdf.createGraph(), sequence("zz", "aa"))
                .name("AC", 2, Map.of());

        assertEquals(Map.of(1, child("AC-zz"), 2, child("AC-aa")), named);
    }

    /** Removing the first of three children leaves the other two their names at moved positions. */
    @Test
    void aSurvivingChildKeepsItsNameAtItsNewPosition() {
        Graph draft = draft("AC-c", 1, "AC-a", 2, "AC-b", 3);
        ChildFragments fragments = ChildFragments.of(parent, draft, sequence("new"));

        Map<Integer, IRI> kept = fragments.carried(edge, position,
                stored -> stored == 1 ? OptionalInt.empty() : OptionalInt.of(stored - 1));
        Map<Integer, IRI> named = fragments.name("AC", 3, kept);

        assertEquals(Map.of(1, child("AC-a"), 2, child("AC-b"), 3, child("AC-new")), named);
    }

    /** A minted name the draft already holds - or this write already handed out - is drawn again. */
    @Test
    void aMintedNameThatIsAlreadyTakenUnderTheParentIsDrawnAgain() {
        Graph draft = draft("AC-a", 1);
        ChildFragments fragments = ChildFragments.of(parent, draft, sequence("a", "b", "b", "c"));

        Map<Integer, IRI> named = fragments.name("AC", 3, fragments.carried(edge, position, OptionalInt::of));

        assertEquals(Map.of(1, child("AC-a"), 2, child("AC-b"), 3, child("AC-c")), named);
    }

    @Test
    void aSourceYieldingOnlyTakenIdsFailsInsteadOfReusingAName() {
        ChildFragments fragments = ChildFragments.of(parent, draft("AC-a", 1), () -> "a");

        assertThrows(IllegalStateException.class, () -> fragments.name("AC", 1, Map.of()));
    }

    @Test
    void anIdThatIsNoPlainFragmentIsRejected() {
        ChildFragments fragments = ChildFragments.of(parent, rdf.createGraph(), sequence("a#b"));

        assertThrows(IllegalStateException.class, () -> fragments.name("AC", 1, Map.of()));
    }

    /** The production source mints a fresh UUID per child, the opacity of the top-level IRIs. */
    @Test
    void theRandomSourceMintsDistinctUuids() {
        Map<Integer, IRI> named = ChildFragments.of(parent, rdf.createGraph(), ChildIdSource.random())
                .name("Step", 2, Map.of());

        named.values().forEach(iri -> assertTrue(iri.getIRIString().matches(PARENT + "#Step-" + UUID_SHAPE),
                iri.getIRIString()));
        assertNotEquals(named.get(1), named.get(2));
    }

    private ChildIdSource sequence(String... ids) {
        Iterator<String> next = List.of(ids).iterator();
        return next::next;
    }

    private Graph draft(Object... localNamesAndPositions) {
        Graph graph = rdf.createGraph();
        for (int i = 0; i < localNamesAndPositions.length; i += 2) {
            IRI child = child((String) localNamesAndPositions[i]);
            graph.add(parent, edge, child);
            graph.add(child, position, rdf.createLiteral(localNamesAndPositions[i + 1].toString(), VocabXsd.INTEGER));
        }
        return graph;
    }

    private IRI child(String localName) {
        return rdf.createIRI(PARENT + "#" + localName);
    }
}
