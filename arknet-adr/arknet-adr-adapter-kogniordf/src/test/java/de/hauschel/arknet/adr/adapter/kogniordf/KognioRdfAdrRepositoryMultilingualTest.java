// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Multilingual behaviour of {@link KognioRdfAdrRepository} (kogn-io/arknet#357): other-language
 * variant preservation across a replacing write, the issue #258 stale-untagged-sibling sweep, the
 * legacy-literal fallback for both {@code arkarch:adrConsequences} and {@code arkarch:adrAlternatives},
 * and the UNION-hop that keeps a replacing write from orphaning a consequence/considered-option
 * child node. Mirrors {@code KognioRdfRequirementRepositoryMultilingualTest}'s shape, scoped to the
 * behaviour this issue actually adds rather than every field req's own test covers.
 */
class KognioRdfAdrRepositoryMultilingualTest {

    private static final ProjectId PROJECT = new ProjectId("adr-multilingual-test");
    /** Any fixed day - these tests exercise language round-tripping, not the stamped date itself. */
    private static final LocalDate DECIDED_ON = LocalDate.of(2026, 8, 23);
    private static final String ADR_GRAPH = "https://w3id.org/arknet/model/adr";

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfAdrRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-adr-multilingual-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        WriteFunnel funnel = new WriteFunnel(datasetLifecycle, gate, WriteFunnel.DEFAULT_WRITE_CONFLICT);
        repository = new KognioRdfAdrRepository(
                datasetLifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT, funnel);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static AdrId freshId() {
        return new AdrId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static Adr adr(AdrId id, AdrCode code, List<Consequence> consequences,
            List<ConsideredOption> consideredOptions) {
        return new Adr(id, code, "Use an embedded triple store", AdrStatus.PROPOSED,
                "The model has to live somewhere a single-user client can reach without a server.",
                "Use kognio-rdf as the embedded RDF substrate behind an out-port.",
                consequences, consideredOptions, null, List.of(), List.of(), null, List.of());
    }

    private void update(String sparqlUpdate) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(sparqlUpdate);
                return null;
            });
        }
    }

    private String currentHeadOf(AdrCode code) {
        return repository.findCurrentByCode(PROJECT, code).orElseThrow().head();
    }

    /**
     * A translation added via {@code compareAndUpdate} under a language {@code name} never carried
     * must not erase the language it was originally written under - the exact preservation
     * {@code KognioRdfRequirementRepository#otherLanguageLiterals} gives {@code title}/
     * {@code description}, now for {@code arknet:name}/{@code arkarch:adrContext}/
     * {@code arkarch:adrDecision}.
     */
    @Test
    void compareAndUpdatePreservesOtherLanguageVariantsOfNameContextDecision() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), List.of(), List.of());
        repository.create(PROJECT, original, "en");

        Adr translated = new Adr(id, original.code(), "Einen eingebetteten Triple Store verwenden",
                original.status(), original.context(), original.decision(), List.of(), List.of(), null,
                List.of(), List.of(), null, List.of());
        repository.compareAndUpdate(PROJECT, currentHeadOf(original.code()), translated,
                "de", "en", "en", Map.of(), Map.of(), null);

        Adr enSelected = repository.findByCode(PROJECT, original.code(), "en").orElseThrow();
        Adr deSelected = repository.findByCode(PROJECT, original.code(), "de").orElseThrow();
        assertEquals("Use an embedded triple store", enSelected.name());
        assertEquals("Einen eingebetteten Triple Store verwenden", deSelected.name());
    }

    /**
     * Issue #258: an existing <em>untagged</em> {@code arknet:name} literal is a stale duplicate,
     * not a genuine other-language variant, once a write under the project's own default language
     * writes the very value it would have resolved to - so it is swept rather than preserved.
     */
    @Test
    void sweepsAStaleUntaggedNameWhenTheWrittenTagEqualsTheDefaultLanguage() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), List.of(), List.of());
        // Written untagged directly at the adapter level (bypassing AdrService's own
        // LanguageTag#resolveWriteLanguage, which never lets a write land untagged) - simulating a
        // store-first (ADR-005) or pre-i18n record.
        repository.create(PROJECT, original, null);

        repository.compareAndUpdate(PROJECT, currentHeadOf(original.code()), original.accept(DECIDED_ON),
                "en", "en", "en", Map.of(), Map.of(), "en");

        String askUntagged = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/core#name> ?name . FILTER(lang(?name) = \"\") } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            assertFalse(handle.sparqlQuery().ask(askUntagged),
                    "the stale untagged literal must be swept once the default-language write lands");
        }
        assertEquals("Use an embedded triple store",
                repository.findByCode(PROJECT, original.code(), "en").orElseThrow().name());
    }

    /**
     * Review finding of kogn-io/arknet#365: a store record whose existing tag carries a
     * non-canonical case (e.g. {@code "EN"} instead of {@code "en"}) must still be recognised as
     * the very same language by {@link AdrRepository.CurrentAdr#nameContextDecisionLanguages()}/
     * {@code consequenceLanguagesByPosition()} - otherwise a same-language correction on an
     * {@link AdrStatus#ACCEPTED} decision would misread as a brand-new translation and slip past
     * {@code AdrService}'s per-field/per-position lock, since {@code AdrService} always compares
     * against {@link de.hauschel.arknet.kernel.LanguageTag#resolveWriteLanguage}'s canonicalized
     * output.
     */
    @Test
    void allLanguageTagsCanonicalizeANonCanonicalTagCaseFromTheStore() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"),
                List.of(new Consequence(1, "English wording", ConsequenceType.NEUTRAL)), List.of());
        repository.create(PROJECT, original, "en");
        String selectChild = "SELECT ?c WHERE { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.CONSEQUENCE + "> ?c } }";
        String childIri;
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            childIri = handle.sparqlQuery().select(selectChild).findFirst().orElseThrow()
                    .getValue("c").orElseThrow().toString();
        }
        // Simulates a legacy record: the name and the consequence-1 statement each carry a
        // non-canonical tag case, written directly at the triple level (bypassing
        // LanguageTag#resolveWriteLanguage, which every real write path routes through).
        update("DELETE { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/core#name> ?old } } "
                + "INSERT { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/core#name> \"Use an embedded triple store\"@EN } } "
                + "WHERE { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value()
                + "> <https://w3id.org/arknet/core#name> ?old } }");
        update("DELETE { GRAPH <" + ADR_GRAPH + "> { <" + childIri + "> <" + ArkarchVocabulary.CONSEQUENCE_STATEMENT
                + "> ?old } } "
                + "INSERT { GRAPH <" + ADR_GRAPH + "> { <" + childIri + "> <"
                + ArkarchVocabulary.CONSEQUENCE_STATEMENT + "> \"English wording\"@EN } } "
                + "WHERE { GRAPH <" + ADR_GRAPH + "> { <" + childIri + "> <" + ArkarchVocabulary.CONSEQUENCE_STATEMENT
                + "> ?old } }");

        AdrRepository.CurrentAdr current = repository.findCurrentByCode(PROJECT, original.code()).orElseThrow();

        assertTrue(current.nameContextDecisionLanguages().contains("en"),
                "a non-canonical stored tag case must still surface as the canonical \"en\"");
        assertEquals(Set.of("en"), current.nameContextDecisionLanguages());
        assertTrue(current.consequenceLanguagesByPosition().get(1).contains("en"),
                "a non-canonical stored tag case must still surface as the canonical \"en\"");
        assertEquals(Set.of("en"), current.consequenceLanguagesByPosition().get(1));
    }

    /**
     * A store-first (pre-#357) {@code arkarch:adrConsequences} literal, on a decision with no
     * structured {@code arkarch:consequence} children, is synthesised into a single {@code NEUTRAL}
     * consequence at position 1 for display - and survives a replacing write untouched, since it is
     * preserved unconditionally rather than surfaced as a domain field (see class javadoc of
     * {@link KognioRdfAdrRepository}).
     */
    @Test
    void legacyAdrConsequencesLiteralSynthesisesANeutralConsequenceAndSurvivesAWrite() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), List.of(), List.of());
        repository.create(PROJECT, original, "en");
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.ADR_CONSEQUENCES + "> \"A flat, pre-#357 consequence.\" } }");

        Adr found = repository.findByCode(PROJECT, original.code(), null).orElseThrow();
        assertEquals(List.of(new Consequence(1, "A flat, pre-#357 consequence.", ConsequenceType.NEUTRAL)),
                found.consequences());

        repository.compareAndUpdate(PROJECT, currentHeadOf(original.code()), original.accept(DECIDED_ON),
                "en", "en", "en", Map.of(), Map.of(), null);

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.ADR_CONSEQUENCES + "> \"A flat, pre-#357 consequence.\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask), "the legacy literal must survive an unrelated write");
        }
    }

    /**
     * {@link #legacyAdrConsequencesLiteralSynthesisesANeutralConsequenceAndSurvivesAWrite}'s mirror
     * for {@code arkarch:adrAlternatives}: synthesises a single outcome-less
     * {@link ConsideredOption}.
     */
    @Test
    void legacyAdrAlternativesLiteralSynthesisesAnOutcomeLessOptionAndSurvivesAWrite() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), List.of(), List.of());
        repository.create(PROJECT, original, "en");
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.ADR_ALTERNATIVES + "> \"A flat, pre-#357 alternative.\" } }");

        Adr found = repository.findByCode(PROJECT, original.code(), null).orElseThrow();
        assertEquals(1, found.consideredOptions().size());
        assertEquals("A flat, pre-#357 alternative.", found.consideredOptions().get(0).rationale());
        assertEquals(null, found.consideredOptions().get(0).outcome());

        repository.compareAndUpdate(PROJECT, currentHeadOf(original.code()), original.accept(DECIDED_ON),
                "en", "en", "en", Map.of(), Map.of(), null);

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.ADR_ALTERNATIVES + "> \"A flat, pre-#357 alternative.\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask), "the legacy literal must survive an unrelated write");
        }
    }

    /**
     * Real structured consequences take precedence over the legacy literal fallback - the same
     * "structured wins, legacy is display-only-when-nothing-structured-exists" rule
     * {@code KognioRdfRequirementRepository}'s own placeholder substitution follows.
     */
    @Test
    void structuredConsequencesWinOverALegacyLiteral() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"),
                List.of(new Consequence(1, "Real, structured consequence", ConsequenceType.POSITIVE)), List.of());
        repository.create(PROJECT, original, "en");
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.ADR_CONSEQUENCES + "> \"A flat literal nobody reads any more.\" } }");

        Adr found = repository.findByCode(PROJECT, original.code(), null).orElseThrow();

        assertEquals(List.of(new Consequence(1, "Real, structured consequence", ConsequenceType.POSITIVE)),
                found.consequences());
    }

    /**
     * The UNION-hop: {@code deleteExisting} follows {@code arkarch:consequence}/
     * {@code arkarch:consideredOption} edges and deletes the pointed-at child's own triples too - a
     * replacing write must not leave the old, re-minted-away child node's triples behind as orphaned
     * garbage.
     */
    @Test
    void compareAndUpdateDoesNotOrphanTheOldConsequenceChildNode() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"),
                List.of(new Consequence(1, "Original wording", ConsequenceType.NEUTRAL)), List.of());
        repository.create(PROJECT, original, "en");
        String oldChildIri;
        String selectChild = "SELECT ?c WHERE { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.CONSEQUENCE + "> ?c } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            oldChildIri = handle.sparqlQuery().select(selectChild).findFirst().orElseThrow()
                    .getValue("c").orElseThrow().toString();
        }

        Adr corrected = original.withConsequenceCorrections(PROJECT,
                List.of(new de.hauschel.arknet.adr.domain.ConsequenceCorrection(1, "Corrected wording",
                        ConsequenceType.POSITIVE)), Set.of());
        repository.compareAndUpdate(PROJECT, currentHeadOf(original.code()), corrected,
                "en", "en", "en", Map.of(1, "en"), Map.of(), null);

        String askOldChildHasTriples = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + oldChildIri + "> ?p ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            assertFalse(handle.sparqlQuery().ask(askOldChildHasTriples),
                    "the re-minted-away consequence child node must not survive as an orphan");
        }
        assertEquals(List.of(new Consequence(1, "Corrected wording", ConsequenceType.POSITIVE)),
                repository.findByCode(PROJECT, original.code(), null).orElseThrow().consequences());
    }

    /**
     * Other-language consequence text at an untouched position survives a write that only corrects
     * a different field - keyed by {@code arknet:position}, not by the about-to-be-deleted child
     * IRI, mirroring {@code KognioRdfRequirementRepository#otherLanguageAcceptanceCriterionTexts}.
     */
    @Test
    void compareAndUpdatePreservesAnOtherLanguageConsequenceTextAtAnUntouchedPosition() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"),
                List.of(new Consequence(1, "English wording", ConsequenceType.NEUTRAL)), List.of());
        repository.create(PROJECT, original, "en");
        // A second, German variant of the very same position, added directly (mirrors what a real
        // write with a new-language tag at that position would leave behind).
        String selectChild = "SELECT ?c WHERE { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.CONSEQUENCE + "> ?c } }";
        String childIri;
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            childIri = handle.sparqlQuery().select(selectChild).findFirst().orElseThrow()
                    .getValue("c").orElseThrow().toString();
        }
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + childIri + "> <"
                + ArkarchVocabulary.CONSEQUENCE_STATEMENT + "> \"Deutscher Wortlaut\"@de } }");

        // A write that touches a DIFFERENT field (name) but re-submits the very same consequence
        // list unchanged, exactly as AdrService#update's mutation chain would for a call that never
        // named a consequence correction.
        Adr renamed = new Adr(id, original.code(), "Renamed", original.status(), original.context(),
                original.decision(), original.consequences(), original.consideredOptions(), null,
                List.of(), List.of(), null, List.of());
        repository.compareAndUpdate(PROJECT, currentHeadOf(original.code()), renamed,
                "en", "en", "en", Map.of(1, "en"), Map.of(), null);

        Adr deSelected = repository.findByCode(PROJECT, original.code(), "de").orElseThrow();
        assertEquals("Deutscher Wortlaut", deSelected.consequences().get(0).statement());
        Adr enSelected = repository.findByCode(PROJECT, original.code(), "en").orElseThrow();
        assertEquals("English wording", enSelected.consequences().get(0).statement());
    }

    /**
     * The end-to-end guarantee the per-position new-language exemption exists for
     * (kogn-io/arknet#357): an already-{@link AdrStatus#ACCEPTED} decision is translated in full -
     * {@code name}/{@code context}/{@code decision} via {@link Adr#reviseText}, both consequences and
     * both considered options via {@link Adr#withConsequenceCorrections}/
     * {@link Adr#withConsideredOptionCorrections} - in one {@code compareAndUpdate} call, exactly the
     * shape {@code AdrService#update} builds. Both language versions must read back cleanly through
     * {@link DisplayLocale}, and neither list may have grown or shrunk in the process - the guard this
     * whole feature protects, not just the individual field-level rules the other tests in this class
     * and {@code AdrTest} pin down separately.
     */
    @Test
    void compareAndUpdateRoundtripsAFullTranslationOfAnAcceptedAdrThroughBothLocales() {
        AdrId id = freshId();
        AdrCode code = new AdrCode("ADR-1");
        Adr original = adr(id, code,
                List.of(new Consequence(1, "English consequence one", ConsequenceType.POSITIVE),
                        new Consequence(2, "English consequence two", ConsequenceType.NEGATIVE)),
                List.of(new ConsideredOption(1, "Option A", "Rationale A", OptionOutcome.CHOSEN),
                        new ConsideredOption(2, "Option B", "Rationale B", OptionOutcome.REJECTED)));
        repository.create(PROJECT, original, "en");
        repository.compareAndUpdate(PROJECT, currentHeadOf(code), original.accept(DECIDED_ON),
                "en", "en", "en", Map.of(1, "en", 2, "en"), Map.of(1, "en", 2, "en"), null);

        AdrRepository.CurrentAdr current = repository.findCurrentByCode(PROJECT, code).orElseThrow();
        assertEquals(AdrStatus.ACCEPTED, current.value().status());

        String germanName = "Einen eingebetteten Triple Store verwenden";
        String germanContext = "Das Modell muss irgendwo leben, das ein Einzelbenutzer-Client ohne Server "
                + "erreichen kann.";
        String germanDecision = "Verwende kognio-rdf als eingebettetes RDF-Substrat hinter einem Out-Port.";
        Adr translated = current.value()
                .reviseText(germanName, germanContext, germanDecision, true)
                .withConsequenceCorrections(PROJECT,
                        List.of(new de.hauschel.arknet.adr.domain.ConsequenceCorrection(
                                        1, "Deutsche Folge eins", ConsequenceType.POSITIVE),
                                new de.hauschel.arknet.adr.domain.ConsequenceCorrection(
                                        2, "Deutsche Folge zwei", ConsequenceType.NEGATIVE)),
                        Set.of(1, 2))
                .withConsideredOptionCorrections(PROJECT,
                        List.of(new de.hauschel.arknet.adr.domain.ConsideredOptionCorrection(
                                        1, "Option A (de)", "Begruendung A", OptionOutcome.CHOSEN),
                                new de.hauschel.arknet.adr.domain.ConsideredOptionCorrection(
                                        2, "Option B (de)", "Begruendung B", OptionOutcome.REJECTED)),
                        Set.of(1, 2));

        repository.compareAndUpdate(PROJECT, current.head(), translated,
                "de", "de", "de", Map.of(1, "de", 2, "de"), Map.of(1, "de", 2, "de"), null);

        Adr enSelected = repository.findByCode(PROJECT, code, "en").orElseThrow();
        assertEquals(AdrStatus.ACCEPTED, enSelected.status());
        assertEquals("Use an embedded triple store", enSelected.name());
        assertEquals(2, enSelected.consequences().size());
        assertEquals("English consequence one", enSelected.consequences().get(0).statement());
        assertEquals("English consequence two", enSelected.consequences().get(1).statement());
        assertEquals(2, enSelected.consideredOptions().size());
        assertEquals("Option A", enSelected.consideredOptions().get(0).name());
        assertEquals("Rationale A", enSelected.consideredOptions().get(0).rationale());
        assertEquals(OptionOutcome.CHOSEN, enSelected.consideredOptions().get(0).outcome());
        assertEquals("Option B", enSelected.consideredOptions().get(1).name());

        Adr deSelected = repository.findByCode(PROJECT, code, "de").orElseThrow();
        assertEquals(AdrStatus.ACCEPTED, deSelected.status());
        assertEquals(germanName, deSelected.name());
        assertEquals(germanContext, deSelected.context());
        assertEquals(germanDecision, deSelected.decision());
        assertEquals(2, deSelected.consequences().size());
        assertEquals("Deutsche Folge eins", deSelected.consequences().get(0).statement());
        assertEquals("Deutsche Folge zwei", deSelected.consequences().get(1).statement());
        assertEquals(2, deSelected.consideredOptions().size());
        assertEquals("Option A (de)", deSelected.consideredOptions().get(0).name());
        assertEquals("Begruendung A", deSelected.consideredOptions().get(0).rationale());
        assertEquals(OptionOutcome.CHOSEN, deSelected.consideredOptions().get(0).outcome());
        assertEquals("Option B (de)", deSelected.consideredOptions().get(1).name());
    }
}
