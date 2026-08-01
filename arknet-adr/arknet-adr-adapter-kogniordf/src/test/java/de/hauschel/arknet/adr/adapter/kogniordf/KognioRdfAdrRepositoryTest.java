// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.adr.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Integration test for {@link KognioRdfAdrRepository} against an in-memory RDF4J-backed kognio-rdf
 * store.
 */
class KognioRdfAdrRepositoryTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String ADR_GRAPH = "https://w3id.org/arknet/model/adr";

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfAdrRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-adr-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        WriteFunnel funnel = new WriteFunnel(datasetLifecycle, gate, WriteFunnel.DEFAULT_WRITE_CONFLICT);
        repository = new KognioRdfAdrRepository(datasetLifecycle, funnel);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    /** Fresh, valid opaque identity - every test picks its own so ids never collide. */
    private static AdrId freshId() {
        return new AdrId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static Adr adr(AdrCode code) {
        return adr(freshId(), code, AdrStatus.PROPOSED, null, null, null, List.of(), List.of(), List.of());
    }

    private static Adr adr(AdrId id, AdrCode code, AdrStatus status, String consequences, String alternatives,
            LocalDate decisionDate, List<RequirementRef> requirements, List<BoundedContextRef> contexts,
            List<AdrId> supersedes) {
        return new Adr(id, code, "Use an embedded triple store", status,
                "The model has to live somewhere a single-user client can reach without a server.",
                "Use kognio-rdf as the embedded RDF substrate behind an out-port.",
                consequences, alternatives, decisionDate, requirements, contexts, supersedes);
    }

    @Test
    void createsAndFindsAdrByCode() {
        Adr created = adr(new AdrCode("ADR-1"));

        repository.create(PROJECT_A, created);
        Optional<Adr> found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1"));

        assertEquals(Optional.of(created), found);
        assertEquals(AdrStatus.PROPOSED, found.orElseThrow().status());
    }

    @Test
    void createsAndReadsBackEveryOptionalField() {
        Adr created = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.ACCEPTED,
                "The store becomes a single point of failure for the model.",
                "A remote SPARQL endpoint; rejected because a single-user client must work offline.",
                LocalDate.of(2026, 7, 31), List.of(), List.of(), List.of());

        repository.create(PROJECT_A, created);
        Adr found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1")).orElseThrow();

        assertEquals(created, found);
        assertEquals(AdrStatus.ACCEPTED, found.status());
        assertEquals(LocalDate.of(2026, 7, 31), found.decisionDate());
    }

    @Test
    void createsAndReadsBackWithoutOptionalFields() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")));

        Adr found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1")).orElseThrow();

        assertNull(found.consequences());
        assertNull(found.alternatives());
        assertNull(found.decisionDate());
    }

    /**
     * Round-trip regression for #91: {@code REJECTED} and {@code DEPRECATED} must survive a
     * write+read cycle the same way {@code PROPOSED}/{@code ACCEPTED} already do, both as the
     * lifecycle individual persisted and the enum value read back.
     */
    @Test
    void createsAndFindsAdrWithRejectedStatus() {
        Adr created = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.REJECTED, null, null, null,
                List.of(), List.of(), List.of());

        repository.create(PROJECT_A, created);
        Optional<Adr> found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1"));

        assertEquals(Optional.of(created), found);
        assertEquals(AdrStatus.REJECTED, found.orElseThrow().status());
        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + created.id().value().value() + "> <"
                + ArkarchVocabulary.ADR_STATUS + "> <" + ArkarchVocabulary.REJECTED + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    @Test
    void createsAndFindsAdrWithDeprecatedStatus() {
        Adr created = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.DEPRECATED, null, null, null,
                List.of(), List.of(), List.of());

        repository.create(PROJECT_A, created);
        Optional<Adr> found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1"));

        assertEquals(Optional.of(created), found);
        assertEquals(AdrStatus.DEPRECATED, found.orElseThrow().status());
        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + created.id().value().value() + "> <"
                + ArkarchVocabulary.ADR_STATUS + "> <" + ArkarchVocabulary.DEPRECATED + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    @Test
    void compareAndUpdateTransitionsToRejected() {
        Adr original = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), List.of());
        repository.create(PROJECT_A, original);

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.reject());

        assertEquals(AdrStatus.REJECTED,
                repository.findByCode(PROJECT_A, original.code()).orElseThrow().status());
    }

    @Test
    void compareAndUpdateTransitionsToDeprecated() {
        Adr original = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.ACCEPTED, null, null, null,
                List.of(), List.of(), List.of());
        repository.create(PROJECT_A, original);

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.deprecate());

        assertEquals(AdrStatus.DEPRECATED,
                repository.findByCode(PROJECT_A, original.code()).orElseThrow().status());
    }

    @Test
    void findAllReturnsEveryStoredAdr() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")));
        repository.create(PROJECT_A, adr(new AdrCode("ADR-2")));

        assertEquals(2, repository.findAll(PROJECT_A).size());
    }

    @Test
    void findByCodeIsEmptyForUnknownCode() {
        assertTrue(repository.findByCode(PROJECT_A, new AdrCode("ADR-99")).isEmpty());
    }

    @Test
    void createRejectsAnAlreadyExistingIdentity() {
        AdrId id = freshId();
        repository.create(PROJECT_A,
                adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null, List.of(), List.of(),
                        List.of()));

        Adr sameIdentity = adr(id, new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), List.of());

        assertThrows(ResourceAlreadyExistsException.class, () -> repository.create(PROJECT_A, sameIdentity));
    }

    @Test
    void createRejectsADuplicateBusinessCodeOnADifferentIdentity() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")));

        Adr sameCode = adr(new AdrCode("ADR-1"));

        assertThrows(DuplicateAdrCodeException.class, () -> repository.create(PROJECT_A, sameCode));
    }

    @Test
    void compareAndUpdateReplacesAnExistingAdr() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), List.of());
        repository.create(PROJECT_A, original);

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.accept());

        assertEquals(AdrStatus.ACCEPTED,
                repository.findByCode(PROJECT_A, original.code()).orElseThrow().status());
    }

    @Test
    void compareAndUpdateRejectsAMissingIdentity() {
        Adr missing = adr(new AdrCode("ADR-1"));

        assertThrows(AdrNotFoundException.class, () -> repository.compareAndUpdate(PROJECT_A, null, missing));
    }

    @Test
    void writeRejectsATooShortContextViaTheShaclGate() {
        // ashapes:ADR-context carries sh:minLength 5 at sh:Violation severity. The domain record only
        // forbids a blank context, so this candidate is built directly rather than through Adr.
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(ArkarchVocabulary.ADR_TYPE));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/identifier"),
                rdf.createLiteral("ADR-1"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/core#name"),
                rdf.createLiteral("A decision"));
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.ADR_STATUS),
                rdf.createIRI(ArkarchVocabulary.PROPOSED));
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.ADR_CONTEXT), rdf.createLiteral("x"));
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.ADR_DECISION),
                rdf.createLiteral("A long enough decision text."));

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    /**
     * {@code ashapes:ADR-consequences}/{@code ADR-alternatives} are {@code sh:Warning}, not
     * {@code sh:Violation}: a decision recorded while it is still being argued has neither yet, and
     * the gate collects only violations - so the write passes.
     */
    @Test
    void anAdrWithoutConsequencesOrAlternativesPassesTheGate() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")));

        assertTrue(repository.findByCode(PROJECT_A, new AdrCode("ADR-1")).isPresent());
    }

    @Test
    void writePersistsAllThreeRelationsAndReadsThemBack() {
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        BoundedContextRef contextRef = new BoundedContextRef(ResourceId.of("https://w3id.org/arknet/id/bc-1"));
        AdrId superseded = freshId();
        Adr created = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(requirement), List.of(contextRef), List.of(superseded));

        repository.create(PROJECT_A, created);
        Adr found = repository.findByCode(PROJECT_A, new AdrCode("ADR-2")).orElseThrow();

        assertEquals(List.of(requirement), found.addressesRequirements());
        assertEquals(List.of(contextRef), found.affectsContexts());
        assertEquals(List.of(superseded), found.supersedes());
    }

    /**
     * The forward edge is asserted, its {@code owl:inverseOf} partner deliberately is not: nothing in
     * this codebase materialises an inverse as a second physical triple. A reader that wants the
     * backward direction gets it from {@link AdrRepository#findSupersedingCodes}, not from a stored
     * {@code arkarch:supersededBy}.
     */
    @Test
    void writeAssertsOnlyTheForwardSupersedesTripleNeverItsInverse() {
        AdrId supersededId = freshId();
        Adr superseded = adr(supersededId, new AdrCode("ADR-1"), AdrStatus.ACCEPTED, null, null, null,
                List.of(), List.of(), List.of());
        repository.create(PROJECT_A, superseded);
        Adr superseding = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), List.of(supersededId));
        repository.create(PROJECT_A, superseding);

        String inverseAsk = "ASK { GRAPH <" + ADR_GRAPH + "> { ?s <"
                + ArkarchVocabulary.SUPERSEDED_BY + "> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask(inverseAsk),
                    "arkarch:supersededBy must never be written as a second physical triple");
        }
        assertEquals(List.of(new AdrCode("ADR-2")), repository.findSupersedingCodes(PROJECT_A, supersededId));
    }

    @Test
    void findSupersedingCodesIsEmptyForANeverSupersededAdr() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created);

        assertEquals(List.of(), repository.findSupersedingCodes(PROJECT_A, created.id()));
    }

    /**
     * Codes must sort by their parsed running number, not by {@link String}'s natural (lexicographic)
     * order - which would put {@code ADR-10}/{@code ADR-11} before {@code ADR-2}/{@code ADR-3}.
     */
    @Test
    void findSupersedingCodesSortsByRunningNumberNotLexicographically() {
        Adr superseded = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, superseded);

        for (String code : List.of("ADR-11", "ADR-2", "ADR-10", "ADR-3")) {
            repository.create(PROJECT_A, adr(freshId(), new AdrCode(code), AdrStatus.PROPOSED, null, null, null,
                    List.of(), List.of(), List.of(superseded.id())));
        }

        assertEquals(
                List.of(new AdrCode("ADR-2"), new AdrCode("ADR-3"), new AdrCode("ADR-10"), new AdrCode("ADR-11")),
                repository.findSupersedingCodes(PROJECT_A, superseded.id()));
    }

    @Test
    void findCodesByIdsResolvesOnlyWhatExists() {
        Adr first = adr(new AdrCode("ADR-1"));
        Adr second = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, first);
        repository.create(PROJECT_A, second);

        Map<AdrId, AdrCode> codes =
                repository.findCodesByIds(PROJECT_A, List.of(first.id(), second.id(), freshId()));

        assertEquals(Map.of(first.id(), first.code(), second.id(), second.code()), codes);
    }

    @Test
    void findCodesByIdsOfAnEmptyCollectionQueriesNothing() {
        assertEquals(Map.of(), repository.findCodesByIds(PROJECT_A, List.of()));
    }

    /**
     * Replace-by-identity regression: an update must carry the earlier reference edges along rather
     * than dropping them - this is what the application service's {@code accept}/{@code supersede}
     * rely on.
     */
    @Test
    void compareAndUpdatePreservesAndExtendsTheRelationEdges() {
        AdrId id = freshId();
        AdrId supersededA = freshId();
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(requirement), List.of(), List.of(supersededA));
        repository.create(PROJECT_A, original);

        AdrId supersededB = freshId();
        Adr extended = original.supersede(supersededB).accept();
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), extended);

        Adr found = repository.findByCode(PROJECT_A, original.code()).orElseThrow();
        // Compared as a set: the read path orders reference edges by target IRI (RDF has no
        // intrinsic statement order), so the two freshly minted UUID identities come back in
        // whichever order they happen to sort - not in the order they were written.
        assertEquals(Set.of(supersededA, supersededB), Set.copyOf(found.supersedes()));
        assertEquals(List.of(requirement), found.addressesRequirements());
        assertEquals(AdrStatus.ACCEPTED, found.status());
    }

    /**
     * {@code arkarch:relatedTo} and {@code arkarch:supersededBy} have no field on {@link Adr} at all -
     * they are reachable only store-first (ADR-005). A replace-by-identity write must carry both
     * along instead of silently erasing them, the same preservation the bounded-context adapter
     * performs for {@code arkddd:hasAggregate}.
     */
    @Test
    void compareAndUpdatePreservesStoreFirstRelatedToAndSupersededByEdges() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), List.of());
        repository.create(PROJECT_A, original);

        String relatedIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String supersededByIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String insert = "INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.RELATED_TO + "> <" + relatedIri + "> ; <"
                + ArkarchVocabulary.SUPERSEDED_BY + "> <" + supersededByIri + "> } }";
        update(insert);

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.accept());

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.RELATED_TO + "> <" + relatedIri + "> ; <"
                + ArkarchVocabulary.SUPERSEDED_BY + "> <" + supersededByIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    /**
     * Blank-node regression: {@code arkarch:addressesRequirement} is not range-constrained to
     * {@code IRI} at the RDF level, so a store-first edge can legally target a blank node.
     * {@link ResourceId} cannot represent one, so the read path never surfaces it - but the write path
     * must still capture and re-attach it across an unrelated update instead of erasing it.
     */
    @Test
    void compareAndUpdatePreservesABlankNodeAddressesRequirementEdge() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), List.of());
        repository.create(PROJECT_A, original);

        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.ADDRESSES_REQUIREMENT + "> "
                + "[ a <https://w3id.org/arknet/requirements#FunctionalRequirement> ] } }");

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.accept());

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.ADDRESSES_REQUIREMENT + "> ?target . "
                + "?target a <https://w3id.org/arknet/requirements#FunctionalRequirement> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask), "blank-node edge must survive the update and still "
                    + "point at its typed node - not merely at some blank node");
        }
    }

    @Test
    void projectsAreIsolated() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")));

        assertFalse(repository.findByCode(PROJECT_B, new AdrCode("ADR-1")).isPresent());
        assertTrue(repository.findAll(PROJECT_B).isEmpty());
    }

    /** A store-first ADR is what actually lands in the shared project dataset. */
    @Test
    void writesIntoTheAdrNamedGraph() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created);

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + created.id().value().value()
                + "> a <" + ArkarchVocabulary.ADR_TYPE + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    /** The status is an IRI-valued individual, never a literal - that is what the shape's sh:in needs. */
    @Test
    void writesTheStatusAsALifecycleIndividual() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created);

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + created.id().value().value() + "> <"
                + ArkarchVocabulary.ADR_STATUS + "> <" + ArkarchVocabulary.PROPOSED + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    // ---- revision trail (ADR-014) and compare-and-set --------------------------------------

    @Test
    void everyWriteRecordsExactlyOneRevisionAndMovesTheQueryableHead() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created);
        String subject = created.id().value().value();

        List<String> afterCreate = revisionsOf(subject);
        assertEquals(1, afterCreate.size(), "create must record exactly one revision");
        assertEquals(afterCreate, headsOf(subject), "the head must point at the sole revision");

        repository.compareAndUpdate(PROJECT_A, afterCreate.get(0), created.accept());

        assertEquals(2, revisionsOf(subject).size(), "update must record exactly one more revision");
        List<String> heads = headsOf(subject);
        assertEquals(1, heads.size(), "the head is rewritten, never duplicated");
        assertFalse(heads.get(0).equals(afterCreate.get(0)), "the head must have moved");
        assertEquals(List.of(afterCreate.get(0)), objectsOf(heads.get(0), ArkprovVocabulary.WAS_REVISION_OF),
                "the new head must supersede the previous one via prov:wasRevisionOf");
    }

    @Test
    void aRejectedWriteLeavesNoRevisionBehind() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")));

        assertThrows(DuplicateAdrCodeException.class,
                () -> repository.create(PROJECT_A, adr(new AdrCode("ADR-1"))));

        String all = "SELECT ?r WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?r a <" + ArkprovVocabulary.REVISION_TYPE + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertEquals(1, handle.sparqlQuery().select(all).count(),
                    "the rejected write must not have recorded a revision");
        }
    }

    @Test
    void findCurrentByCodeReturnsTheStateTogetherWithTheCurrentHead() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created);

        AdrRepository.CurrentAdr current =
                repository.findCurrentByCode(PROJECT_A, new AdrCode("ADR-1")).orElseThrow();

        assertEquals(created, current.value());
        assertEquals(headsOf(created.id().value().value()), List.of(current.head()));
    }

    @Test
    void findCurrentByCodeReturnsEmptyForAnUnknownCode() {
        assertEquals(Optional.empty(), repository.findCurrentByCode(PROJECT_A, new AdrCode("ADR-9")));
    }

    /**
     * {@code findCurrentByCode} now joins {@code ?head} into the very same {@code SELECT} as the
     * scalar fields (the fix for the lost-update race a separate, later head query allowed - a
     * concurrent {@code compareAndUpdate} landing between the two reads let the caller's stale state
     * pair with a fresher head and win a CAS it should have lost). That join must survive the
     * row-multiplication pattern this adapter otherwise tolerates: a store-first second
     * {@code arkarch:adrContext} triple multiplies the query's rows, and the head - one triple,
     * repeated identically on every row of the cross product - must still collapse to the single
     * value {@link #currentHeadOf} observes, not be lost or duplicated by the extra join.
     */
    @Test
    void findCurrentByCodeKeepsTheHeadCorrectUnderRowMultiplication() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created);
        String subject = created.id().value().value();
        String expectedHead = currentHeadOf(created.code());

        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + subject + "> <"
                + ArkarchVocabulary.ADR_CONTEXT + "> \"a second, store-first context value\" } }");

        AdrRepository.CurrentAdr current =
                repository.findCurrentByCode(PROJECT_A, new AdrCode("ADR-1")).orElseThrow();

        assertEquals(created.context(), current.value().context(), "first-seen value still wins");
        assertEquals(expectedHead, current.head(), "the head must not be affected by row multiplication");
    }

    /**
     * The write side of the guard: a caller whose observed head is no longer current - because
     * another writer committed in between - is rejected instead of overwriting the change it never
     * saw, and its rejected write leaves neither a triple nor a revision behind.
     */
    @Test
    void compareAndUpdateRejectsAStaleHeadAndWritesNothing() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), List.of());
        repository.create(PROJECT_A, original);
        String staleHead = currentHeadOf(original.code());

        AdrId supersededByWinner = freshId();
        repository.compareAndUpdate(PROJECT_A, staleHead, original.supersede(supersededByWinner));

        AdrId supersededByLoser = freshId();
        Adr byTheLoser = original.supersede(supersededByLoser);
        assertThrows(AdrConcurrentlyModifiedException.class,
                () -> repository.compareAndUpdate(PROJECT_A, staleHead, byTheLoser));

        assertEquals(List.of(supersededByWinner),
                repository.findByCode(PROJECT_A, original.code()).orElseThrow().supersedes());
        assertEquals(2, revisionsOf(id.value().value()).size(),
                "the rejected write must not have recorded a revision");
    }

    /** The head a caller would observe right now - what a well-behaved compare-and-set passes. */
    private String currentHeadOf(AdrCode code) {
        return repository.findCurrentByCode(PROJECT_A, code).orElseThrow().head();
    }

    private void update(String sparqlUpdate) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(sparqlUpdate);
                return null;
            });
        }
    }

    private List<String> revisionsOf(String subjectIri) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?v a <" + ArkprovVocabulary.REVISION_TYPE + "> ; "
                + "<" + ArkprovVocabulary.SPECIALIZATION_OF + "> <" + subjectIri + "> } }");
    }

    private List<String> headsOf(String subjectIri) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + subjectIri + "> <" + ArkprovVocabulary.HEAD + "> ?v } }");
    }

    private List<String> objectsOf(String subjectIri, String predicateIri) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + subjectIri + "> <" + predicateIri + "> ?v } }");
    }

    private List<String> selectIris(String query) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .toList();
        }
    }
}
