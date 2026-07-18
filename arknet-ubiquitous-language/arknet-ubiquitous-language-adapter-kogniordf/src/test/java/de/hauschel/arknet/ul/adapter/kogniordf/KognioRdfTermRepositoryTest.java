package de.hauschel.arknet.ul.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
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
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * Integration test for {@link KognioRdfTermRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 */
class KognioRdfTermRepositoryTest {

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("a");
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("b");
    private static final String SKOS_CONCEPT = "http://www.w3.org/2004/02/skos/core#Concept";

    private DatasetLifecycleRdf4j lifecycle;
    private TermRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-ul-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
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

        repository.create(WORKSPACE_A, term);
        Optional<Term> found = repository.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertEquals(Optional.of(term), found);
        assertEquals("Gutschrift", found.orElseThrow().prefLabel());
        assertEquals("Rueckerstattung eines bereits gezahlten Betrags.", found.orElseThrow().definition());
    }

    @Test
    void findAllContainsAllCreatedTerms() {
        Term first = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, first);
        assertEquals(1, repository.findAll(WORKSPACE_A).size());

        Term second = new Term(freshId(), new TermCode("TERM-2"), "Bestellung", "def b", null);
        repository.create(WORKSPACE_A, second);

        List<Term> all = repository.findAll(WORKSPACE_A);
        assertEquals(2, all.size());
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void createRejectsAnAlreadyExistingIdentityAndPersistsNothingElse() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, term);

        Term collidingId = new Term(id, new TermCode("TERM-2"), "Bestellung", "def b", null);

        assertThrows(ResourceAlreadyExistsException.class,
                () -> repository.create(WORKSPACE_A, collidingId));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(Optional.of(term), repository.findByCode(WORKSPACE_A, new TermCode("TERM-1")));
    }

    /**
     * Identity collision and code collision are distinct failure modes: two different, freshly
     * minted identities both claiming {@code TERM-1} must be rejected by code, not by identity -
     * the sibling requirements BC relies on {@code dcterms:identifier} being unique (#36).
     */
    @Test
    void createRejectsADuplicateCodeUnderADifferentIdentityAndPersistsNothingElse() {
        TermCode code = new TermCode("TERM-1");
        Term first = new Term(freshId(), code, "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, first);

        Term collidingCode = new Term(freshId(), code, "Bestellung", "def b", null);

        assertThrows(DuplicateTermCodeException.class,
                () -> repository.create(WORKSPACE_A, collidingCode));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(Optional.of(first), repository.findByCode(WORKSPACE_A, code));
    }

    @Test
    void updateRejectsAMissingIdentity() {
        Term neverCreated = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);

        assertThrows(TermNotFoundException.class,
                () -> repository.update(WORKSPACE_A, neverCreated));
        assertTrue(repository.findAll(WORKSPACE_A).isEmpty());
    }

    @Test
    void updateReplacesByIdentityInsteadOfDuplicating() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        Term original = new Term(id, code, "Gutschrift", "Erste Definition.", null);
        Term revised = new Term(id, code, "Gutschrift", "Ueberarbeitete Definition.", null);

        repository.create(WORKSPACE_A, original);
        repository.update(WORKSPACE_A, revised);

        assertEquals(Optional.of(revised), repository.findByCode(WORKSPACE_A, code));
        assertEquals(1, repository.findAll(WORKSPACE_A).size());
        assertEquals(revised, repository.findAll(WORKSPACE_A).get(0));
    }

    /** The opaque identity is preserved across an update - only the term's state changes. */
    @Test
    void updatePreservesTheOpaqueIdentity() {
        TermId id = freshId();
        TermCode code = new TermCode("TERM-1");
        repository.create(WORKSPACE_A, new Term(id, code, "Gutschrift", "Erste Definition.", null));

        repository.update(WORKSPACE_A, new Term(id, code, "Gutschrift", "Ueberarbeitete Definition.", null));

        assertEquals(id, repository.findByCode(WORKSPACE_A, code).orElseThrow().id());
    }

    @Test
    void findByCodeReturnsEmptyForUnknownCode() {
        assertEquals(Optional.empty(), repository.findByCode(WORKSPACE_A, new TermCode("TERM-99")));
    }

    @Test
    void workspacesAreIsolated() {
        Term term = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);

        repository.create(WORKSPACE_A, term);

        assertTrue(repository.findAll(WORKSPACE_B).isEmpty());
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
        ShaclWriteGate gate = KognioRdfTermRepositoryFactory.buildGate();
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

        repository.create(WORKSPACE_A, term);
        Optional<Term> found = repository.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertEquals(Optional.of(term), found);
        ActorFacet facet = found.orElseThrow().actorFacet();
        assertEquals(ActorKind.HUMAN, facet.kind());
        assertEquals("Besteller", facet.role());
        assertTrue(subjectHasType(WORKSPACE_A, id, "https://w3id.org/arknet/process#HumanActor"));
    }

    @Test
    void createsAndFindsTermWithSystemActorFacet() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Zahlungsdienst", "Verarbeitet Zahlungen.",
                new ActorFacet(ActorKind.SYSTEM, "PaymentService"));

        repository.create(WORKSPACE_A, term);
        Optional<Term> found = repository.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertEquals(Optional.of(term), found);
        ActorFacet facet = found.orElseThrow().actorFacet();
        assertEquals(ActorKind.SYSTEM, facet.kind());
        assertEquals("PaymentService", facet.role());
        assertTrue(subjectHasType(WORKSPACE_A, id, "https://w3id.org/arknet/process#SystemActor"));
    }

    @Test
    void createsAndFindsTermWithoutActorFacet() {
        TermId id = freshId();
        Term term = new Term(id, new TermCode("TERM-1"), "Gutschrift", "def a", null);

        repository.create(WORKSPACE_A, term);
        Optional<Term> found = repository.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertNull(found.orElseThrow().actorFacet());
        assertFalse(subjectHasType(WORKSPACE_A, id, "https://w3id.org/arknet/process#HumanActor"));
        assertFalse(subjectHasType(WORKSPACE_A, id, "https://w3id.org/arknet/process#SystemActor"));
    }

    @Test
    void findAllReconstructsActorFacet() {
        Term withFacet = new Term(freshId(), new TermCode("TERM-1"), "Kunde", "def a",
                new ActorFacet(ActorKind.HUMAN, "Besteller"));
        repository.create(WORKSPACE_A, withFacet);

        List<Term> all = repository.findAll(WORKSPACE_A);

        assertEquals(1, all.size());
        assertEquals(new ActorFacet(ActorKind.HUMAN, "Besteller"), all.get(0).actorFacet());
    }

    // ---- display-language fallback for multilingual prefLabel (issue #80) ----------------

    /**
     * A concept with {@code @de} and {@code @en} prefLabels, read with a German display locale,
     * surfaces the German label - step 1 of the fallback chain.
     */
    @Test
    void findByCodePicksThePrefLabelInTheRequestedLanguage() {
        TermId id = freshId();
        givenMultilingualConcept(WORKSPACE_A, id, "TERM-1", "Person, die bestellt.",
                "\"Kunde\"@de, \"Customer\"@en");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        Optional<Term> found = germanReader.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertEquals("Kunde", found.orElseThrow().prefLabel());
    }

    /**
     * A concept lacking the requested language ({@code @de}) but present in the system default
     * ({@code @en}) surfaces the English label - step 2. The term must NOT vanish (the #65 error
     * class: a hard language filter would bind nothing).
     */
    @Test
    void findByCodeFallsBackToTheSystemDefaultLanguage() {
        TermId id = freshId();
        givenMultilingualConcept(WORKSPACE_A, id, "TERM-1", "Person, die bestellt.",
                "\"Customer\"@en, \"Client\"@fr");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        Optional<Term> found = germanReader.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

        assertEquals("Customer", found.orElseThrow().prefLabel());
    }

    /** A plain, untagged prefLabel (today's term_add normal case) surfaces via step 3. */
    @Test
    void findByCodeFallsBackToAnUntaggedPrefLabel() {
        TermId id = freshId();
        givenMultilingualConcept(WORKSPACE_A, id, "TERM-1", "Person, die bestellt.", "\"Kunde\"");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        Optional<Term> found = germanReader.findByCode(WORKSPACE_A, new TermCode("TERM-1"));

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
        givenMultilingualConcept(WORKSPACE_A, id, "TERM-1", "Person, die bestellt.",
                "\"Client\"@fr, \"Cliente\"@es");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        String first = germanReader.findByCode(WORKSPACE_A, new TermCode("TERM-1")).orElseThrow().prefLabel();
        String second = germanReader.findByCode(WORKSPACE_A, new TermCode("TERM-1")).orElseThrow().prefLabel();

        assertEquals("Cliente", first);
        assertEquals(first, second);
    }

    /** The same multilingual selection applies to findAll, not only findByCode. */
    @Test
    void findAllPicksThePrefLabelInTheRequestedLanguage() {
        givenMultilingualConcept(WORKSPACE_A, freshId(), "TERM-1", "Person, die bestellt.",
                "\"Kunde\"@de, \"Customer\"@en");
        TermRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        List<Term> all = germanReader.findAll(WORKSPACE_A);

        assertEquals(1, all.size());
        assertEquals("Kunde", all.get(0).prefLabel());
    }

    /** Distinct display locales over the same store surface distinct labels for the same concept. */
    @Test
    void findByCodeHonoursTheConfiguredDisplayLocale() {
        givenMultilingualConcept(WORKSPACE_A, freshId(), "TERM-1", "Person, die bestellt.",
                "\"Kunde\"@de, \"Customer\"@en");

        assertEquals("Kunde", readerFor(Locale.GERMAN, Locale.ENGLISH)
                .findByCode(WORKSPACE_A, new TermCode("TERM-1")).orElseThrow().prefLabel());
        assertEquals("Customer", readerFor(Locale.ENGLISH, Locale.GERMAN)
                .findByCode(WORKSPACE_A, new TermCode("TERM-1")).orElseThrow().prefLabel());
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
            WorkspaceId workspaceId, TermId id, String code, String definition, String prefLabelList) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#definition> \"" + definition + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> " + prefLabelList + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- findByIds: batch resolution for ResolveTerms (issue #77 nachtrag) --------------

    @Test
    void findByIdsResolvesKnownIdentitiesInOneQuery() {
        Term first = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        Term second = new Term(freshId(), new TermCode("TERM-2"), "Bestellung", "def b", null);
        repository.create(WORKSPACE_A, first);
        repository.create(WORKSPACE_A, second);

        List<ResolveTerms.ResolvedTerm> resolved =
                repository.findByIds(WORKSPACE_A, List.of(first.id().value(), second.id().value()));

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveTerms.ResolvedTerm(first.id().value(), first.code())));
        assertTrue(resolved.contains(new ResolveTerms.ResolvedTerm(second.id().value(), second.code())));
    }

    /** An id absent from the workspace is simply absent from the result, never an error. */
    @Test
    void findByIdsSilentlyOmitsUnknownIdentities() {
        Term known = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, known);
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/does-not-exist");

        List<ResolveTerms.ResolvedTerm> resolved =
                repository.findByIds(WORKSPACE_A, List.of(known.id().value(), unknown));

        assertEquals(List.of(new ResolveTerms.ResolvedTerm(known.id().value(), known.code())), resolved);
    }

    @Test
    void findByIdsWithEmptyIdsReturnsAnEmptyListWithoutQuerying() {
        assertEquals(List.of(), repository.findByIds(WORKSPACE_A, List.of()));
    }

    @Test
    void findByIdsIsScopedPerWorkspace() {
        Term inWorkspaceA = new Term(freshId(), new TermCode("TERM-1"), "Gutschrift", "def a", null);
        repository.create(WORKSPACE_A, inWorkspaceA);

        assertEquals(List.of(), repository.findByIds(WORKSPACE_B, List.of(inWorkspaceA.id().value())));
    }

    /**
     * Store-first regression test (issue #77, second nachtrag; narrowed by issue #84):
     * {@code ulshapes:TermShape} places no constraint at all on {@code dcterms:identifier} (no
     * {@code sh:minCount}, no {@code sh:maxCount}), so a subject with two identifier triples is
     * shape-legal even though {@code term_add} never writes more than one. {@code findByIds}'
     * mandatory {@code identifier} join must not multiply such a subject into two
     * {@link ResolveTerms.ResolvedTerm}s carrying the same id - a caller keying its own results
     * by identity (as {@code RequirementMcpTools#resolveTermsFor} does) would otherwise throw on
     * the duplicate key. (Before #84 this vector ran through {@code skos:prefLabel}, which
     * {@code findByIds} no longer joins at all.)
     */
    @Test
    void findByIdsReturnsExactlyOneResolvedTermForASubjectWithSeveralIdentifiers() {
        TermId id = freshId();
        givenTermWithTwoIdentifiers(WORKSPACE_A, id, "TERM-1", "TERM-1-ALT");

        List<ResolveTerms.ResolvedTerm> resolved = repository.findByIds(WORKSPACE_A, List.of(id.value()));

        assertEquals(1, resolved.size());
        assertEquals(id.value(), resolved.get(0).id());
    }

    /**
     * Issue #84: {@code findByIds} joins only {@code identifier}, not {@code prefLabel}/
     * {@code definition} - fields the {@link ResolveTerms.ResolvedTerm} projection never carries.
     * A store-first term that has an identity and a code but happens to miss a
     * {@code skos:prefLabel} (shape-invalid for {@link #findByCode}/{@link #findAll}, which still
     * require one) is therefore resolvable here, where the earlier, wider join used to exclude it.
     */
    @Test
    void findByIdsResolvesATermWithoutAnyPrefLabel() {
        TermId id = freshId();
        givenTermWithoutPrefLabel(WORKSPACE_A, id, "TERM-1");

        List<ResolveTerms.ResolvedTerm> resolved = repository.findByIds(WORKSPACE_A, List.of(id.value()));

        assertEquals(List.of(new ResolveTerms.ResolvedTerm(id.value(), new TermCode("TERM-1"))), resolved);
    }

    /**
     * Writes a {@code skos:Concept} straight into the terms graph with two
     * {@code dcterms:identifier} triples - shape-legal ({@code ulshapes:TermShape} places no
     * constraint on the property at all), but unreachable via {@code term_add}.
     */
    private void givenTermWithTwoIdentifiers(WorkspaceId workspaceId, TermId id, String first, String second) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + first + "\" ; "
                + "<http://purl.org/dc/terms/identifier> \"" + second + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Kunde\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /** Writes a {@code skos:Concept} without any {@code skos:prefLabel} - store-first only. */
    private void givenTermWithoutPrefLabel(WorkspaceId workspaceId, TermId id, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + id.value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#definition> \"Eine Person, die bestellt.\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    private boolean subjectHasType(WorkspaceId workspaceId, TermId id, String typeIri) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + id.value().value() + "> a <" + typeIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }
}
