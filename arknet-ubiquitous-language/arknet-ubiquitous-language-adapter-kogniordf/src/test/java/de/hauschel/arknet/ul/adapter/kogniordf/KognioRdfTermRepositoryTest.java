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
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermConcurrentlyModifiedException;
import de.hauschel.arknet.ul.domain.TermCycleException;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * Integration test for {@link KognioRdfTermRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 */
class KognioRdfTermRepositoryTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String SKOS_CONCEPT = "http://www.w3.org/2004/02/skos/core#Concept";

    /**
     * The store's on-disk home, managed by JUnit rather than {@code Files.createTempDirectory},
     * which left its directories behind - harmless while the store is {@code IN_MEMORY}, but
     * still an inode left in {@code /tmp} for every test run. Deleted after {@link #tearDown()}
     * has shut the store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private TermRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = KognioRdfTermRepositoryFactory.over(datasetLifecycle);
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
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "Erste Definition.", "\"Kunde\"@de, \"Customer\"@en");

        repository.update(PROJECT_A, code, null, "Ueberarbeitete Definition.", null, null, null, null);

        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"),
                "the German prefLabel variant must survive an update that never touches prefLabel");
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Customer", "en"),
                "the English prefLabel variant must survive an update that never touches prefLabel");
        assertEquals("Ueberarbeitete Definition.", repository.findByCode(PROJECT_A, code, null).orElseThrow().definition());
    }

    /**
     * Same bug 1 regression, exercised the way the PR review actually found it: an update that
     * only touches the Actor facette (never {@code prefLabel} or {@code definition} at all) must
     * not disturb either of them, including a store-first multi-valued {@code prefLabel}.
     */
    @Test
    void updateChangingOnlyActorFacetPreservesMultiValuedPrefLabelAndDefinition() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "Eine Definition.", "\"Kunde\"@de, \"Customer\"@en");

        Term result = repository.update(PROJECT_A, code, null, null, new ActorFacet(ActorKind.HUMAN, "Besteller"), null, null, null);

        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Customer", "en"));
        assertEquals("Eine Definition.", result.definition());
        assertEquals(new ActorFacet(ActorKind.HUMAN, "Besteller"), result.actorFacet());
    }

    /**
     * Bug 3 (data loss, found reviewing the same PR): correcting only {@code actorKind} (e.g. a
     * miscategorised {@code HUMAN} actor that should have been {@code SYSTEM}) without restating
     * {@code actorRole} must not wipe the role already on record - a {@code null} role means
     * "unchanged", not "clear", exactly like every other field {@link #update} takes.
     */
    @Test
    void updateChangingOnlyActorKindPreservesTheExistingActorRole() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A,
                new Term(id, code, "Kunde", "def a", new ActorFacet(ActorKind.HUMAN, "Besteller")), null);

        Term result = repository.update(PROJECT_A, code, null, null, new ActorFacet(ActorKind.SYSTEM, null), null, null, null);

        assertEquals(new ActorFacet(ActorKind.SYSTEM, "Besteller"), result.actorFacet());
        assertEquals(new ActorFacet(ActorKind.SYSTEM, "Besteller"),
                repository.findByCode(PROJECT_A, code, null).orElseThrow().actorFacet());
    }

    /**
     * Same class of bug as {@link #updateChangingOnlyActorKindPreservesTheExistingActorRole}, from
     * the other direction: correcting {@code actorKind} away from {@code LEGAL} must remove the
     * stale {@code arkproc:LegalActor} type triple, not leave the subject typed as two actor kinds
     * at once.
     */
    @Test
    void updateChangingActorKindAwayFromLegalRemovesTheStaleType() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A,
                new Term(id, code, "Kunde GmbH", "def a", new ActorFacet(ActorKind.LEGAL, "Besteller")), null);

        repository.update(PROJECT_A, code, null, null, new ActorFacet(ActorKind.HUMAN, "Besteller"), null, null, null);

        assertFalse(subjectHasType(PROJECT_A, id, "https://w3id.org/arknet/process#LegalActor"));
        assertTrue(subjectHasType(PROJECT_A, id, "https://w3id.org/arknet/process#HumanActor"));
    }

    /**
     * Bug 3 (the fix this PR ships, issue #228): {@code update()}'s delete used to be a blind
     * {@code DELETE WHERE} over the whole predicate, regardless of language - every write that
     * corrected a term without stating a {@code language} (the untagged case, still the common
     * one) therefore deleted every language-tagged variant a store-first term legally carried,
     * even though the caller supplied - and meant to write - only a plain, untagged value. The
     * delete is now scoped to the same tag as what is being written (untagged here, since no
     * {@code language} was given), so a variant this call was never asked to touch survives.
     */
    @Test
    void updateGivenAPrefLabelWithoutLanguageOnlyReplacesTheUntaggedVariant() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"@de, \"Customer\"@en");

        repository.update(PROJECT_A, code, "Bestandskunde", null, null, null, null, null);

        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"),
                "an untagged correction must not delete an unrelated language-tagged variant");
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Customer", "en"),
                "an untagged correction must not delete an unrelated language-tagged variant");
        assertTrue(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Bestandskunde"));
    }

    /**
     * A second, later untagged correction replaces only the first untagged correction's own
     * value - it does not accumulate untagged duplicates, and still leaves every language-tagged
     * variant alone.
     */
    @Test
    void updateGivenAPrefLabelWithoutLanguageTwiceReplacesTheUntaggedVariantEachTime() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"@de, \"Customer\"@en");

        repository.update(PROJECT_A, code, "Bestandskunde", null, null, null, null, null);
        repository.update(PROJECT_A, code, "Stammkunde", null, null, null, null, null);

        assertFalse(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Bestandskunde"));
        assertTrue(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Stammkunde"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Customer", "en"));
    }

    /**
     * Replacing {@code prefLabel} with an explicit {@code language} argument only replaces the
     * variant carrying that same tag - every other language variant, including an untagged one,
     * survives untouched. This is the counterpart write path to {@code term_add}'s own
     * {@code language} argument (see {@link #createWritesALanguageTaggedPrefLabelAndDefinition()}).
     */
    @Test
    void updateGivenAPrefLabelWithLanguageReplacesOnlyThatLanguageVariant() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"@de, \"Customer\"@en");

        Term result = repository.update(PROJECT_A, code, "Stammkunde", null, null, "de", null, null);

        assertEquals("Stammkunde", result.prefLabel());
        assertFalse(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Kunde", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Stammkunde", "de"));
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Customer", "en"));
    }

    /**
     * Issue #258, decision 3: an {@code update} that writes {@code prefLabel} under the tag equal
     * to {@code defaultLanguage} sweeps away a stale untagged sibling of the same predicate
     * instead of preserving it as a spurious "other" language variant.
     */
    @Test
    void updateSweepsAnUntaggedPrefLabelWhenTheWrittenTagEqualsTheProjectDefault() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"");

        Term result = repository.update(PROJECT_A, code, "Bestandskunde", null, null, "de", "de", null);

        assertEquals("Bestandskunde", result.prefLabel());
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Bestandskunde", "de"));
        assertFalse(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Kunde"));
    }

    /**
     * Regression guard for the same sweep (issue #258): writing {@code prefLabel} under an
     * <em>explicit</em>, non-default language must leave an existing untagged variant alone - the
     * sweep only ever fires when the written tag equals the project's default.
     */
    @Test
    void updateKeepsAnUntaggedPrefLabelWhenTheWrittenTagDiffersFromTheProjectDefault() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        givenMultilingualConcept(PROJECT_A, id, "TERM-1", "def a", "\"Kunde\"");

        Term result = repository.update(PROJECT_A, code, "Client", null, null, "fr", "de", null);

        assertEquals("Client", result.prefLabel());
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Client", "fr"));
        assertTrue(subjectHasUntaggedPrefLabel(PROJECT_A, id, "Kunde"));
    }

    /**
     * P0 regression (PR #230 review comment): {@code deleteTriplesOfLanguage}'s
     * {@code FILTER(lang(?o) = "tag")} compared the raw, unnormalized BCP-47 tag
     * case-sensitively - a later correction spelling the same language with a different case
     * (e.g. {@code "DE"} where the term was originally written with {@code "de"}) missed the
     * existing literal and inserted a second, differently-cased one instead of replacing it,
     * defeating {@code sh:uniqueLang}. Fixed by canonicalizing every tag through
     * {@code canonicalLanguageTag} before both writing a literal and building the delete filter,
     * so the two calls always agree on one case.
     */
    @Test
    void updateWithADifferentlyCasedLanguageTagReplacesTheSameStoredVariantInsteadOfDuplicatingIt() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        Term term = new Term(id, code, "Kunde", "Person, die eine Bestellung aufgibt.", null);
        repository.create(PROJECT_A, term, "de");

        Term result = repository.update(PROJECT_A, code, "Stammkunde", null, null, "DE", null, null);

        assertEquals("Stammkunde", result.prefLabel());
        assertTrue(subjectHasLanguageTaggedPrefLabel(PROJECT_A, id, "Stammkunde", "de"));
        assertFalse(subjectHasPrefLabelWithRawLanguageTag(PROJECT_A, id, "DE"),
                "a case-differing language argument must not leave a duplicate, differently-cased literal behind");
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
        Term narrower = new Term(freshId(), new TermCode("TERM-2"), "Human Actor", "A human acting.", null,
                broaderCode);

        repository.create(PROJECT_A, narrower, null);

        assertEquals(broaderCode, repository.findByCode(PROJECT_A, new TermCode("TERM-2"), null)
                .orElseThrow().broader());
    }

    @Test
    void createRejectsABroaderCodeThatDoesNotResolveAndPersistsNothing() {
        Term narrower = new Term(freshId(), new TermCode("TERM-1"), "Human Actor", "A human acting.", null,
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

        Term result = repository.update(PROJECT_A, code, null, null, null, null, null, Optional.of(broaderCode));

        assertEquals(broaderCode, result.broader());
        assertEquals(broaderCode, repository.findByCode(PROJECT_A, code, null).orElseThrow().broader());
    }

    @Test
    void updateClearsAnAlreadySetBroaderReference() {
        TermCode broaderCode = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), broaderCode, "Actor", "Someone or something acting.",
                null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Human Actor", "A human acting.", null,
                broaderCode), null);

        Term result = repository.update(PROJECT_A, code, null, null, null, null, null, Optional.empty());

        assertNull(result.broader());
        assertNull(repository.findByCode(PROJECT_A, code, null).orElseThrow().broader());
    }

    @Test
    void updateOmittingBroaderLeavesAnAlreadySetOneUnchanged() {
        TermCode broaderCode = new TermCode("TERM-1");
        TermCode code = new TermCode("TERM-2");
        repository.create(PROJECT_A, new Term(freshId(), broaderCode, "Actor", "Someone or something acting.",
                null), null);
        repository.create(PROJECT_A, new Term(freshId(), code, "Human Actor", "A human acting.", null,
                broaderCode), null);

        Term result = repository.update(PROJECT_A, code, "Human Being", null, null, null, null, null);

        assertEquals("Human Being", result.prefLabel());
        assertEquals(broaderCode, result.broader());
        assertEquals(broaderCode, repository.findByCode(PROJECT_A, code, null).orElseThrow().broader());
    }

    @Test
    void updateRejectsABroaderCodeThatDoesNotResolveAndPersistsNothing() {
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), code, "Human Actor", "A human acting.", null), null);

        assertThrows(TermNotFoundException.class, () -> repository.update(
                PROJECT_A, code, null, null, null, null, null, Optional.of(new TermCode("TERM-404"))));
        assertNull(repository.findByCode(PROJECT_A, code, null).orElseThrow().broader());
    }

    @Test
    void updateRejectsATermAsItsOwnBroaderTermAndPersistsNothing() {
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(freshId(), code, "Actor", "Someone or something acting.", null),
                null);

        assertThrows(TermCycleException.class, () -> repository.update(
                PROJECT_A, code, null, null, null, null, null, Optional.of(code)));
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
        repository.create(PROJECT_A, new Term(freshId(), term2, "Human Actor", "A human acting.", null, term1),
                null);
        repository.create(PROJECT_A, new Term(freshId(), term3, "Customer", "A buying human.", null, term2), null);

        assertThrows(TermCycleException.class, () -> repository.update(
                PROJECT_A, term1, null, null, null, null, null, Optional.of(term3)));
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
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + id.value().value() + "> <http://www.w3.org/2004/02/skos/core#prefLabel> \""
                + value + "\"@" + tag + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /** Whether {@code subjectIri} carries a plain, untagged {@code skos:prefLabel} literal with exactly this value. */
    private boolean subjectHasUntaggedPrefLabel(ProjectId projectId, TermId id, String value) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
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
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
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
        public void close(DatasetId id) {
            delegate.close(id);
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
        public boolean ask(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
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
        public ReadableGraph export(IRI graph) {
            return delegate.export(graph);
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
        public boolean contains(IRI graph, io.kogn.rdf.terms.BlankNodeOrIRI subject, IRI predicate,
                io.kogn.rdf.terms.RDFTerm object) {
            return delegate.contains(graph, subject, predicate, object);
        }

        @Override
        public void update(String sparqlUpdate) {
            delegate.update(sparqlUpdate);
        }

        @Override
        public void update(String sparqlUpdate, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
            delegate.update(sparqlUpdate, bindings);
        }

        @Override
        public Stream<BindingSet> select(String query) {
            return delegate.select(query);
        }

        @Override
        public Stream<BindingSet> select(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
            return delegate.select(query, bindings);
        }

        @Override
        public ReadableGraph construct(String query) {
            return delegate.construct(query);
        }

        @Override
        public ReadableGraph construct(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
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

    @Test
    void createsAndFindsTermWithHumanActorFacet() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Kunde", "Person, die eine Bestellung aufgibt.",
                new ActorFacet(ActorKind.HUMAN, "Besteller"));

        repository.create(PROJECT_A, term, null);
        Optional<Term> found = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null);

        assertEquals(Optional.of(term), found);
        ActorFacet facet = found.orElseThrow().actorFacet();
        assertEquals(ActorKind.HUMAN, facet.kind());
        assertEquals("Besteller", facet.role());
        assertTrue(subjectHasType(PROJECT_A, id, "https://w3id.org/arknet/process#HumanActor"));
    }

    @Test
    void createsAndFindsTermWithSystemActorFacet() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Zahlungsdienst", "Verarbeitet Zahlungen.",
                new ActorFacet(ActorKind.SYSTEM, "PaymentService"));

        repository.create(PROJECT_A, term, null);
        Optional<Term> found = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null);

        assertEquals(Optional.of(term), found);
        ActorFacet facet = found.orElseThrow().actorFacet();
        assertEquals(ActorKind.SYSTEM, facet.kind());
        assertEquals("PaymentService", facet.role());
        assertTrue(subjectHasType(PROJECT_A, id, "https://w3id.org/arknet/process#SystemActor"));
    }

    @Test
    void createsAndFindsTermWithLegalActorFacet() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Kunde GmbH", "Ein Unternehmen, das Bestellungen aufgibt.",
                new ActorFacet(ActorKind.LEGAL, "Besteller"));

        repository.create(PROJECT_A, term, null);
        Optional<Term> found = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null);

        assertEquals(Optional.of(term), found);
        ActorFacet facet = found.orElseThrow().actorFacet();
        assertEquals(ActorKind.LEGAL, facet.kind());
        assertEquals("Besteller", facet.role());
        assertTrue(subjectHasType(PROJECT_A, id, "https://w3id.org/arknet/process#LegalActor"));
    }

    @Test
    void createsAndFindsTermWithoutActorFacet() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Gutschrift", "def a", null);

        repository.create(PROJECT_A, term, null);
        Optional<Term> found = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null);

        assertNull(found.orElseThrow().actorFacet());
        assertFalse(subjectHasType(PROJECT_A, id, "https://w3id.org/arknet/process#HumanActor"));
        assertFalse(subjectHasType(PROJECT_A, id, "https://w3id.org/arknet/process#SystemActor"));
        assertFalse(subjectHasType(PROJECT_A, id, "https://w3id.org/arknet/process#LegalActor"));
    }

    @Test
    void findAllReconstructsActorFacet() {
        Term withFacet = new Term(freshId(), new TermCode("TERM-1"), "Kunde", "def a",
                new ActorFacet(ActorKind.HUMAN, "Besteller"));
        repository.create(PROJECT_A, withFacet, null);

        List<Term> all = repository.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals(new ActorFacet(ActorKind.HUMAN, "Besteller"), all.get(0).actorFacet());
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
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
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
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
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
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
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

    // ---- actorRole: row multiplication when arkproc:actorRole is repeated -----------------

    /**
     * Store-first regression test (issue #154): neither {@code ulshapes:TermShape} nor
     * {@code arknet-actor.ttl} place a {@code sh:maxCount} on {@code arkproc:actorRole}, so an
     * actor-facetted subject with two role literals is shape-legal even though
     * {@code term_add}/{@code term_update} never write more than one. Before the fix,
     * {@code assemblyFor} read the actor facet (kind + role together) only from the first SPARQL
     * row bound for a subject via {@code computeIfAbsent} - correct by accident whenever the two
     * role rows happened to bind in insertion order, but silently dropping the second value
     * regardless, unlike the sibling {@code definition} case which already logged a {@code WARN}.
     * This pins the outward-visible contract: neither read throws, and each returns one of the
     * two role values without crashing.
     */
    @Test
    void findByCodeDoesNotThrowForATermWithTwoActorRoles() {
        TermId id = freshId();
        givenActorTermWithTwoRoles(PROJECT_A, id, "TERM-1", "Kaeufer", "Verkaeufer");

        Term found = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow();

        assertTrue(List.of("Kaeufer", "Verkaeufer").contains(found.actorFacet().role()));
    }

    /** Same regression as above, exercised via the batch {@link TermRepository#findAll}. */
    @Test
    void findAllDoesNotThrowForATermWithTwoActorRoles() {
        TermId id = freshId();
        givenActorTermWithTwoRoles(PROJECT_A, id, "TERM-1", "Kaeufer", "Verkaeufer");

        List<Term> all = repository.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertTrue(List.of("Kaeufer", "Verkaeufer").contains(all.get(0).actorFacet().role()));
    }

    /**
     * The chosen role is deterministic across repeated reads against the same, unchanged store
     * state - not merely "does not throw" - mirroring
     * {@link #findByCodePicksTheSameDefinitionOnRepeatedReads}'s determinism check for the
     * sibling {@code definition} case.
     */
    @Test
    void findByCodePicksTheSameActorRoleOnRepeatedReads() {
        TermId id = freshId();
        givenActorTermWithTwoRoles(PROJECT_A, id, "TERM-1", "Kaeufer", "Verkaeufer");

        String first = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow().actorFacet().role();
        String second = repository.findByCode(PROJECT_A, new TermCode("TERM-1"), null).orElseThrow().actorFacet().role();

        assertEquals(first, second);
    }

    /**
     * Writes an actor-facetted {@code skos:Concept} with two {@code arkproc:actorRole} literals
     * straight into the terms graph - shape-legal ({@code ulshapes:TermShape}/
     * {@code arknet-actor.ttl} place no constraint on the property's cardinality), but
     * unreachable via {@code term_add}/{@code term_update}, which only ever write one.
     */
    private void givenActorTermWithTwoRoles(
            ProjectId projectId, TermId id, String code, String firstRole, String secondRole) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> , "
                + "<https://w3id.org/arknet/process#HumanActor> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Kunde\" ; "
                + "<http://www.w3.org/2004/02/skos/core#definition> \"Definition.\" ; "
                + "<https://w3id.org/arknet/process#actorRole> \"" + firstRole + "\" ; "
                + "<https://w3id.org/arknet/process#actorRole> \"" + secondRole + "\" } }";
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
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
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
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
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
     * SHACL-legal store-first (ADR-005), even though {@code term_add} always mints an IRI
     * subject via {@link de.hauschel.arknet.kernel.ResourceIdFactory}.
     */
    private void givenBlankNodeTerm(ProjectId projectId, String code, String prefLabel, String definition) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
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

    private boolean subjectHasType(ProjectId projectId, TermId id, String typeIri) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + id.value().value() + "> a <" + typeIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    // ---- revision trail (ADR-014): one revision per write, head queryable ----------------

    /**
     * ADR-014 revision basis for this bounded context's funnel write paths: {@code create}
     * records exactly one immutable revision and the head is queryable per resource.
     */
    @Test
    void createRecordsExactlyOneRevisionWithAQueryableHead() {
        TermId id = freshId();
        repository.create(PROJECT_A, new Term(id, new TermCode("TERM-1"), "Gutschrift",
                "Eine dem Kundenkonto gutgeschriebene Erstattung.", null), null);

        List<String> revisions = revisionsOf(id);
        assertEquals(1, revisions.size(), "create must record exactly one revision");
        assertEquals(revisions, headsOf(id), "the head must point at the sole revision");
    }

    /**
     * The other half of the same ADR-014 guarantee, for the write path that only joined the
     * funnel with ADR-014 decision 4: the patch-{@code update} is no longer a
     * special path outside the revision trail - it records exactly one further revision and
     * moves the head, so every user-reachable {@code term_update} is now provenanced and its
     * head usable as the next writer's concurrency token.
     */
    @Test
    void updateRecordsExactlyOneFurtherRevisionAndAdvancesTheHead() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A, new Term(id, code, "Gutschrift", "Erste Definition.", null), null);
        List<String> headAfterCreate = headsOf(id);

        repository.update(PROJECT_A, code, null, "Zweite Definition.", null, null, null, null);

        List<String> revisions = revisionsOf(id);
        assertEquals(2, revisions.size(), "update must record exactly one more revision");
        List<String> heads = headsOf(id);
        assertEquals(1, heads.size(), "the head is rewritten, never duplicated");
        assertNotEquals(headAfterCreate, heads, "the head must advance to the update's revision");
        assertTrue(revisions.contains(heads.get(0)), "the head must be one of the term's revisions");
    }

    /**
     * The complement of the previous test: every field {@code update}
     * takes is {@code required = false} on the {@code term_update} MCP tool, so a caller can
     * legally invoke it with nothing but the identifying {@code code}. Such a call must be a true
     * no-op - no write, no revision, no head movement - exactly like the symmetric guard in
     * {@code RequirementService#updateWithOptimisticRetry}: a revision documents a model change
     * (ADR-011/ADR-014), and recording one for an empty patch would both grow the immutable
     * provenance trail without cause and hand a concurrent CAS writer a spurious conflict.
     */
    @Test
    void updateWithNoFieldsIsANoOpThatRecordsNoRevision() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(PROJECT_A,
                new Term(id, code, "Gutschrift", "Erste Definition.", null), null);
        List<String> headAfterCreate = headsOf(id);

        Term result = repository.update(PROJECT_A, code, null, null, null, null, null, null);

        assertEquals(1, revisionsOf(id).size(), "a field-less update must record no further revision");
        assertEquals(headAfterCreate, headsOf(id), "a field-less update must not move the head");
        assertEquals(new Term(id, code, "Gutschrift", "Erste Definition.", null), result);
    }

    /**
     * The retry half of ADR-014 decision 4: a concurrent writer that advances the
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
        assertEquals(3, revisionsOf(id).size(),
                "create, the concurrent update and the retried update - the losing attempt records none");
        assertTrue(revisionsOf(id).contains(headsOf(id).get(0)), "the head must be one of the term's revisions");
    }

    /**
     * Wraps a real {@link DatasetLifecycle} and runs {@code beforeTransaction} right before every
     * write transaction opens - the exact point at which a concurrent writer's commit turns this
     * caller's already-taken read (state plus {@code arkprov:head}) stale. The one-shot guard
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
        public void close(DatasetId id) {
            delegate.close(id);
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
     * term's assembly and its {@code arkprov:head} via two <em>separate</em>
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
                        repository.update(PROJECT_A, code, "Neu", null, null, null, null, null);
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
        public void close(DatasetId id) {
            delegate.close(id);
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
                public Stream<BindingSet> select(String sparql, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
                    List<BindingSet> rows = real.select(sparql, bindings).toList();
                    afterSelect.run();
                    return rows.stream();
                }

                @Override
                public ReadableGraph construct(String sparql) {
                    return real.construct(sparql);
                }

                @Override
                public ReadableGraph construct(String sparql, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
                    return real.construct(sparql, bindings);
                }

                @Override
                public boolean ask(String sparql) {
                    return real.ask(sparql);
                }

                @Override
                public boolean ask(String sparql, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
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

        @Override
        public DatasetTransactor transactor() {
            return delegate.transactor();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private List<String> revisionsOf(TermId id) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?v a <" + ArkprovVocabulary.REVISION_TYPE + "> ; "
                + "<" + ArkprovVocabulary.SPECIALIZATION_OF + "> <" + id.value().value() + "> } }");
    }

    private List<String> headsOf(TermId id) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + id.value().value() + "> <" + ArkprovVocabulary.HEAD + "> ?v } }");
    }

    private List<String> selectIris(String query) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .toList();
        }
    }
}
