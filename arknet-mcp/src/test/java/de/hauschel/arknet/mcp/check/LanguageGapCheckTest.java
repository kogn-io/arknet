// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.mcp.check.LanguageGapCheck.Gap;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * Unit tests for the one structural rule behind {@code store_check LANGUAGE}
 * (kogn-io/arknet#412): a predicate that already carries a language-tagged literal is expected to
 * carry one for every language the project maintains.
 *
 * <p>Every fixture here is a bare triple list, never a live store - the rule has nothing to do
 * with RDF4J, with a bounded context or with a field name, and a fixture that pulled any of those
 * in would let the test pass for the wrong reason.</p>
 */
class LanguageGapCheckTest {

    private static final String ID = "https://w3id.org/arknet/id/";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final List<String> DE_EN = List.of("de", "en");

    @Test
    void reportsAPredicateThatCarriesOneMaintainedLanguageButNotTheOther() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "t1", RDF_TYPE, SKOS + "Concept"),
                literal(ID + "t1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "t1", SKOS + "definition", "Eine Definition", "de")));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN))
                .extracting(Gap::handle, Gap::typeLocalName, Gap::predicateIri, Gap::missingLanguages)
                .containsExactly(tuple("TERM-1", "Concept", SKOS + "definition", List.of("en")));
    }

    @Test
    void reportsNothingForAPredicateThatCarriesEveryMaintainedLanguage() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "t1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "t1", SKOS + "definition", "Eine Definition", "de"),
                literal(ID + "t1", SKOS + "definition", "A definition", "en")));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN)).isEmpty();
    }

    /**
     * The blind spot the tool description has to name: without a single tagged literal there is
     * nothing in the data that says this field was ever meant to be multilingual, and reporting it
     * would flag every untagged value in the store, {@code dcterms:identifier} included.
     */
    @Test
    void staysSilentAboutAFieldThatCarriesNoLanguageTaggedLiteralAtAll() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "t1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "t1", SKOS + "definition", "Eine Definition", null)));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN)).isEmpty();
    }

    @Test
    void ignoresIriObjectsBecauseAReferenceHasNoLanguage() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "r1", DCTERMS + "identifier", "FR-1", null),
                iri(ID + "r1", ARKREQ + "usesTerm", ID + "t1"),
                literal(ID + "t1", DCTERMS + "identifier", "TERM-1", null)));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN)).isEmpty();
    }

    @Test
    void matchesALanguageTagRegardlessOfItsCasingSoAStoreFirstLiteralStillCounts() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "t1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "t1", SKOS + "definition", "Eine Definition", "DE"),
                literal(ID + "t1", SKOS + "definition", "A definition", "EN")));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN)).isEmpty();
    }

    @Test
    void runsNoCheckAtAllWhenTheProjectMaintainsNoLanguages() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "t1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "t1", SKOS + "definition", "Eine Definition", "de")));

        assertThat(LanguageGapCheck.run(snapshot, List.of())).isEmpty();
    }

    /**
     * A positioned child resource carries no code of its own, so the report would otherwise show an
     * opaque minted IRI where a reader needs to know which requirement to open.
     */
    @Test
    void namesAPositionedChildResourceByItsOwnersCodeAndItsPosition() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "r1", DCTERMS + "identifier", "FR-1", null),
                iri(ID + "r1", ARKREQ + "acceptanceCriterion", ID + "ac1"),
                iri(ID + "ac1", RDF_TYPE, ARKREQ + "AcceptanceCriterion"),
                literal(ID + "ac1", ARKREQ + "position", "2", null),
                literal(ID + "ac1", ARKREQ + "criterionText", "Ein Kriterium", "de")));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN))
                .extracting(Gap::handle, Gap::typeLocalName)
                .containsExactly(tuple("FR-1 acceptanceCriterion#2", "AcceptanceCriterion"));
    }

    @Test
    void fallsBackToTheIriWhenNeitherTheResourceNorAnOwnerOffersACode() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "orphan", SKOS + "definition", "Eine Definition", "de")));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN))
                .singleElement()
                .satisfies(gap -> {
                    assertThat(gap.handle()).isNull();
                    assertThat(gap.subjectIri()).isEqualTo(ID + "orphan");
                });
    }

    /** Two owners means no single owner, and a guessed one would put a wrong code in the report. */
    @Test
    void leavesAChildReferencedByTwoOwnersWithoutABorrowedCode() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "r1", DCTERMS + "identifier", "FR-1", null),
                literal(ID + "r2", DCTERMS + "identifier", "FR-2", null),
                iri(ID + "r1", ARKREQ + "acceptanceCriterion", ID + "ac1"),
                iri(ID + "r2", ARKREQ + "acceptanceCriterion", ID + "ac1"),
                literal(ID + "ac1", ARKREQ + "criterionText", "Ein Kriterium", "de")));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN))
                .singleElement()
                .satisfies(gap -> assertThat(gap.handle()).isNull());
    }

    @Test
    void usesSkosNotationAsTheCodeWhenThereIsNoDctermsIdentifier() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "t1", SKOS + "notation", "TERM-9", null),
                literal(ID + "t1", SKOS + "definition", "Eine Definition", "de")));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN))
                .singleElement()
                .satisfies(gap -> assertThat(gap.handle()).isEqualTo("TERM-9"));
    }

    @Test
    void ordersFindingsByHandleThenPredicateSoTwoRunsOverAnUnchangedStoreAgree() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "r2", DCTERMS + "identifier", "FR-2", null),
                literal(ID + "r2", DCTERMS + "description", "Beschreibung", "de"),
                literal(ID + "r1", DCTERMS + "identifier", "FR-1", null),
                literal(ID + "r1", DCTERMS + "title", "Titel", "de"),
                literal(ID + "r1", DCTERMS + "description", "Beschreibung", "de")));

        assertThat(LanguageGapCheck.run(snapshot, DE_EN))
                .extracting(Gap::handle, Gap::predicateIri)
                .containsExactly(
                        tuple("FR-1", DCTERMS + "description"),
                        tuple("FR-1", DCTERMS + "title"),
                        tuple("FR-2", DCTERMS + "description"));
    }

    @Test
    void reportsEveryMissingLanguageOfAFieldInTheOrderTheProjectDeclaredThem() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "t1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "t1", SKOS + "definition", "Une definition", "fr")));

        assertThat(LanguageGapCheck.run(snapshot, List.of("de", "en", "fr")))
                .singleElement()
                .satisfies(gap -> assertThat(gap.missingLanguages()).containsExactly("de", "en"));
    }

    private static Triple iri(String subject, String predicate, String object) {
        return new Triple(subject, predicate, new RdfNode.Resource(object));
    }

    private static Triple literal(String subject, String predicate, String value, String languageTag) {
        return new Triple(subject, predicate, new RdfNode.Literal(value, null, languageTag));
    }
}
