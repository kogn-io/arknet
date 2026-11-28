// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.document;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.iri;
import static de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.Document.Node;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The two term interleavings whose invariant is a shape over more than the written value: the
 * one word of a term under every language tag ({@code Term-prefLabel-oneWord}) and a broader chain
 * without a cycle ({@code Term-broader-noCycle}). In both the racer's document is valid against the
 * state it read; only the second run, against the state the concurrent writer committed, breaks the
 * rule - so the rule must be checked on that second run and not once before the race.
 */
class SubmitTermRealStoreConcurrencyTest {

    private static final String EN = "en";
    private static final String DE = "de";
    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";

    @TempDir
    Path storageRoot;

    private SubmitRace race;

    @BeforeEach
    void setUp() {
        race = new SubmitRace(storageRoot);
    }

    @AfterEach
    void tearDown() {
        race.close();
    }

    /**
     * Counterpart of {@code TermServiceRealStoreConcurrencyTest#concurrentRenameAndStaleTranslationWrite
     * _theStaleTranslationIsRejectedNotSilentlyWritten}: the racer translates the word it read while
     * the concurrent writer renames the term. The translation of the old word is rejected, not
     * written next to the new one.
     */
    @Test
    void aTranslationOfAWordRenamedMeanwhileIsRejectedOnTheSecondRun() {
        // given
        String term = race.created(document().create(Node.typed(ArkreqVocabulary.CONCEPT_TYPE)
                .with(SKOS_PREF_LABEL, text("Kunde", DE))
                .with(ArkreqVocabulary.DEFINITION, text("Erste Definition.", DE))));

        // when
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.SLICE, term,
                document().update(term, Node.fields()
                        .with(SKOS_PREF_LABEL, text("Kunde", EN))
                        .with(ArkreqVocabulary.DEFINITION, text("First definition.", EN))),
                document().update(term, Node.fields().with(SKOS_PREF_LABEL, text("Kunde (korrigiert)", DE))));

        // then
        ExecutionException failure = assertThrows(ExecutionException.class, outcome::racerResult);
        assertInstanceOf(SubmitRejectedException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("one word under every language tag"),
                failure.getCause().getMessage());
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
        assertEquals(Set.of("Kunde (korrigiert)@de"), race.values(term, SKOS_PREF_LABEL));
        assertEquals(Set.of("Erste Definition.@de"), race.values(term, ArkreqVocabulary.DEFINITION));
    }

    /**
     * Counterpart of {@code TermServiceRealStoreConcurrencyTest#concurrentUpdatesRacingToCloseATwoTermCycle
     * _racerAIsRejectedInsteadOfBothSucceeding}, the write-skew case: each racer writes only its own
     * term, and each document alone is free of a cycle. The racer read the other term before the
     * concurrent writer pointed it back; its second run sees the cycle and is rejected.
     */
    @Test
    void twoRacersClosingATwoTermCycleLeaveTheSecondRejected() {
        // given
        String order = race.created(term("Order"));
        String invoice = race.created(term("Invoice"));

        // when the racer read the invoice it points at, and the invoice is pointed at the order first
        SubmitRace.Outcome outcome = race.race(PausingBatch.Read.SLICE, invoice,
                document().update(order, Node.fields().with(ArkreqVocabulary.BROADER, iri(invoice))),
                document().update(invoice, Node.fields().with(ArkreqVocabulary.BROADER, iri(order))));

        // then
        ExecutionException failure = assertThrows(ExecutionException.class, outcome::racerResult);
        assertInstanceOf(SubmitRejectedException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("cycle"), failure.getCause().getMessage());
        assertEquals(2, outcome.runs(), "the lost commit runs the document once more");
        assertEquals(Set.of(), race.values(order, ArkreqVocabulary.BROADER));
        assertEquals(Set.of(order), race.values(invoice, ArkreqVocabulary.BROADER));
    }

    private static Document term(final String label) {
        return document().create(Node.typed(ArkreqVocabulary.CONCEPT_TYPE)
                .with(SKOS_PREF_LABEL, text(label, EN))
                .with(ArkreqVocabulary.DEFINITION, text("Means " + label + ".", EN)));
    }
}
