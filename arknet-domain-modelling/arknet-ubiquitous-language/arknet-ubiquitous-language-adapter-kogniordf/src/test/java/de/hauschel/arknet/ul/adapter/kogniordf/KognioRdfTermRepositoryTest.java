// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetShutdownOutcome;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import io.kogn.rdf.versioning.EntryKind;
import io.kogn.rdf.versioning.HistoryEntry;
import io.kogn.rdf.versioning.PublishState;
import io.kogn.rdf.versioning.RevisionToken;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.persistence.testsupport.VersioningProbe;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.ul.domain.TermConcurrentlyModifiedException;
import de.hauschel.arknet.ul.domain.TermCycleException;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermLabelMismatchException;
import de.hauschel.arknet.ul.domain.TermNotFoundException;
import de.hauschel.arknet.ul.domain.TermReferencedException;

/**
 * Integration test for {@link KognioRdfTermRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 */
class KognioRdfTermRepositoryTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final DatasetId DATASET_A = new DatasetId(PROJECT_A.value());
    private static final String SKOS_CONCEPT = "http://www.w3.org/2004/02/skos/core#Concept";
    private static final String TERMS_GRAPH = ContentGraph.GRAPH_IRI;
    private static final String SKOS_RELATED = "http://www.w3.org/2004/02/skos/core#related";
    private static final String SKOS_BROADER = "http://www.w3.org/2004/02/skos/core#broader";

    /**
     * The store's on-disk home, managed by JUnit rather than {@code Files.createTempDirectory},
     * which left its directories behind - harmless while the store is {@code IN_MEMORY}, but
     * still an inode left in {@code /tmp} for every test run. Deleted after {@link #tearDown()}
     * has shut the store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private VersioningProbe probe;
    private TermRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = KognioRdfTermRepositoryFactory.over(datasetLifecycle);
        probe = new VersioningProbe(datasetLifecycle, ContentGraph.GRAPH_IRI);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    /** Fresh, valid opaque identity - every test picks its own so ids never collide. */
    private static TermId freshId() {
        return new TermId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    @Test
    void createsAndFindsTermByCode() {
        Term term = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift",
                "Rueckerstattung eines bereits gezahlten Betrags.", null);

        repository.create(PROJECT_A, term, null);
        Optional<Term> found = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null);

        assertEquals(Optional.of(term), found);
        assertEquals("Gutschrift", found.orElseThrow().prefLabel());
        assertEquals("Rueckerstattung eines bereits gezahlten Betrags.", found.orElseThrow().definition());
    }

    @Test
    void findAllContainsAllCreatedTerms() {
        Term first = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(PROJECT_A, first, null);
        assertEquals(1, repository.findAll(PROJECT_A, null).size());

        Term second = new Term(freshId(), new TermCode("TERM-2"), "Bestellung", "def b", null);
        repository.create(PROJECT_A, second, null);

        List<Term> all = repository.findAll(PROJECT_A, null);
        assertEquals(2, all.size());
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void createRejectsAnAlreadyExistingIdentityAndPersistsNothingElse() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(PROJECT_A, term, null);

        Term collidingId = new Term(id, new TermCode("TERM-2"), "Bestellung", "def b", null);

        assertThrows(ResourceAlreadyExistsException.class,
                () -> repository.create(PROJECT_A, collidingId, null));
        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertEquals(Optional.of(term), repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null));
    }

    /**
     * Identity collision and code collision are distinct failure modes: two different, freshly
     * minted identities both claiming {@code TERM-1} must be rejected by code, not by identity -
     * the sibling requirements BC relies on {@code dcterms:identifier} being unique.
     */
    @Test
    void createRejectsADuplicateCodeUnderADifferentIdentityAndPersistsNothingElse() {
        TermCode code = new TermCode("TERM-1");
        Term first = new Term(freshId(), code, "Gutschrift", "def a", null);
        repository.create(PROJECT_A, first, null);

        Term collidingCode = new Term(freshId(), code, "Bestellung", "def b", null);

        assertThrows(DuplicateTermCodeException.class,
                () -> repository.create(PROJECT_A, collidingCode, null));
        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertEquals(Optional.of(first), repository.findByCode(PROJECT_A, code, null));
    }

    @Test
    void updateRejectsAnUnknownCode() {
        assertThrows(TermNotFoundException.class,
                () -> repository.update(PROJECT_A, new TermCode("TERM-1"), "Erstattung", null, null, null, null, null));
        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
    }

    @Test
    void updateChangesOnlyTheGivenFieldAndPersistsTheChange() {
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A,
                new Term(freshId(), code, "Gutschrift", "Erste Definition.", null), null);

        Term result = repository.update(PROJECT_A, code, null, "Ueberarbeitete Definition.", null, null, null, null);

        assertEquals("Gutschrift", result.prefLabel());
        assertEquals("Ueberarbeitete Definition.", result.definition());
        assertEquals(Optional.of(result), repository.findByCode(PROJECT_A, code, null));
        assertEquals(1, repository.findAll(PROJECT_A, null).size());
    }

    /** The opaque identity is preserved across an update - only the term's state changes. */
    @Test
    void updatePreservesTheOpaqueIdentity() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Erste Definition.", null), null);

        repository.update(PROJECT_A, code, null, "Ueberarbeitete Definition.", null, null, null, null);

        assertEquals(id, repository.findByCode(PROJECT_A, code, null).orElseThrow().id());
    }

    /**
     * Bug 1 (data loss, found reviewing the {@code term_update} PR): a store-first term can
     * legally carry {@code skos:prefLabel} in several languages. Before the fix,
     * {@code update()} took a full {@link Term} - already collapsed to a single label by whichever
     * read produced it - and wholesale-replaced the subject's triples with it, silently deleting
     * every other language variant even when the caller only touched {@code definition}. This
     * pins the fixed contract directly against the raw store: both original {@code prefLabel}
     * variants must still be present after an update that never mentions {@code prefLabel} at all.
     */
    @Test
    void updateChangingOnlyDefinitionPreservesEveryPrefLabelLanguageVariant() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "Erste Definition.", "\"Kunde\"@de, \"Kunde\"@en");

        repository.update(PROJECT_A, code, null, "Ueberarbeitete Definition.", null, null, null, null);

        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"),
                "the German prefLabel variant must survive an update that never touches prefLabel");
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "en"),
                "the English prefLabel variant must survive an update that never touches prefLabel");
        assertEquals("Ueberarbeitete Definition.", repository.findByCode(PROJECT_A, code, null).orElseThrow().definition());
    }

    /**
     * kogn-io/arknet#742: a legacy term carrying a different word under each language tag breaks
     * {@code ulshapes:Term-prefLabel-oneWord}. An update that leaves the labels as they are is
     * rejected; a rename without a language, which writes one word under every tag, repairs it.
     */
    @Test
    void aLegacyTermWithADifferentWordPerLanguageCanOnlyBeRenamed() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "Erste Definition.", "\"Kunde\"@de, \"Customer\"@en");

        WriteConstraintViolationException rejected = assertThrows(WriteConstraintViolationException.class,
                () -> repository.update(PROJECT_A, code, null, "Ueberarbeitete Definition.", null, null, null, null));
        assertTrue(rejected.getMessage().contains("one word under every language tag"), rejected.getMessage());

        repository.update(PROJECT_A, code, "Kunde", null, null, null, null, null);

        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "en"));
        assertFalse(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Customer", "en"));
    }

    /**
     * FR-10 (kogn-io/arknet#502): a {@code prefLabel} supplied without a {@code language} is a
     * rename, not a scoped correction - it replaces the word under EVERY language tag the term
     * already carries at once, not just an untagged slot. Renaming "Kunde"@de/"Customer"@en
     * therefore writes "Bestandskunde" under BOTH tags, leaves no untagged literal behind, and
     * leaves neither original word in the store.
     */
    @Test
    void updateGivenAPrefLabelWithoutLanguageRenamesEveryLanguageTagAtOnce() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"@de, \"Customer\"@en");

        Term result = repository.update(PROJECT_A, code, "Bestandskunde", null, null, null, null, null);

        assertEquals("Bestandskunde", result.prefLabel());
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Bestandskunde", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Bestandskunde", "en"));
        assertFalse(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertFalse(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Customer", "en"));
        assertFalse(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Bestandskunde"));
    }

    /**
     * A second rename without a {@code language} replaces the first rename's word under every tag
     * again - it does not accumulate anything, and the term still carries exactly one
     * {@code prefLabel} literal per language tag it started with.
     */
    @Test
    void updateGivenAPrefLabelWithoutLanguageTwiceRenamesToTheLatestWordEachTime() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"@de, \"Customer\"@en");

        repository.update(PROJECT_A, code, "Bestandskunde", null, null, null, null, null);
        repository.update(PROJECT_A, code, "Stammkunde", null, null, null, null, null);

        assertFalse(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Bestandskunde", "de"));
        assertFalse(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Bestandskunde", "en"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Stammkunde", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Stammkunde", "en"));
        assertFalse(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Stammkunde"));
    }

    /**
     * FR-10: a {@code prefLabel} supplied WITH an explicit {@code language} is a
     * translation-scoped write, not a rename - it must equal every word the term already carries.
     * "Stammkunde"@de is therefore rejected against a term whose existing label is
     * "Kunde"/"Customer" (both named in the exception), and the store is left completely
     * untouched.
     */
    @Test
    void updateGivenAPrefLabelWithLanguageThatDiffersFromTheExistingLabelIsRejected() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"@de, \"Customer\"@en");

        TermLabelMismatchException ex = assertThrows(TermLabelMismatchException.class, () -> repository.update(
                PROJECT_A, code, "Stammkunde", null, "de", null, null, null));

        assertTrue(ex.getMessage().contains("Kunde"));
        assertTrue(ex.getMessage().contains("Customer"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Customer", "en"));
        assertFalse(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Stammkunde", "de"));
    }

    /**
     * A rejected translation-scoped write never reaches the funnel at all - the draft's token does
     * not move, exactly like the field-less no-op guarded by
     * {@link #updateWithNoFieldsIsANoOpThatLeavesTheDraftUntouched()}.
     */
    @Test
    void updateGivenAPrefLabelMismatchLeavesTheDraftUntouched() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Kunde", "Erste Definition.", null), "de");
        Optional<RevisionToken> tokenAfterCreate = tokenOf(id);

        assertThrows(TermLabelMismatchException.class, () -> repository.update(
                PROJECT_A, code, "Customer", null, "en", null, null, null));

        assertEquals(tokenAfterCreate, tokenOf(id), "a rejected mismatch must not write the draft");
    }

    /**
     * A translation-scoped write (explicit {@code language}) of the SAME word the term already
     * carries is not a mismatch: it adds that tag alongside the existing one(s) instead of
     * replacing anything (FR-10) - "Kunde"@de plus an explicit "en" write of the identical word
     * ends up as "Kunde" under both @de and @en.
     */
    @Test
    void updateGivenAPrefLabelWithLanguageAndTheSameWordAddsTheTag() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"@de");

        Term result = repository.update(PROJECT_A, code, "Kunde", null, "en", null, null, null);

        assertEquals("Kunde", result.prefLabel());
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "en"));
    }

    /**
     * A rename also adds the project's own default language tag alongside whatever tags the term
     * already carries (FR-10): renaming a term that so far carries only "Kunde"@de, in a project
     * whose default language is "en", writes the new word under BOTH @de and @en - not just the
     * tag(s) already present.
     */
    @Test
    void updateGivenAPrefLabelWithoutLanguageAlsoWritesTheProjectDefaultTag() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"@de");

        Term result = repository.update(PROJECT_A, code, "Client", null, null, "en", null, null);

        assertEquals("Client", result.prefLabel());
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Client", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Client", "en"));
        assertFalse(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
    }

    /**
     * A rename of a term that carries no tagged label at all, in a project with no default
     * language either, has nothing to write the new word under except the untagged slot - it
     * stays untagged, same as before FR-10.
     */
    @Test
    void updateGivenAPrefLabelWithoutLanguageOnAnUntaggedTermWithNoDefaultStaysUntagged() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Kunde", "def a", null), null);

        Term result = repository.update(PROJECT_A, code, "Stammkunde", null, null, null, null, null);

        assertEquals("Stammkunde", result.prefLabel());
        assertTrue(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Stammkunde"));
        assertFalse(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Kunde"));
    }

    /**
     * Issue #258, decision 3, still valid for a same-word translation write (FR-10): writing
     * {@code prefLabel} under the tag equal to {@code defaultLanguage} sweeps away a stale
     * untagged sibling of the same predicate instead of preserving it as a spurious "other"
     * language variant.
     */
    @Test
    void updateSweepsAnUntaggedPrefLabelWhenTheWrittenTagEqualsTheProjectDefault() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"");

        Term result = repository.update(PROJECT_A, code, "Kunde", null, "de", "de", null, null);

        assertEquals("Kunde", result.prefLabel());
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertFalse(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Kunde"));
    }

    /**
     * Regression guard for the same sweep (issue #258): writing {@code prefLabel} under an
     * <em>explicit</em>, non-default language must leave an existing untagged variant alone - the
     * sweep only ever fires when the written tag equals the project's default. Same word as the
     * existing untagged label (FR-10): this is a translation-scoped write, adding the @fr tag
     * alongside the untagged one.
     */
    @Test
    void updateKeepsAnUntaggedPrefLabelWhenTheWrittenTagDiffersFromTheProjectDefault() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"");

        Term result = repository.update(PROJECT_A, code, "Kunde", null, "fr", "de", null, null);

        assertEquals("Kunde", result.prefLabel());
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "fr"));
        assertTrue(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Kunde"));
    }

    /**
     * P0 regression (PR #230 review comment): {@code deleteTriplesOfLanguage}'s
     * {@code FILTER(lang(?o) = "tag")} compared the raw, unnormalized BCP-47 tag
     * case-sensitively - a later write spelling the same language with a different case
     * (e.g. {@code "DE"} where the term was originally written with {@code "de"}) missed the
     * existing literal and inserted a second, differently-cased one instead of replacing it,
     * defeating {@code sh:uniqueLang}. Fixed by canonicalizing every tag through
     * {@code canonicalLanguageTag} before both writing a literal and building the delete filter,
     * so the two calls always agree on one case. Same word as the existing label (FR-10): a
     * translation-scoped write of "Kunde" under "DE" must land as a single, canonicalized @de
     * literal, not a second, differently-cased one.
     */
    @Test
    void updateWithADifferentlyCasedLanguageTagReplacesTheSameStoredVariantInsteadOfDuplicatingIt() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        Term term = new Term(id, code, "Kunde", "Person, die eine Bestellung aufgibt.", null);
        repository.create(PROJECT_A, term, "de");

        Term result = repository.update(PROJECT_A, code, "Kunde", null, "DE", null, null, null);

        assertEquals("Kunde", result.prefLabel());
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertFalse(subjectHasPrefLabelWithRawLanguageTag(PROJECT_A, id, "DE"),
                "a case-differing language argument must not leave a duplicate, differently-cased literal behind");
    }

    /**
     * A region-subtagged language (kogn-io/arknet#711): kognio-rdf 0.4.0 lower-cases every language
     * tag on the way into the store, so a definition written under arknet's canonical {@code de-CH}
     * is stored as {@code @de-ch}. A delete filter comparing {@code lang(?o)} against
     * {@code "de-CH"} case-sensitively missed it and left two definitions of one language behind.
     */
    @Test
    void updatingARegionTaggedDefinitionTwiceLeavesExactlyOneLiteralOfThatLanguage() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Kunde", "Erste Fassung.", null), "de-CH");

        repository.update(PROJECT_A, code, null, "Zweite Fassung.", "de-CH", null, null, null);
        repository.update(PROJECT_A, code, null, "Dritte Fassung.", "de-CH", null, null, null);

        assertEquals(List.of("Dritte Fassung."), definitionsOf(PROJECT_A, id));
    }

    /**
     * Data written before kognio-rdf 0.4.0 kept the tag as written ({@code @de-CH}); data written
     * since is lower-cased ({@code @de-ch}). The language-scoped delete must catch the old spelling
     * too, or the first update after the upgrade leaves the old definition next to the new one
     * (kogn-io/arknet#711).
     */
    @Test
    void updatingADefinitionStoredUnderTheOldMixedCaseRegionTagReplacesIt() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Kunde", "Neue Fassung.", null), "en");
        String legacyDefinition = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> <http://www.w3.org/2004/02/skos/core#definition> "
                + "\"Alte Fassung.\"@de-CH } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(legacyDefinition);
                return null;
            });
            assertTrue(handle.sparqlQuery().ask("ASK { GRAPH ?g { ?s ?p ?o FILTER(lang(?o) = \"de-CH\") } }"),
                    "precondition: the legacy literal must really carry the mixed-case tag");
        }

        repository.update(PROJECT_A, code, null, "Zweite Fassung.", "de-CH", null, null, null);

        assertEquals(List.of("Neue Fassung.", "Zweite Fassung."), definitionsOf(PROJECT_A, id).stream().sorted().toList());
    }

    /**
     * {@code term_add}'s new {@code language} argument (issue #228): a term registered with an
     * explicit language tag stores its {@code prefLabel}/{@code definition} as language-tagged
     * literals, not the plain untagged ones {@code create} wrote before this argument existed.
     */
    @Test
    void createWritesALanguageTaggedPrefLabelAndDefinition() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Kunde", "Person, die eine Bestellung aufgibt.", null);

        repository.create(PROJECT_A, term, "de");

        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertFalse(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Kunde"));
    }

    /** Omitting {@code language} on {@code term_add} keeps writing a plain, untagged literal - unchanged. */
    @Test
    void createWithoutLanguageWritesAnUntaggedPrefLabel() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Kunde", "Person, die eine Bestellung aufgibt.", null);

        repository.create(PROJECT_A, term, null);

        assertTrue(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Kunde"));
    }

    // ---- skos:broader (issue #252) -----------------------------------------------------------

    @Test
    void createWritesABroaderReferenceToAnAlreadyExistingTerm() {
        TermCode broaderCode = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), broaderCode, "Actor", "Someone or something acting.",
                null), null);
        Term narrower = new Term(freshId(), new TermCode("TERM-2"), "Human Actor", "A human acting.",
                broaderCode);

        repository.create(PROJECT_A, narrower, null);

        assertEquals(broaderCode, repository.findByCode(PROJECT_A, new TermCode("TERM-2"), null)
                .orElseThrow().broader());
    }

    @Test
    void createRejectsABroaderCodeThatDoesNotResolveAndPersistsNothing() {
        Term narrower = new Term(freshId(), new TermCode("TERM-1"), "Human Actor", "A human acting.",
                new TermCode("TERM-404"));

        assertThrows(TermNotFoundException.class, () -> repository.create(PROJECT_A, narrower, null));
        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
    }

    @Test
    void updateSetsABroaderReferenceOnAnAlreadyExistingTerm() {
        TermCode broaderCode = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), broaderCode, "Actor", "Someone or something acting.",
                null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Human Actor", "A human acting.", null), null);

        Term result = repository.update(PROJECT_A, code, null, null, null, null, Optional.of(broaderCode), null);

        assertEquals(broaderCode, result.broader());
        assertEquals(broaderCode, repository.findByCode(PROJECT_A, code, null).orElseThrow().broader());
    }

    @Test
    void updateClearsAnAlreadySetBroaderReference() {
        TermCode broaderCode = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), broaderCode, "Actor", "Someone or something acting.",
                null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Human Actor", "A human acting.",
                broaderCode), null);

        Term result = repository.update(PROJECT_A, code, null, null, null, null, Optional.empty(), null);

        assertNull(result.broader());
        assertNull(repository.findByCode(PROJECT_A, code, null).orElseThrow().broader());
    }

    @Test
    void updateOmittingBroaderLeavesAnAlreadySetOneUnchanged() {
        TermCode broaderCode = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), broaderCode, "Actor", "Someone or something acting.",
                null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Human Actor", "A human acting.",
                broaderCode), null);

        Term result = repository.update(PROJECT_A, code, "Human Being", null, null, null, null, null);

        assertEquals("Human Being", result.prefLabel());
        assertEquals(broaderCode, result.broader());
        assertEquals(broaderCode, repository.findByCode(PROJECT_A, code, null).orElseThrow().broader());
    }

    // ---- skos:related (kogn-io/arknet#420) ---------------------------------------------------

    @Test
    void createWritesRelatedReferencesToAlreadyExistingTerms() {
        TermCode first = new TermCode("TERM-1");
        TermCode second = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), first, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), second, "Dataset", "Ein Datensatz.", null), null);
        Term anchor = new Term(freshId(), new TermCode("TERM-3"), "Anker", "Ein opakes Merkmal.", null,
                List.of(first, second));

        repository.create(PROJECT_A, anchor, null);

        assertEquals(List.of(first, second),
                repository.findByCode(PROJECT_A, new TermCode("TERM-3"), null).orElseThrow().related());
    }

    /** One unknown peer among known ones aborts the whole create - no partial edge set is written. */
    @Test
    void createRejectsARelatedCodeThatDoesNotResolveAndPersistsNothing() {
        TermCode known = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), known, "Projekt", "Ein Vorhaben.", null), null);
        Term anchor = new Term(freshId(), new TermCode("TERM-2"), "Anker", "Ein opakes Merkmal.", null,
                List.of(known, new TermCode("TERM-404")));

        assertThrows(TermNotFoundException.class, () -> repository.create(PROJECT_A, anchor, null));
        assertEquals(1, repository.findAll(PROJECT_A, null).size());
    }

    /** Only the forward direction is asserted: the peer's own read shows no edge back. */
    @Test
    void createWritesTheForwardDirectionOnly() {
        TermCode peer = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), peer, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), new TermCode("TERM-2"), "Anker", "Ein Merkmal.",
                null, List.of(peer)), null);

        assertEquals(List.of(), repository.findByCode(PROJECT_A, peer, null).orElseThrow().related(),
                "the out-port hands back the forward direction alone - merging is TermService's job");
        assertEquals(List.of(new TermCode("TERM-2")),
                repository.findRelatedCodes(PROJECT_A, idOf(PROJECT_A, peer)),
                "the backward direction is a read of its own");
    }

    @Test
    void updateSetsRelatedReferencesOnAnAlreadyExistingTerm() {
        TermCode peer = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), peer, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Anker", "Ein Merkmal.", null), null);

        Term result = repository.update(PROJECT_A, code, null, null, null, null, null, List.of(peer));

        assertEquals(List.of(peer), result.related());
        assertEquals(List.of(peer), repository.findByCode(PROJECT_A, code, null).orElseThrow().related());
    }

    @Test
    void updateReplacesTheExistingRelatedReferencesWholesale() {
        TermCode first = new TermCode("TERM-1");
        TermCode second = new TermCode("TERM-2");
        TermCode code = new TermCode("TERM-3");
        repository.create(PROJECT_A, new Term(freshId(), first, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), second, "Dataset", "Ein Datensatz.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Anker", "Ein Merkmal.", null,
                List.of(first)), null);

        Term result = repository.update(PROJECT_A, code, null, null, null, null, null, List.of(second));

        assertEquals(List.of(second), result.related());
        assertEquals(List.of(second), repository.findByCode(PROJECT_A, code, null).orElseThrow().related());
    }

    @Test
    void updateClearsAlreadySetRelatedReferences() {
        TermCode peer = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), peer, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Anker", "Ein Merkmal.", null,
                List.of(peer)), null);

        Term result = repository.update(PROJECT_A, code, null, null, null, null, null, List.of());

        assertEquals(List.of(), result.related());
        assertEquals(List.of(), repository.findByCode(PROJECT_A, code, null).orElseThrow().related());
    }

    // ---- skos:related / skos:broader: store-first edges to a blank node no tool can write ----

    /**
     * A store-first {@code skos:related} edge onto a blank node - {@code [ a skos:Concept ]}
     * written directly into the content graph - is copied into the new slice with the rest of the
     * draft by an update that does not itself replace {@code related}. The gate validates the whole
     * slice, so {@code ulshapes:Term-related}'s {@code sh:nodeKind sh:IRI} rejects the update: the
     * rejection names the property, nothing is written, and the edge stays.
     */
    @Test
    void updateOfATermWithABlankNodeRelatedEdgeIsRejectedAndTheEdgeStays() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Anker", "Ein Merkmal.", null), null);
        givenEdgeToFreshBlankNodeConcept(id, SKOS_RELATED);

        WriteConstraintViolationException rejection = assertThrows(WriteConstraintViolationException.class,
                () -> repository.update(PROJECT_A, code, null, "Neue Fassung.", null, null, null, null));

        assertTrue(rejection.getMessage().contains(SKOS_RELATED),
                "the rejection must name the offending property, got: " + rejection.getMessage());
        assertTrue(edgeTargetsABlankNode(id, SKOS_RELATED),
                "the rejected update must leave the blank-node edge in place");
        assertEquals(List.of("Ein Merkmal."), definitionsOf(PROJECT_A, id));
    }

    /** {@code skos:broader} counterpart of {@link #updateOfATermWithABlankNodeRelatedEdgeIsRejectedAndTheEdgeStays}. */
    @Test
    void updateOfATermWithABlankNodeBroaderEdgeIsRejectedAndTheEdgeStays() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Anker", "Ein Merkmal.", null), null);
        givenEdgeToFreshBlankNodeConcept(id, SKOS_BROADER);

        WriteConstraintViolationException rejection = assertThrows(WriteConstraintViolationException.class,
                () -> repository.update(PROJECT_A, code, null, "Neue Fassung.", null, null, null, null));

        assertTrue(rejection.getMessage().contains(SKOS_BROADER),
                "the rejection must name the offending property, got: " + rejection.getMessage());
        assertTrue(edgeTargetsABlankNode(id, SKOS_BROADER),
                "the rejected update must leave the blank-node edge in place");
        assertEquals(List.of("Ein Merkmal."), definitionsOf(PROJECT_A, id));
    }

    private void givenEdgeToFreshBlankNodeConcept(final TermId subjectId, final String predicate) {
        String insert = "INSERT DATA { GRAPH <" + TERMS_GRAPH + "> { "
                + "<" + subjectId.value().value() + "> <" + predicate + "> [ a <" + SKOS_CONCEPT + "> ] } }";
        try (DatasetHandle handle = lifecycle.acquire(DATASET_A)) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    private boolean edgeTargetsABlankNode(final TermId subjectId, final String predicate) {
        try (DatasetHandle handle = lifecycle.acquire(DATASET_A)) {
            return handle.sparqlQuery().ask("ASK { GRAPH <" + TERMS_GRAPH + "> { <" + subjectId.value().value()
                    + "> <" + predicate + "> ?o . ?o a <" + SKOS_CONCEPT + "> FILTER(isBlank(?o)) } }");
        }
    }

    @Test
    void updateOmittingRelatedLeavesAlreadySetOnesUnchanged() {
        TermCode peer = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), peer, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Anker", "Ein Merkmal.", null,
                List.of(peer)), null);

        Term result = repository.update(PROJECT_A, code, "Projektanker", null, null, null, null, null);

        assertEquals("Projektanker", result.prefLabel());
        assertEquals(List.of(peer), result.related());
        assertEquals(List.of(peer), repository.findByCode(PROJECT_A, code, null).orElseThrow().related());
    }

    @Test
    void updateRejectsARelatedCodeThatDoesNotResolveAndPersistsNothing() {
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), code, "Anker", "Ein Merkmal.", null), null);

        assertThrows(TermNotFoundException.class, () -> repository.update(
                PROJECT_A, code, null, null, null, null, null, List.of(new TermCode("TERM-404"))));
        assertEquals(List.of(), repository.findByCode(PROJECT_A, code, null).orElseThrow().related());
    }

    /**
     * Unlike {@code skos:broader}, the relation is symmetric and mutual assertion is legal - it
     * must not be mistaken for a cycle to break.
     */
    @Test
    void updateAllowsTwoTermsToNameEachOther() {
        TermCode first = new TermCode("TERM-1");
        TermCode second = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), first, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), second, "Anker", "Ein Merkmal.", null), null);

        repository.update(PROJECT_A, first, null, null, null, null, null, List.of(second));
        repository.update(PROJECT_A, second, null, null, null, null, null, List.of(first));

        assertEquals(List.of(second), repository.findByCode(PROJECT_A, first, null).orElseThrow().related());
        assertEquals(List.of(first), repository.findByCode(PROJECT_A, second, null).orElseThrow().related());
    }

    /**
     * Rejected before the store is touched. The port has to guard this itself, not lean on
     * {@code TermService}: {@link Term}'s own constructor would otherwise raise it only while
     * {@code update} renders its answer - by which time the triple is already written.
     */
    @Test
    void updateRejectsATermAsItsOwnRelatedPeerAndPersistsNothing() {
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), code, "Anker", "Ein Merkmal.", null), null);

        assertThrows(IllegalArgumentException.class, () -> repository.update(
                PROJECT_A, code, null, null, null, null, null, List.of(code)));
        assertEquals(List.of(), repository.findByCode(PROJECT_A, code, null).orElseThrow().related());
    }

    @Test
    void updateRejectsTheSameRelatedPeerTwiceAndPersistsNothing() {
        TermCode peer = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), peer, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Anker", "Ein Merkmal.", null), null);

        assertThrows(IllegalArgumentException.class, () -> repository.update(
                PROJECT_A, code, null, null, null, null, null, List.of(peer, peer)));
        assertEquals(List.of(), repository.findByCode(PROJECT_A, code, null).orElseThrow().related());
    }

    /** The backward read is scoped to the project, like every other read here. */
    @Test
    void findRelatedCodesIsEmptyForAnUnreferencedTerm() {
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), code, "Projekt", "Ein Vorhaben.", null), null);

        assertEquals(List.of(), repository.findRelatedCodes(PROJECT_A, idOf(PROJECT_A, code)));
    }

    /**
     * A term still named as another term's {@code skos:related} peer blocks the delete, exactly as
     * a {@code skos:broader} target does - the edge lives on the other term's subject and would
     * otherwise be left dangling.
     */
    @Test
    void deleteRejectsATermStillReferencedAsARelatedPeer() {
        TermCode peer = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), peer, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), new TermCode("TERM-2"), "Anker", "Ein Merkmal.",
                null, List.of(peer)), null);

        TermReferencedException ex = assertThrows(TermReferencedException.class,
                () -> repository.delete(PROJECT_A, peer));
        assertEquals(List.of("skos:related"), ex.referencingPredicates());
        assertTrue(repository.findByCode(PROJECT_A, peer, null).isPresent(),
                "a rejected delete must leave the term untouched");
    }

    /** The term asserting the edge is deletable - nothing points at it. */
    @Test
    void deleteAllowsTheTermThatAssertsTheRelatedEdge() {
        TermCode peer = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), peer, "Projekt", "Ein Vorhaben.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Anker", "Ein Merkmal.", null,
                List.of(peer)), null);

        repository.delete(PROJECT_A, code);

        assertTrue(repository.findByCode(PROJECT_A, code, null).isEmpty());
        assertEquals(List.of(), repository.findRelatedCodes(PROJECT_A, idOf(PROJECT_A, peer)));
    }

    /** The opaque identity behind a business code, for the reverse-direction read. */
    private TermId idOf(ProjectId projectId, TermCode code) {
        return repository.findByCode(projectId, code, null).orElseThrow().id();
    }

    @Test
    void updateRejectsABroaderCodeThatDoesNotResolveAndPersistsNothing() {
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), code, "Human Actor", "A human acting.", null), null);

        assertThrows(TermNotFoundException.class, () -> repository.update(
                PROJECT_A, code, null, null, null, null, Optional.of(new TermCode("TERM-404")), null));
        assertNull(repository.findByCode(PROJECT_A, code, null).orElseThrow().broader());
    }

    @Test
    void updateRejectsATermAsItsOwnBroaderTermAndPersistsNothing() {
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), code, "Actor", "Someone or something acting.", null),
                null);

        assertThrows(TermCycleException.class, () -> repository.update(
                PROJECT_A, code, null, null, null, null, Optional.of(code), null));
        assertNull(repository.findByCode(PROJECT_A, code, null).orElseThrow().broader());
    }

    /**
     * Transitive cycle protection: TERM-3's broader is TERM-2, TERM-2's broader is TERM-1 - trying
     * to then set TERM-1's own broader to TERM-3 would close the loop
     * ({@code TERM-1 -> TERM-3 -> TERM-2 -> TERM-1}) and must be rejected, even though TERM-1 and
     * TERM-3 are not directly linked to each other yet.
     */
    @Test
    void updateRejectsATransitiveCycleAndPersistsNothing() {
        TermCode term1 = new TermCode("TERM-1");
        TermCode term2 = new TermCode("TERM-2");
        TermCode term3 = new TermCode("TERM-3");
        repository.create(PROJECT_A, new Term(freshId(), term1, "Actor", "Someone or something acting.", null),
                null);
        repository.create(PROJECT_A, new Term(freshId(), term2, "Human Actor", "A human acting.", term1),
                null);
        repository.create(PROJECT_A, new Term(freshId(), term3, "Customer", "A buying human.", term2), null);

        assertThrows(TermCycleException.class, () -> repository.update(
                PROJECT_A, term1, null, null, null, null, Optional.of(term3), null));
        assertNull(repository.findByCode(PROJECT_A, term1, null).orElseThrow().broader());
    }

    /**
     * Bug 2 (concurrency, found reviewing the same PR): a genuine store-level write conflict
     * (kogn-io/rdf-core#18) during {@code update()} must surface as the dedicated
     * {@link TermConcurrentlyModifiedException} - never as {@link DuplicateTermCodeException},
     * which {@code update()} can no longer even provoke since it never rewrites
     * {@code dcterms:identifier}, and never as the raw RDF4J exception. No real overlapping
     * threads are needed - a decorator forces the store's commit-time conflict signal on every
     * write instead.
     *
     * <p>This is the <em>exhaustion</em> path, not a single catch block: the
     * decorator fails every attempt, so {@link KognioRdfTermRepository#update}'s bounded retry
     * loop runs all of its attempts (each one re-reading and re-validating) before the last
     * conflict reaches the caller. The transient, self-healing case - one losing attempt, then a
     * successful one - is covered by
     * {@link #updateRetriesAndKeepsBothChangesWhenAConcurrentWriterAdvancedTheHead()}.</p>
     */
    @Test
    void updateTranslatesAGenuineWriteConflictIntoTermConcurrentlyModifiedException() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Erste Definition.", null), null);

        TermRepository conflicting = KognioRdfTermRepositoryFactory.over(new ConflictingWriteLifecycle(lifecycle));

        assertThrows(TermConcurrentlyModifiedException.class,
                () -> conflicting.update(PROJECT_A, code, null, "Ueberarbeitete Definition.", null, null, null, null));
        assertEquals(Optional.of(new Term(id, code, "Gutschrift", "Erste Definition.", null)),
                repository.findByCode(PROJECT_A, code, null));
    }

    /** Whether {@code subjectIri} carries a {@code skos:prefLabel} literal with exactly this value and tag. */
    private boolean subjectHasLanguageTaggedPrefLabel(ProjectId projectId, TermId id, String value, String tag) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> <http://www.w3.org/2004/02/skos/core#prefLabel> \""
                + value + "\"@" + tag + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /** Every {@code skos:definition} value of {@code id}, whatever its language tag. */
    private List<String> definitionsOf(ProjectId projectId, TermId id) {
        String query = "SELECT ?o WHERE { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> <http://www.w3.org/2004/02/skos/core#definition> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((Literal) row.getValue("o").orElseThrow()).getLexicalForm())
                    .toList();
        }
    }

    /** Whether {@code subjectIri} carries a plain, untagged {@code skos:prefLabel} literal with exactly this value. */
    private boolean subjectHasUntaggedPrefLabel(ProjectId projectId, TermId id, String value) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> <http://www.w3.org/2004/02/skos/core#prefLabel> ?label . "
                + "FILTER(lang(?label) = \"\" && str(?label) = \"" + value + "\") } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /**
     * Whether {@code id} carries a {@code skos:prefLabel} literal whose RAW, unnormalized
     * language tag is exactly {@code rawTag} - bypasses the {@code "value"@tag} triple-pattern
     * matching {@link #subjectHasLanguageTaggedPrefLabel} relies on (RDF term equality may treat
     * differently-cased tags as the same term) and instead mirrors production's own
     * {@code deleteTriplesOfLanguage} filter ({@code FILTER(lang(?o) = "tag")}, a case-sensitive
     * string comparison against the tag exactly as stored) so a test can tell a canonicalized
     * write from a raw, un-normalized one.
     */
    private boolean subjectHasPrefLabelWithRawLanguageTag(ProjectId projectId, TermId id, String rawTag) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> <http://www.w3.org/2004/02/skos/core#prefLabel> ?o . "
                + "FILTER(lang(?o) = \"" + rawTag + "\") } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /** Wraps a real {@link DatasetLifecycle}, decorating every acquired transaction's {@link DatasetTx}. */
    private static final class ConflictingWriteLifecycle implements DatasetLifecycle {

        private final DatasetLifecycle delegate;

        ConflictingWriteLifecycle(DatasetLifecycle delegate) {
            this.delegate = delegate;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return new ConflictingWriteHandle(delegate.acquire(id));
        }

        @Override
        public DatasetShutdownOutcome shutDown(DatasetId id) {
            return delegate.shutDown(id);
        }

        @Override
        public void shutDownAll() {
            delegate.shutDownAll();
        }

        @Override
        public void delete(DatasetId id) {
            delegate.delete(id);
        }

        @Override
        public Set<DatasetId> list() {
            return delegate.list();
        }
    }

    private static final class ConflictingWriteHandle implements DatasetHandle {

        private final DatasetHandle delegate;

        ConflictingWriteHandle(DatasetHandle delegate) {
            this.delegate = delegate;
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public SparqlQuery sparqlQuery() {
            return delegate.sparqlQuery();
        }

        @Override
        public SparqlUpdate sparqlUpdate() {
            return delegate.sparqlUpdate();
        }

        @Override
        public DatasetExport datasetExport() {
            return delegate.datasetExport();
        }

        @Override
        public DatasetTransactor transactor() {
            DatasetTransactor real = delegate.transactor();
            return new DatasetTransactor() {
                @Override
                public <T> T inTransaction(Function<DatasetTx, T> fn) {
                    return real.inTransaction(tx -> fn.apply(new ConflictingWriteTx(tx)));
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Simulates the store's commit-time write-conflict signal (a {@link ConcurrencyConflictException})
     * on the write path's {@code add} call - the point at which the funnel has
     * already resolved the subject and compared the head, and is about to persist the patched
     * predicate(s). Unconditional by design: every one of {@link KognioRdfTermRepository#update}'s
     * retry attempts loses.
     */
    private static final class ConflictingWriteTx implements DatasetTx {

        private final DatasetTx delegate;

        ConflictingWriteTx(DatasetTx delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean ask(String query) {
            return delegate.ask(query);
        }

        @Override
        public boolean ask(String query, java.util.Map<String, RDFTerm> bindings) {
            return delegate.ask(query, bindings);
        }

        @Override
        public long add(IRI graph, ReadableGraph data) {
            throw new ConcurrencyConflictException("simulated overlapping-transaction conflict", null);
        }

        @Override
        public long remove(IRI graph, ReadableGraph data) {
            return delegate.remove(graph, data);
        }

        @Override
        public void clear(IRI graph) {
            delegate.clear(graph);
        }

        @Override
        public ReadableGraph snapshot(IRI graph) {
            return delegate.snapshot(graph);
        }

        @Override
        public long count(IRI graph) {
            return delegate.count(graph);
        }

        @Override
        public long count() {
            return delegate.count();
        }

        @Override
        public ReadableGraph match(IRI graph, BlankNodeOrIRI subject, IRI predicate,
                RDFTerm object) {
            return delegate.match(graph, subject, predicate, object);
        }

        @Override
        public boolean contains(IRI graph, BlankNodeOrIRI subject, IRI predicate,
                RDFTerm object) {
            return delegate.contains(graph, subject, predicate, object);
        }

        @Override
        public void update(String sparqlUpdate) {
            delegate.update(sparqlUpdate);
        }

        @Override
        public void update(String sparqlUpdate, java.util.Map<String, RDFTerm> bindings) {
            delegate.update(sparqlUpdate, bindings);
        }

        @Override
        public Stream<BindingSet> select(String query) {
            return delegate.select(query);
        }

        @Override
        public Stream<BindingSet> select(String query, java.util.Map<String, RDFTerm> bindings) {
            return delegate.select(query, bindings);
        }

        @Override
        public ReadableGraph construct(String query) {
            return delegate.construct(query);
        }

        @Override
        public ReadableGraph construct(String query, java.util.Map<String, RDFTerm> bindings) {
            return delegate.construct(query, bindings);
        }
    }

    @Test
    void findByCodeReturnsEmptyForUnknownCode() {
        assertEquals(Optional.empty(), repository.findByCode(PROJECT_A, new TermCode("TERM-99"), null));
    }

    @Test
    void projectsAreIsolated() {
        Term term = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);

        repository.create(PROJECT_A, term, null);

        assertTrue(repository.findAll(PROJECT_B, null).isEmpty());
    }

    /**
     * Gate-level regression test: {@code TermShape} targets {@code skos:Concept} directly (no
     * RDFS reasoning needed, unlike the requirements shapes), but the {@link Term} domain record
     * forbids a blank {@code prefLabel}, so no violation is reachable through
     * {@link TermRepository#create}. This test bypasses the domain and drives the gate with a
     * hand-built {@code skos:Concept} that has no {@code skos:prefLabel}, proving the shapes
     * actually load and {@code targetClass skos:Concept} fires (no silent pass).
     */
    @Test
    void gateRejectsConceptWithoutPrefLabel() {
        ShaclWriteGate gate = KognioRdfTermRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph invalidConcept = rdf.createGraph();
        invalidConcept.add(subject, VocabRdf.TYPE, rdf.createIRI(SKOS_CONCEPT));

        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(invalidConcept));
    }

    // ---- display-language fallback for multilingual prefLabel ----------------------------

    /**
     * A concept with {@code @de} and {@code @en} prefLabels, read with a German display locale,
     * surfaces the German label - step 1 of the fallback chain.
     */
    @Test
    void findByCodePicksThePrefLabelInTheRequestedLanguage() {
        TermId id = freshId();
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "Person, die bestellt.",
                "\"Kunde\"@de, \"Customer\"@en");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        Optional<Term> found = germanReader.findByCode(PROJECT_A, new TermCode("TERM-1"), null);

        assertEquals("Kunde", found.orElseThrow().prefLabel());
    }

    /**
     * A concept lacking the requested language ({@code @de}) but present in the system default
     * ({@code @en}) surfaces the English label - step 2. The term must NOT vanish (a hard
     * language filter would bind nothing).
     */
    @Test
    void findByCodeFallsBackToTheSystemDefaultLanguage() {
        TermId id = freshId();
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "Person, die bestellt.",
                "\"Customer\"@en, \"Client\"@fr");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        Optional<Term> found = germanReader.findByCode(PROJECT_A, new TermCode("TERM-1"), null);

        assertEquals("Customer", found.orElseThrow().prefLabel());
    }

    /** A plain, untagged prefLabel (today's term_add normal case) surfaces via step 3. */
    @Test
    void findByCodeFallsBackToAnUntaggedPrefLabel() {
        TermId id = freshId();
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "Person, die bestellt.", "\"Kunde\"");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        Optional<Term> found = germanReader.findByCode(PROJECT_A, new TermCode("TERM-1"), null);

        assertEquals("Kunde", found.orElseThrow().prefLabel());
    }

    /**
     * Neither the requested (de) nor the default (en) language, nothing untagged: the term is
     * still returned (never swallowed) and step 4 is deterministic - two consecutive reads yield
     * the same label ({@code "es"} sorts before {@code "fr"}).
     */
    @Test
    void findByCodeFallsBackDeterministicallyAsLastResort() {
        TermId id = freshId();
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "Person, die bestellt.",
                "\"Client\"@fr, \"Cliente\"@es");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        String first = germanReader.findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow().prefLabel();
        String second = germanReader.findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow().prefLabel();

        assertEquals("Cliente", first);
        assertEquals(first, second);
    }

    /**
     * {@code term_get}'s new {@code displayLocale} argument (issue #228): a per-call override
     * wins over the repository's own constructor-configured display language, without needing a
     * differently-configured repository instance for a one-off request in another language.
     */
    @Test
    void findByCodeDisplayLocaleArgumentOverridesTheConfiguredDefault() {
        TermId id = freshId();
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "Person, die bestellt.",
                "\"Kunde\"@de, \"Customer\"@en");
        TermRepository englishReader = readerFor(Locale.ENGLISH, Locale.ENGLISH);

        Optional<Term> found = englishReader.findByCode(PROJECT_A, new TermCode("TERM-1"), "de");

        assertEquals("Kunde", found.orElseThrow().prefLabel());
    }

    /** The same multilingual selection applies to findAll, not only findByCode. */
    @Test
    void findAllPicksThePrefLabelInTheRequestedLanguage() {
        givenMultilingualConcept(PROJECT_A, freshId(), "TERM-1", "Person, die bestellt.",
                "\"Kunde\"@de, \"Customer\"@en");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        List<Term> all = germanReader.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals("Kunde", all.get(0).prefLabel());
    }

    /**
     * {@code term_list}'s own default-language resolution (issue #274): a per-call override wins
     * over the repository's own constructor-configured display language for {@link #findAll} too,
     * the same as {@link #findByCodeDisplayLocaleArgumentOverridesTheConfiguredDefault} already
     * proves for {@link #findByCode} - {@code UbiquitousLanguageMcpTools#list} passes the calling
     * project's own configured default language as this argument, not an explicit tool argument
     * (unlike {@code term_get}'s {@code displayLocale}), but this repository method itself does
     * not distinguish the two: whatever string it is handed simply overrides the configured
     * {@code requested} tier for this one call.
     */
    @Test
    void findAllDisplayLocaleArgumentOverridesTheConfiguredDefault() {
        givenMultilingualConcept(PROJECT_A, freshId(), "TERM-1", "Person, die bestellt.",
                "\"Kunde\"@de, \"Customer\"@en");
        TermRepository englishReader = readerFor(Locale.ENGLISH, Locale.ENGLISH);

        List<Term> all = englishReader.findAll(PROJECT_A, "de");

        assertEquals(1, all.size());
        assertEquals("Kunde", all.get(0).prefLabel());
    }

    /** Distinct display locales over the same store surface distinct labels for the same concept. */
    @Test
    void findByCodeHonoursTheConfiguredDisplayLocale() {
        givenMultilingualConcept(PROJECT_A, freshId(), "TERM-1", "Person, die bestellt.",
                "\"Kunde\"@de, \"Customer\"@en");

        assertEquals("Kunde", readerFor(Locale.GERMAN, Locale.ENGLISH)
                .findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow().prefLabel());
        assertEquals("Customer", readerFor(Locale.ENGLISH, Locale.GERMAN)
                .findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow().prefLabel());
    }

    /**
     * Issue #404: {@code term_update}'s own reply used to render an <em>untouched</em>
     * {@code prefLabel} through this repository's process-wide configured {@link DisplayLocale}
     * (English by default), while {@code term_get} renders that very same field through the
     * calling project's own default language. One and the same store state therefore answered
     * {@code term_update} with the English label and a directly following {@code term_get} with
     * the German one. The reply now resolves an untouched field exactly the way
     * {@link TermRepository#findByCode} does for the same project. Since a term carries one word
     * under every tag (kogn-io/arknet#742, {@code ulshapes:Term-prefLabel-oneWord}), the field that
     * still differs by language is the definition, so the untouched field here is the definition.
     */
    @Test
    void updateRendersAnUntouchedDefinitionLikeFindByCodeDoes() {
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConceptWithDefinition(PROJECT_A, freshId(), "TERM-1", "\"Kunde\"@de, \"Kunde\"@en",
                "\"Person, die bestellt.\"@de, \"Person placing an order.\"@en");
        TermRepository englishConfigured = readerFor(Locale.ENGLISH, Locale.ENGLISH);

        Term updated = englishConfigured.update(PROJECT_A, code, "Kunde", null, "de", "de", null, null);

        assertEquals("Person, die bestellt.", updated.definition());
        assertEquals(englishConfigured.findByCode(PROJECT_A, code, "de").orElseThrow().definition(),
                updated.definition(),
                "term_update's reply must show the definition term_get shows for the same project");
    }

    /**
     * The same promise on {@link #update}'s no-op branch (every field omitted), which returns
     * without ever consulting the funnel and therefore renders <em>both</em> language-tagged
     * fields from the current state alone - the branch where a wrong display locale is visible
     * on the definition as well, not only on the label.
     */
    @Test
    void updateWithoutAnyFieldRendersBothTextFieldsLikeFindByCodeDoes() {
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConceptWithDefinition(PROJECT_A, freshId(), "TERM-1",
                "\"Kunde\"@de, \"Customer\"@en",
                "\"Person, die bestellt.\"@de, \"Person placing an order.\"@en");
        TermRepository englishConfigured = readerFor(Locale.ENGLISH, Locale.ENGLISH);

        Term unchanged = englishConfigured.update(PROJECT_A, code, null, null, null, "de", null, null);

        assertEquals("Kunde", unchanged.prefLabel());
        assertEquals("Person, die bestellt.", unchanged.definition());
    }

    /** A term repository reading the shared store under an explicit display-language preference. */
    private TermRepository readerFor(Locale requested, Locale systemDefault) {
        return KognioRdfTermRepositoryFactory.over(lifecycle, new DisplayLocale(requested, systemDefault));
    }

    /**
     * Writes a {@code skos:Concept} with one or several {@code skos:prefLabel} literals (the
     * {@code prefLabelList} is spliced verbatim into a SPARQL object list, e.g.
     * {@code "\"Kunde\"@de, \"Customer\"@en"}) - the multilingual, store-first shape term_add
     * itself never produces.
     */
    private void givenMultilingualConcept(
            ProjectId projectId, TermId id, String code, String definition, String prefLabelList) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#definition> \"" + definition + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> " + prefLabelList + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- definition: row multiplication when skos:definition is repeated -----------------

    /**
     * Store-first regression test: {@code ulshapes:TermShape} places no {@code sh:maxCount} on
     * {@code skos:definition}, so a subject with two definition literals (e.g. one per language)
     * is shape-legal even though {@code term_add} never writes more than one. Before the fix,
     * {@code assemblyFor} read {@code definition} only from the first SPARQL row bound for a
     * subject via {@code computeIfAbsent} - correct by accident whenever the two definition rows
     * happened to bind in insertion order, but silently dropping the second value regardless.
     * This pins the outward-visible contract: neither read throws, and each returns one of the two
     * values without crashing.
     */
    @Test
    void findByCodeDoesNotThrowForATermWithTwoDefinitions() {
        TermId id = freshId();
        givenTermWithTwoDefinitions(PROJECT_A, id, "TERM-1", "Erste Definition.", "Zweite Definition.");

        Term found = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow();

        assertTrue(List.of("Erste Definition.", "Zweite Definition.").contains(found.definition()));
    }

    /** Same regression as above, exercised via the batch {@link TermRepository#findAll}. */
    @Test
    void findAllDoesNotThrowForATermWithTwoDefinitions() {
        TermId id = freshId();
        givenTermWithTwoDefinitions(PROJECT_A, id, "TERM-1", "Erste Definition.", "Zweite Definition.");

        List<Term> all = repository.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertTrue(List.of("Erste Definition.", "Zweite Definition.").contains(all.get(0).definition()));
    }

    /**
     * The chosen definition is deterministic across repeated reads against the same, unchanged
     * store state - not merely "does not throw" - mirroring
     * {@link #findByCodeFallsBackDeterministicallyAsLastResort}'s determinism check for the
     * sibling {@code prefLabel} fallback.
     */
    @Test
    void findByCodePicksTheSameDefinitionOnRepeatedReads() {
        TermId id = freshId();
        givenTermWithTwoDefinitions(PROJECT_A, id, "TERM-1", "Erste Definition.", "Zweite Definition.");

        String first = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow().definition();
        String second = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow().definition();

        assertEquals(first, second);
    }

    /**
     * Reproduces issue #248 (the TERM-5 case from the arknet self-interview): a concept whose
     * {@code skos:prefLabel} AND {@code skos:definition} are both available in German and
     * English. Before the fix, {@code prefLabel} was selected via the injected
     * {@link DisplayLocale} while {@code definition} was taken as the first-seen SPARQL row
     * regardless of the requested language - so a German-locale reader could see the German
     * label next to the English definition on the very same card. This pins the fix: a reader
     * configured for one language sees BOTH fields resolved to that same language, and a
     * differently-configured reader over the identical store sees the other language for BOTH.
     */
    @Test
    void findByCodeResolvesPrefLabelAndDefinitionToTheSameLanguage() {
        TermId id = freshId();
        givenMultilingualConceptWithDefinition(PROJECT_A, id, "TERM-5",
                "\"Legal Person\"@en, \"Juristische Person\"@de",
                "\"A legal person is a non-human entity recognised in law.\"@en, "
                        + "\"Eine juristische Person ist eine im Recht anerkannte Nicht-Person.\"@de");

        Term german = readerFor(Locale.GERMAN, Locale.ENGLISH)
                .findByCode(PROJECT_A, new TermCode("TERM-5"), null).orElseThrow();
        Term english = readerFor(Locale.ENGLISH, Locale.GERMAN)
                .findByCode(PROJECT_A, new TermCode("TERM-5"), null).orElseThrow();

        assertEquals("Juristische Person", german.prefLabel());
        assertEquals("Eine juristische Person ist eine im Recht anerkannte Nicht-Person.", german.definition());
        assertEquals("Legal Person", english.prefLabel());
        assertEquals("A legal person is a non-human entity recognised in law.", english.definition());
    }

    /** Same cross-field consistency guarantee for the batch read behind {@code term_list}/the store report. */
    @Test
    void findAllResolvesPrefLabelAndDefinitionToTheSameLanguage() {
        givenMultilingualConceptWithDefinition(PROJECT_A, freshId(), "TERM-5",
                "\"Legal Person\"@en, \"Juristische Person\"@de",
                "\"A legal person is a non-human entity recognised in law.\"@en, "
                        + "\"Eine juristische Person ist eine im Recht anerkannte Nicht-Person.\"@de");

        Term german = readerFor(Locale.GERMAN, Locale.ENGLISH).findAll(PROJECT_A, null).get(0);

        assertEquals("Juristische Person", german.prefLabel());
        assertEquals("Eine juristische Person ist eine im Recht anerkannte Nicht-Person.", german.definition());
    }

    /**
     * Writes a {@code skos:Concept} with a language-tagged {@code skos:prefLabel} list AND a
     * language-tagged {@code skos:definition} list (both spliced verbatim into a SPARQL object
     * list, e.g. {@code "\"Kunde\"@de, \"Customer\"@en"}) - {@link #givenMultilingualConcept}'s
     * sibling for the cross-field consistency tests, since that helper only varies {@code
     * prefLabel} and takes a single, untagged {@code definition}.
     */
    private void givenMultilingualConceptWithDefinition(
            ProjectId projectId, TermId id, String code, String prefLabelList, String definitionList) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#definition> " + definitionList + " ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> " + prefLabelList + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Writes a {@code skos:Concept} with two {@code skos:definition} literals straight into the
     * terms graph - shape-legal ({@code ulshapes:TermShape} places no constraint on the property's
     * cardinality), but unreachable via {@code term_add}/{@code term_update}, which only ever
     * write one.
     */
    private void givenTermWithTwoDefinitions(
            ProjectId projectId, TermId id, String code, String firstDefinition, String secondDefinition) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Kunde\" ; "
                + "<http://www.w3.org/2004/02/skos/core#definition> \"" + firstDefinition + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#definition> \"" + secondDefinition + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- findByIds: batch resolution for ResolveTerms -------------------------------------

    @Test
    void findByIdsResolvesKnownIdentitiesInOneQuery() {
        Term first = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        Term second = new Term(freshId(), new TermCode("TERM-2"), "Bestellung", "def b", null);
        repository.create(PROJECT_A, first, null);
        repository.create(PROJECT_A, second, null);

        List<ResolveTerms.ResolvedTerm> resolved =
                repository.findByIds(PROJECT_A, List.of(first.id().value(), second.id().value()));

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveTerms.ResolvedTerm(first.id().value(), first.code())));
        assertTrue(resolved.contains(new ResolveTerms.ResolvedTerm(second.id().value(), second.code())));
    }

    /** An id absent from the project is simply absent from the result, never an error. */
    @Test
    void findByIdsSilentlyOmitsUnknownIdentities() {
        Term known = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(PROJECT_A, known, null);
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/does-not-exist");

        List<ResolveTerms.ResolvedTerm> resolved =
                repository.findByIds(PROJECT_A, List.of(known.id().value(), unknown));

        assertEquals(List.of(new ResolveTerms.ResolvedTerm(known.id().value(), known.code())), resolved);
    }

    @Test
    void findByIdsWithEmptyIdsReturnsAnEmptyListWithoutQuerying() {
        assertEquals(List.of(), repository.findByIds(PROJECT_A, List.of()));
    }

    @Test
    void findByIdsIsScopedPerProject() {
        Term inProjectA = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(PROJECT_A, inProjectA, null);

        assertEquals(List.of(), repository.findByIds(PROJECT_B, List.of(inProjectA.id().value())));
    }

    /**
     * Store-first regression test:
     * {@code ulshapes:TermShape} places no constraint at all on {@code dcterms:identifier} (no
     * {@code sh:minCount}, no {@code sh:maxCount}), so a subject with two identifier triples is
     * shape-legal even though {@code term_add} never writes more than one. {@code findByIds}'
     * mandatory {@code identifier} join must not multiply such a subject into two
     * {@link ResolveTerms.ResolvedTerm}s carrying the same id - a caller keying its own results
     * by identity (as {@code RequirementMcpTools#resolveTermsFor} does) would otherwise throw on
     * the duplicate key. (This vector used to run through {@code skos:prefLabel}, which
     * {@code findByIds} no longer joins at all.)
     */
    @Test
    void findByIdsReturnsExactlyOneResolvedTermForASubjectWithSeveralIdentifiers() {
        TermId id = freshId();
        givenTermWithTwoIdentifiers(PROJECT_A, id, "TERM-1", "TERM-1-ALT");

        List<ResolveTerms.ResolvedTerm> resolved = repository.findByIds(PROJECT_A, List.of(id.value()));

        assertEquals(1, resolved.size());
        assertEquals(id.value(), resolved.get(0).id());
    }

    /**
     * {@code findByIds} joins only {@code identifier}, not {@code prefLabel}/
     * {@code definition} - fields the {@link ResolveTerms.ResolvedTerm} projection never carries.
     * A store-first term that has an identity and a code but happens to miss a
     * {@code skos:prefLabel} (shape-invalid for {@link #findByCode}/{@link #findAll}, which still
     * require one) is therefore resolvable here, where the earlier, wider join used to exclude it.
     */
    @Test
    void findByIdsResolvesATermWithoutAnyPrefLabel() {
        TermId id = freshId();
        givenTermWithoutPrefLabel(PROJECT_A, id, "TERM-1");

        List<ResolveTerms.ResolvedTerm> resolved = repository.findByIds(PROJECT_A, List.of(id.value()));

        assertEquals(List.of(new ResolveTerms.ResolvedTerm(id.value(), new TermCode("TERM-1"))), resolved);
    }

    /**
     * Writes a {@code skos:Concept} straight into the terms graph with two
     * {@code dcterms:identifier} triples - shape-legal ({@code ulshapes:TermShape} places no
     * constraint on the property at all), but unreachable via {@code term_add}.
     */
    private void givenTermWithTwoIdentifiers(ProjectId projectId, TermId id, String first, String second) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + first + "\" ; "
                + "<http://purl.org/dc/terms/identifier> \"" + second + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Kunde\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /** Writes a {@code skos:Concept} without any {@code skos:prefLabel} - store-first only. */
    private void givenTermWithoutPrefLabel(ProjectId projectId, TermId id, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#definition> \"Eine Person, die bestellt.\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- findAllCodes: the raw code read behind the code assignment ------------------------

    /**
     * What {@link TermRepository#findAllCodes} exists for (kogn-io/arknet#360), pinned against the
     * real store: the query joins the {@code skos:Concept} type and {@code dcterms:identifier} and
     * nothing else, so a code stays taken even by a subject {@link TermRepository#findAll} cannot
     * materialise at all. Deliberately the barest such concept there is - neither
     * {@code skos:prefLabel} nor {@code skos:definition}, the two literals the listing read joins as
     * mandatory - so any further mandatory join added to this query later (the tempting "a code
     * alone is not a term") fails here, instead of silently letting {@code term_add} mint
     * {@code TERM-2} a second time.
     *
     * <p>The exact count also pins the type join's second duty: the glossary's own
     * {@code skos:ConceptScheme} subject shares this named graph and must not turn up among the
     * codes.</p>
     */
    @Test
    void findAllCodesKeepsTheCodeOfASubjectFindAllCannotMaterialiseAtAll() {
        repository.create(PROJECT_A,
                new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null), null);
        givenBareConcept(PROJECT_A, freshId(), "TERM-2");

        List<TermCode> codes = repository.findAllCodes(PROJECT_A);

        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertTrue(repository.findByCode(PROJECT_A, new TermCode("TERM-2"), null).isEmpty());
        assertEquals(2, codes.size());
        assertTrue(codes.containsAll(List.of(new TermCode("TERM-1"), new TermCode("TERM-2"))));
    }

    /**
     * The same guarantee against a blank-node holder (kogn-io/arknet#360). {@code skos:Concept}
     * says nothing about node kind, and neither does the counting query: no
     * {@code FILTER(isIRI(?s))}, unlike every read around it. It has to stay absent, because the
     * writer is just as node-kind-blind - {@code VersionedWriteFunnel#create} asks
     * {@code tx.contains(graph, null, dcterms:identifier, code)} with a wildcard subject and would
     * reject a {@code term_add} minting {@code TERM-2}. A counter that skipped the blank node would
     * keep proposing that very number, retry after retry, and {@code term_add} would stay dead for
     * the project. Re-introduce the filter and this test goes red.
     */
    @Test
    void findAllCodesCountsACodeHeldByABlankNodeSubject() {
        repository.create(PROJECT_A,
                new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null), null);
        givenBareBlankNodeConcept(PROJECT_A, "TERM-2");

        List<TermCode> codes = repository.findAllCodes(PROJECT_A);

        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertTrue(codes.contains(new TermCode("TERM-2")), codes.toString());
    }

    /** Codes are read per project, never across projects - same scoping {@code findAll} promises. */
    @Test
    void findAllCodesIsScopedToItsProject() {
        repository.create(PROJECT_A,
                new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null), null);

        assertEquals(List.of(), repository.findAllCodes(PROJECT_B));
    }

    /**
     * Writes the barest {@code skos:Concept} the store accepts - a type triple and a
     * {@code dcterms:identifier}, no {@code skos:prefLabel}, no {@code skos:definition}. Written
     * raw, because no in-port can produce it: {@code term_add} always writes both literals, and
     * {@code ulshapes:Term-prefLabel}'s {@code sh:minCount 1} would reject this shape at the write
     * gate. Store-first data that arrived some other way is exactly the case
     * {@code findAllCodes} has to survive.
     */
    private void givenBareConcept(ProjectId projectId, TermId id, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * The same bare concept, only anonymous: {@code []} mints a fresh blank node, the node kind
     * {@code ulshapes:TermShape} never rules out and {@code term_add} never produces. Written raw
     * for the same reason as its IRI sibling - only a store-first write can put a code on
     * a subject shaped like this.
     */
    private void givenBareBlankNodeConcept(ProjectId projectId, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { "
                + "[] a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- blank-node subject guard ----------------------------------------------------------

    /**
     * Store-first regression test: {@code ulshapes:TermShape} carries no
     * {@code sh:nodeKind sh:IRI} constraint on the subject, so a blank-node concept is
     * SHACL-legal even though {@code term_add} always mints an IRI subject. Before the fix,
     * the unguarded {@code (IRI) row.getValue("s")} cast in {@code iriOf} threw a
     * {@code ClassCastException} that crashed {@code findAll} for the whole project, not just
     * the offending term.
     */
    @Test
    void findAllSkipsATermWithABlankNodeSubjectInsteadOfCrashing() {
        Term validTerm = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(PROJECT_A, validTerm, null);
        givenBlankNodeTerm(PROJECT_A, "TERM-9", "Blinder Fleck", "def blank");

        List<Term> all = repository.findAll(PROJECT_A, null);

        assertEquals(List.of(validTerm), all);
    }

    /** Same guard, exercised through {@code findByCode} instead of {@code findAll}. */
    @Test
    void findByCodeReturnsEmptyForATermWithABlankNodeSubjectInsteadOfCrashing() {
        givenBlankNodeTerm(PROJECT_A, "TERM-9", "Blinder Fleck", "def blank");

        Optional<Term> found = repository.findByCode(PROJECT_A, new TermCode("TERM-9"), null);

        assertEquals(Optional.empty(), found);
    }

    /**
     * Writes a {@code skos:Concept} with a blank-node subject straight into the terms graph -
     * {@code ulshapes:TermShape} places no {@code sh:nodeKind sh:IRI} on the subject, so this is
     * SHACL-legal store-first, even though {@code term_add} always mints an IRI
     * subject via {@link de.hauschel.arknet.kernel.ResourceIdFactory}.
     */
    private void givenBlankNodeTerm(ProjectId projectId, String code, String prefLabel, String definition) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/content> { "
                + "[] a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"" + prefLabel + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#definition> \"" + definition + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- draft and token: every write changes the draft and publishes it ------------------

    /** {@code create} publishes at once: the term carries a token and exactly one version. */
    @Test
    void createPublishesExactlyOneVersion() {
        TermId id = freshId();
        repository.create(PROJECT_A, new Term(id, new TermCode("TERM-1"), "Gutschrift",
                "Eine dem Kundenkonto gutgeschriebene Erstattung.", null), null);

        assertTrue(tokenOf(id).isPresent(), "create must leave a draft");
        assertEquals(PublishState.PUBLISHED_UNCHANGED, probe().publishState(DATASET_A, id.value().value()));
        assertEquals(1, probe().history(DATASET_A, id.value().value()).size(), "a write is a publish");
    }

    /** The patch-{@code update} writes the draft through the compare-and-set: the token moves. */
    @Test
    void updateMovesTheDraftsToken() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Erste Definition.", null), null);
        Optional<RevisionToken> tokenAfterCreate = tokenOf(id);

        repository.update(PROJECT_A, code, null, "Zweite Definition.", null, null, null, null);

        assertNotEquals(tokenAfterCreate, tokenOf(id), "the update must have written the draft");
    }

    /**
     * Every field {@code update} takes is {@code required = false} on the {@code term_update} MCP
     * tool, so a caller can legally invoke it with nothing but the identifying {@code code}. Such a
     * call must be a true no-op - no write, no token movement - exactly like the symmetric guard
     * in {@code RequirementService#updateWithOptimisticRetry}: a write for an empty patch would
     * hand a concurrent compare-and-set writer a spurious conflict.
     */
    @Test
    void updateWithNoFieldsIsANoOpThatLeavesTheDraftUntouched() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A,
                new Term(id, code, "Gutschrift", "Erste Definition.", null), null);
        Optional<RevisionToken> tokenAfterCreate = tokenOf(id);

        Term result = repository.update(PROJECT_A, code, null, null, null, null, null, null);

        assertEquals(tokenAfterCreate, tokenOf(id), "a field-less update must not write the draft");
        assertEquals(new Term(id, code, "Gutschrift", "Erste Definition.", null), result);
    }

    /**
     * The retry half of the compare-and-set guarantee: a concurrent writer that advances the
     * term's shared head between this caller's read and its write must cost the caller nothing.
     * The losing attempt is retried against a fresh read, so both changes survive - the caller's
     * own patched predicate and the other writer's change to a <em>different</em> predicate,
     * which the earlier predicate-scoped conflict detection would not even have noticed.
     *
     * <p>The interleaving is pinned by a decorator that lets exactly one other write commit right
     * before the funnel's compare-and-set transaction opens, rather than by real threads, which
     * would make this flaky. The proof that the retry actually re-read: {@code update} builds its
     * return value from the state it read <em>before</em> the write, so a caller that never
     * re-read would return the pre-race definition and disagree with the store.</p>
     */
    @Test
    void updateRetriesAndKeepsBothChangesWhenAConcurrentWriterAdvancedTheHead() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Erste Definition.", null), null);

        AtomicBoolean pending = new AtomicBoolean(true);
        TermRepository racing = KognioRdfTermRepositoryFactory.over(
                new HeadAdvancingLifecycle(lifecycle, () -> {
                    if (pending.compareAndSet(true, false)) {
                        repository.update(PROJECT_A, code, null, "Definition des Konkurrenten.", null, null, null, null);
                    }
                }));

        Term result = racing.update(PROJECT_A, code, "Gutschriftsbeleg", null, null, null, null, null);

        assertFalse(pending.get(), "the concurrent writer must have committed - nothing was raced otherwise");
        Term expected = new Term(id, code, "Gutschriftsbeleg", "Definition des Konkurrenten.", null);
        assertEquals(expected, result, "the retry must return the state it re-read, not its stale first read");
        assertEquals(Optional.of(expected), repository.findByCode(PROJECT_A, code, null),
                "both writers' changes must survive - neither patch is silently lost");
    }

    /**
     * Wraps a real {@link DatasetLifecycle} and runs {@code beforeTransaction} right before every
     * write transaction opens - the exact point at which a concurrent writer's commit turns this
     * caller's already-taken read (state plus {@code RevisionToken}) stale. The one-shot guard
     * lives in the {@link Runnable} the test supplies, so a retried attempt runs unimpeded.
     */
    private static final class HeadAdvancingLifecycle implements DatasetLifecycle {

        private final DatasetLifecycle delegate;
        private final Runnable beforeTransaction;

        HeadAdvancingLifecycle(DatasetLifecycle delegate, Runnable beforeTransaction) {
            this.delegate = delegate;
            this.beforeTransaction = beforeTransaction;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return new HeadAdvancingHandle(delegate.acquire(id), beforeTransaction);
        }

        @Override
        public DatasetShutdownOutcome shutDown(DatasetId id) {
            return delegate.shutDown(id);
        }

        @Override
        public void shutDownAll() {
            delegate.shutDownAll();
        }

        @Override
        public void delete(DatasetId id) {
            delegate.delete(id);
        }

        @Override
        public Set<DatasetId> list() {
            return delegate.list();
        }
    }

    private static final class HeadAdvancingHandle implements DatasetHandle {

        private final DatasetHandle delegate;
        private final Runnable beforeTransaction;

        HeadAdvancingHandle(DatasetHandle delegate, Runnable beforeTransaction) {
            this.delegate = delegate;
            this.beforeTransaction = beforeTransaction;
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public SparqlQuery sparqlQuery() {
            return delegate.sparqlQuery();
        }

        @Override
        public SparqlUpdate sparqlUpdate() {
            return delegate.sparqlUpdate();
        }

        @Override
        public DatasetExport datasetExport() {
            return delegate.datasetExport();
        }

        @Override
        public DatasetTransactor transactor() {
            DatasetTransactor real = delegate.transactor();
            return new DatasetTransactor() {
                @Override
                public <T> T inTransaction(Function<DatasetTx, T> fn) {
                    beforeTransaction.run();
                    return real.inTransaction(fn);
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Regression test: {@code attemptUpdate} used to read the
     * term's assembly and its {@code RevisionToken} via two <em>separate</em>
     * {@code SparqlQuery#select} calls. That port's contract only guarantees that each individual
     * call is a self-contained read against the store's current committed state - nothing ties
     * two separate calls to the same snapshot. A concurrent writer's commit landing exactly
     * between those two calls left the first call's assembly stale (pre-commit) paired with the
     * second call's head, which was already fresh (post-commit): the funnel's head comparison
     * then wrongly matched against a state that was no longer current, and the caller returned a
     * {@code prefLabel} the store no longer held - without ever entering the retry loop.
     *
     * <p>{@link #updateRetriesAndKeepsBothChangesWhenAConcurrentWriterAdvancedTheHead()} does not
     * catch this: its decorator interleaves right before the write transaction opens, which is
     * <em>after</em> both (pre-fix) reads have already completed. This test instead interleaves
     * right after a {@code select} call returns - the exact window the review named - by injecting
     * the concurrent commit between the state read and the head read (pre-fix) respectively right
     * after the single combined read (post-fix).</p>
     *
     * <p>Expected outcome, fixed: the concurrent writer's {@code prefLabel} change is never lost
     * and this caller's own {@code definition} patch is retried against the now-current state, so
     * the returned {@link Term} carries the concurrent writer's fresh {@code prefLabel} together
     * with this caller's own patched {@code definition} - never the stale {@code prefLabel} this
     * caller's own read saw before the race.</p>
     */
    @Test
    void updateReflectsAConcurrentWriteThatCommitsBetweenTheStateAndHeadReads() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Alt", "Erste Definition.", null), null);

        AtomicBoolean pending = new AtomicBoolean(true);
        TermRepository racing = KognioRdfTermRepositoryFactory.over(
                new SelectRacingLifecycle(lifecycle, () -> {
                    if (pending.compareAndSet(true, false)) {
                        // On a thread of its own: the racer may be inside its read transaction.
                        runOnOtherThread(() -> repository.update(PROJECT_A, code, "Neu", null, null, null, null,
                                null));
                    }
                }));

        Term result = racing.update(PROJECT_A, code, null, "Ueberarbeitete Definition.", null, null, null, null);

        assertFalse(pending.get(), "the concurrent writer must have committed - nothing was raced otherwise");
        Term expected = new Term(id, code, "Neu", "Ueberarbeitete Definition.", null);
        assertEquals(expected, result,
                "the returned term must carry the concurrent writer's fresh prefLabel, not a stale read");
        assertEquals(Optional.of(expected), repository.findByCode(PROJECT_A, code, null),
                "both writers' changes must survive - neither patch is silently lost");
    }

    private static void runOnOtherThread(Runnable work) {
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                failure.set(e);
            }
        }, "concurrent-writer");
        other.start();
        try {
            other.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    /**
     * Wraps a real {@link DatasetLifecycle} and runs {@code afterSelect} right after every
     * {@code SparqlQuery#select} call's rows have been materialised - the exact point at which an
     * earlier read-then-read implementation is between its two separate reads. Unlike
     * {@link HeadAdvancingLifecycle} (which interleaves once the write transaction opens, i.e.
     * after both reads), this fires while a caller's read is potentially still in progress. The
     * one-shot guard lives in the {@link Runnable} the test supplies, exactly like
     * {@link HeadAdvancingLifecycle} - a retried attempt's own select calls run unimpeded.
     */
    private static final class SelectRacingLifecycle implements DatasetLifecycle {

        private final DatasetLifecycle delegate;
        private final Runnable afterSelect;

        SelectRacingLifecycle(DatasetLifecycle delegate, Runnable afterSelect) {
            this.delegate = delegate;
            this.afterSelect = afterSelect;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return new SelectRacingHandle(delegate.acquire(id), afterSelect);
        }

        @Override
        public DatasetShutdownOutcome shutDown(DatasetId id) {
            return delegate.shutDown(id);
        }

        @Override
        public void shutDownAll() {
            delegate.shutDownAll();
        }

        @Override
        public void delete(DatasetId id) {
            delegate.delete(id);
        }

        @Override
        public Set<DatasetId> list() {
            return delegate.list();
        }
    }

    private static final class SelectRacingHandle implements DatasetHandle {

        private final DatasetHandle delegate;
        private final Runnable afterSelect;

        SelectRacingHandle(DatasetHandle delegate, Runnable afterSelect) {
            this.delegate = delegate;
            this.afterSelect = afterSelect;
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public SparqlQuery sparqlQuery() {
            SparqlQuery real = delegate.sparqlQuery();
            return new SparqlQuery() {
                @Override
                public Stream<BindingSet> select(String sparql) {
                    // Force materialisation before signalling - the port contract already
                    // promises the returned stream is fully materialised (no store resources
                    // held open), so this changes no observable behaviour; it only fixes the
                    // point at which afterSelect must run relative to this call's own result.
                    List<BindingSet> rows = real.select(sparql).toList();
                    afterSelect.run();
                    return rows.stream();
                }

                @Override
                public Stream<BindingSet> select(String sparql, java.util.Map<String, RDFTerm> bindings) {
                    List<BindingSet> rows = real.select(sparql, bindings).toList();
                    afterSelect.run();
                    return rows.stream();
                }

                @Override
                public ReadableGraph construct(String sparql) {
                    return real.construct(sparql);
                }

                @Override
                public ReadableGraph construct(String sparql, java.util.Map<String, RDFTerm> bindings) {
                    return real.construct(sparql, bindings);
                }

                @Override
                public boolean ask(String sparql) {
                    return real.ask(sparql);
                }

                @Override
                public boolean ask(String sparql, java.util.Map<String, RDFTerm> bindings) {
                    return real.ask(sparql, bindings);
                }
            };
        }

        @Override
        public SparqlUpdate sparqlUpdate() {
            return delegate.sparqlUpdate();
        }

        @Override
        public DatasetExport datasetExport() {
            return delegate.datasetExport();
        }

        /**
         * Runs {@code afterSelect} once more at the end of every transaction's work, before it
         * commits - a read transaction then holds its state and token while the concurrent writer
         * commits underneath it.
         */
        @Override
        public DatasetTransactor transactor() {
            DatasetTransactor real = delegate.transactor();
            return new DatasetTransactor() {
                @Override
                public <T> T inTransaction(java.util.function.Function<DatasetTx, T> work) {
                    return real.inTransaction(tx -> {
                        T result = work.apply(tx);
                        afterSelect.run();
                        return result;
                    });
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    // ---- delete (issue #335) ----------------------------------------------------------------

    @Test
    void deleteRemovesTheTermAndItsTriples() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Eine Erstattung.", null), null);

        repository.delete(PROJECT_A, code);

        assertTrue(repository.findByCode(PROJECT_A, code, null).isEmpty());
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask("ASK { GRAPH <" + TERMS_GRAPH + "> { <"
                    + id.value().value() + "> ?p ?o } }"), "no triple of the deleted term may remain");
        }
    }

    @Test
    void deleteRejectsAnUnknownCode() {
        assertThrows(TermNotFoundException.class, () -> repository.delete(PROJECT_A, new TermCode("TERM-99")));
    }

    /** A delete removes the draft and records a tombstone in the library history. */
    @Test
    void deleteRecordsATombstone() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Eine Erstattung.", null), null);

        repository.delete(PROJECT_A, code);

        assertTrue(tokenOf(id).isEmpty(), "the draft must be gone");
        assertEquals(PublishState.DELETED, probe().publishState(DATASET_A, id.value().value()));
        List<EntryKind> kinds = probe().history(DATASET_A, id.value().value()).stream().map(HistoryEntry::kind).toList();
        assertEquals(EntryKind.TOMBSTONE, kinds.getLast(), "the delete must record a tombstone");
        assertTrue(kinds.subList(0, kinds.size() - 1).stream().allMatch(EntryKind.PUBLISH::equals)
                && kinds.size() > 1, "every write before the delete published a version");
    }

    /**
     * {@link TermReferencedException} blocks the delete while a requirement's
     * {@code arkreq:usesTerm} still points at the term - {@code rejectIfReferenced} searches across
     * the content graph, where every bounded context's edges live (ADR-63).
     */
    @Test
    void deleteRejectsATermStillReferencedViaUsesTerm() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Eine Erstattung.", null), null);
        String reference = "INSERT DATA { GRAPH <" + TERMS_GRAPH + "> { <https://example.org/req/1> <"
                + ArkreqVocabulary.USES_TERM + "> <" + id.value().value() + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(reference);
                return null;
            });
        }

        assertThrows(TermReferencedException.class, () -> repository.delete(PROJECT_A, code));
        assertTrue(repository.findByCode(PROJECT_A, code, null).isPresent(),
                "a rejected delete must leave the term untouched");
    }

    /**
     * The ADR counterpart of the test above (kogn-io/arknet#399). An architecture decision points
     * at a glossary term through {@code arkarch:usesTerm} - its own property in its own namespace,
     * not the shared {@code arkreq:usesTerm} the requirements BC writes (kogn-io/arknet#393) - and
     * that edge was missing from {@code REFERENCING_PREDICATES}, so the delete went through and
     * left the decision pointing into nothing.
     *
     * <p>The assertion on the reported shorthand is part of the fix, not decoration: the two
     * properties share the local name {@code usesTerm}, and only the namespace prefix tells the
     * caller that {@code adr_update}, not {@code req_update}, is the tool that drops this
     * edge.</p>
     */
    @Test
    void deleteRejectsATermStillReferencedViaAnArchitectureDecisionsUsesTerm() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Eine Erstattung.", null), null);
        String reference = "INSERT DATA { GRAPH <" + TERMS_GRAPH + "> { <https://example.org/adr/1> <"
                + ArkarchVocabulary.USES_TERM + "> <" + id.value().value() + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(reference);
                return null;
            });
        }

        TermReferencedException ex = assertThrows(TermReferencedException.class,
                () -> repository.delete(PROJECT_A, code));
        assertEquals(List.of("arkarch:usesTerm"), ex.referencingPredicates(),
                "the rejection must name the ADR's own property, distinguishable from arkreq:usesTerm");
        assertTrue(repository.findByCode(PROJECT_A, code, null).isPresent(),
                "a rejected delete must leave the term untouched");
    }

    /**
     * The same-graph counterpart: a term still named as another term's {@code skos:broader}
     * target must block the delete too, even though the referencing edge lives in this adapter's
     * own {@link #TERMS_GRAPH} rather than a sibling BC's.
     */
    @Test
    void deleteRejectsATermStillReferencedAsABroaderTarget() {
        TermId broaderId = freshId();
        TermCode broaderCode = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(broaderId, broaderCode, "Zahlungsverkehr", "Oberbegriff.", null), null);
        repository.create(PROJECT_A, new Term(freshId(), new TermCode("TERM-2"), "Gutschrift",
                "Eine Erstattung.", null), null);
        repository.update(PROJECT_A, new TermCode("TERM-2"), null, null, null, null,
                Optional.of(broaderCode), null);

        assertThrows(TermReferencedException.class, () -> repository.delete(PROJECT_A, broaderCode));
        assertTrue(repository.findByCode(PROJECT_A, broaderCode, null).isPresent(),
                "a rejected delete must leave the broader term untouched");
    }

    @Test
    void projectsAreIsolatedForDelete() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Eine Erstattung.", null), null);

        assertThrows(TermNotFoundException.class, () -> repository.delete(PROJECT_B, code));
        assertTrue(repository.findByCode(PROJECT_A, code, null).isPresent(),
                "a delete in another project must not touch this project's term");
    }

    /**
     * The business code lives on the model triple the delete removes, so the funnel keeps it on
     * the tombstone (issue #350) - what keeps {@code TERM-1} from naming a second term later.
     */
    @Test
    void deleteKeepsTheBusinessCodeOnTheTombstone() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Eine Erstattung.", null), null);

        repository.delete(PROJECT_A, code);

        assertEquals(List.of(new TermCode("TERM-1")), repository.findRetainedCodes(PROJECT_A));
    }

    /** A living term's revision carries no retained code - only a tombstoned one does. */
    @Test
    void findRetainedCodesIgnoresLivingTermsAndOtherProjects() {
        repository.create(PROJECT_A, new Term(freshId(), new TermCode("TERM-1"), "Gutschrift",
                "Eine Erstattung.", null), null);
        TermCode deletedCode = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), deletedCode, "Bestellung",
                "Eine Anfrage.", null), null);

        repository.delete(PROJECT_A, deletedCode);

        assertEquals(List.of(deletedCode), repository.findRetainedCodes(PROJECT_A));
        assertEquals(List.of(), repository.findRetainedCodes(PROJECT_B));
    }

    /**
     * The retained-codes graph is shared by every bounded context, so a neighbour retaining its own
     * code the same way must not be counted as a term code - the deleted resource's type triple is
     * gone, leaving the code prefix as the only discriminator.
     */
    @Test
    void findRetainedCodesIgnoresAnotherContextsRetainedCode() {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <" + VersionedWriteFunnel.RETAINED_CODES_GRAPH + "> { "
                        + "<urn:uuid:foreign-tombstone> <http://purl.org/dc/terms/identifier> \"ADR-9\" } }");
                return null;
            });
        }

        assertEquals(List.of(), repository.findRetainedCodes(PROJECT_A));
    }

    private Optional<RevisionToken> tokenOf(TermId id) {
        return probe().token(DATASET_A, id.value().value());
    }

    private VersioningProbe probe() {
        return new VersioningProbe(lifecycle, ContentGraph.GRAPH_IRI);
    }
}
