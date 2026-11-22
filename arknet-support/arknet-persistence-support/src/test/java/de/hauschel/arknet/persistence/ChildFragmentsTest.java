// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

class ChildFragmentsTest {

    private static final DatasetId DATASET = new DatasetId("child-fragments");
    private static final String PARENT = "https://example.org/req/1";

    private final RDF rdf = new SimpleRdf();
    private final IRI parent = rdf.createIRI(PARENT);
    private final IRI edge = rdf.createIRI("https://example.org/criterion");
    private final IRI position = rdf.createIRI("https://example.org/position");

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY),
                storageRoot);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void aNewParentNumbersItsChildrenFromOne() {
        assertEquals(Map.of(1, child("AC-1"), 2, child("AC-2")), ChildFragments.forNewParent(parent, "AC", 2));
    }

    /** Removing the first of three children leaves the other two their names at moved positions. */
    @Test
    void aSurvivingChildKeepsItsNameAtItsNewPosition() {
        Graph draft = draft("AC-1", 1, "AC-2", 2, "AC-3", 3);

        Map<Integer, IRI> named = tx(tx -> {
            ChildFragments fragments = ChildFragments.of(tx, parent, draft);
            Map<Integer, IRI> kept = fragments.carried(edge, position,
                    stored -> stored == 1 ? OptionalInt.empty() : OptionalInt.of(stored - 1));
            return fragments.name("AC", 2, kept);
        });

        assertEquals(Map.of(1, child("AC-2"), 2, child("AC-3")), named);
    }

    /** The number of a removed child is never handed out again, even if it was the highest. */
    @Test
    void aNewChildNeverGetsTheNumberOfARemovedOne() {
        tx(tx -> {
            ChildFragments.recordIssued(tx, parent, draft("AC-1", 1, "AC-2", 2));
            return null;
        });
        Graph draft = draft("AC-1", 1);

        Map<Integer, IRI> named = tx(tx -> {
            ChildFragments fragments = ChildFragments.of(tx, parent, draft);
            return fragments.name("AC", 2, fragments.carried(edge, position, OptionalInt::of));
        });

        assertEquals(Map.of(1, child("AC-1"), 2, child("AC-3")), named);
    }

    /** Each kind counts on its own. */
    @Test
    void theNumbersOfOneKindDoNotAdvanceAnother() {
        Graph draft = draft("Step-1", 1, "Step-2", 2);

        Map<Integer, IRI> named = tx(tx -> ChildFragments.of(tx, parent, draft).name("Extension", 1, Map.of()));

        assertEquals(Map.of(1, child("Extension-1")), named);
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

    private <T> T tx(Function<DatasetTx, T> work) {
        try (DatasetHandle handle = lifecycle.acquire(DATASET)) {
            return handle.transactor().inTransaction(work::apply);
        }
    }
}
