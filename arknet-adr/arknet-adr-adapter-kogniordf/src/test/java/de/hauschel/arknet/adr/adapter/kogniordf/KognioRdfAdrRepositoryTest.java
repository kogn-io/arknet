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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotDeletableException;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrReferencedException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.adr.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.adr.domain.TermRef;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
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
    /** Any fixed day - these tests exercise persistence, not which day a transition stamps. */
    private static final LocalDate DECIDED_ON = LocalDate.of(2026, 8, 23);
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
        repository = new KognioRdfAdrRepository(
                datasetLifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT, funnel);
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
        return adr(freshId(), code, AdrStatus.PROPOSED, List.of(), List.of(), null, List.of(), List.of(), null);
    }

    private static Adr adr(AdrId id, AdrCode code, AdrStatus status, List<Consequence> consequences,
            List<ConsideredOption> consideredOptions, LocalDate decisionDate, List<RequirementRef> requirements,
            List<BoundedContextRef> contexts, AdrId supersededBy) {
        return adr(id, code, status, consequences, consideredOptions, decisionDate, requirements, contexts,
                supersededBy, List.of());
    }

    private static Adr adr(AdrId id, AdrCode code, AdrStatus status, List<Consequence> consequences,
            List<ConsideredOption> consideredOptions, LocalDate decisionDate, List<RequirementRef> requirements,
            List<BoundedContextRef> contexts, AdrId supersededBy, List<AdrId> relatedTo) {
        return new Adr(id, code, "Use an embedded triple store", status,
                "The model has to live somewhere a single-user client can reach without a server.",
                "Use kognio-rdf as the embedded RDF substrate behind an out-port.",
                consequences, consideredOptions, decisionDate, requirements, contexts, List.of(), supersededBy,
                relatedTo);
    }

    @Test
    void createsAndFindsAdrByCode() {
        Adr created = adr(new AdrCode("ADR-1"));

        repository.create(PROJECT_A, created, "en");
        Optional<Adr> found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1"), null);

        assertEquals(Optional.of(created), found);
        assertEquals(AdrStatus.PROPOSED, found.orElseThrow().status());
    }

    @Test
    void createsAndReadsBackEveryOptionalField() {
        Adr created = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.ACCEPTED,
                List.of(new Consequence(1, "The store becomes a single point of failure for the model.",
                        ConsequenceType.NEGATIVE)),
                List.of(new ConsideredOption(1, "Remote SPARQL endpoint",
                        "Rejected because a single-user client must work offline.", OptionOutcome.REJECTED)),
                LocalDate.of(2026, 7, 31), List.of(), List.of(), null);

        repository.create(PROJECT_A, created, "en");
        Adr found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1"), null).orElseThrow();

        assertEquals(created, found);
        assertEquals(AdrStatus.ACCEPTED, found.status());
        assertEquals(LocalDate.of(2026, 7, 31), found.decisionDate());
    }

    @Test
    void createsAndReadsBackWithoutOptionalFields() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");

        Adr found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1"), null).orElseThrow();

        assertEquals(List.of(), found.consequences());
        assertEquals(List.of(), found.consideredOptions());
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
                List.of(), List.of(), null);

        repository.create(PROJECT_A, created, "en");
        Optional<Adr> found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1"), null);

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
                List.of(), List.of(), null);

        repository.create(PROJECT_A, created, "en");
        Optional<Adr> found = repository.findByCode(PROJECT_A, new AdrCode("ADR-1"), null);

        assertEquals(Optional.of(created), found);
        assertEquals(AdrStatus.DEPRECATED, found.orElseThrow().status());
        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + created.id().value().value() + "> <"
                + ArkarchVocabulary.ADR_STATUS + "> <" + ArkarchVocabulary.DEPRECATED + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    /**
     * Round-trip for kogn-io/arknet#357's fifth status, now a real, writable value: SUPERSEDED
     * writes together with its {@code supersededBy} edge and reads back as both.
     */
    @Test
    void createsAndFindsAdrWithSupersededStatus() {
        Adr successor = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, successor, "en");
        Adr created = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.SUPERSEDED, null, null, null,
                List.of(), List.of(), successor.id());

        repository.create(PROJECT_A, created, "en");
        Optional<Adr> found = repository.findByCode(PROJECT_A, new AdrCode("ADR-2"), null);

        assertEquals(Optional.of(created), found);
        assertEquals(AdrStatus.SUPERSEDED, found.orElseThrow().status());
        assertEquals(successor.id(), found.orElseThrow().supersededBy());
        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + created.id().value().value() + "> <"
                + ArkarchVocabulary.ADR_STATUS + "> <" + ArkarchVocabulary.SUPERSEDED + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    /**
     * A store-first {@code arkarch:adrStatus} value the gate's {@code sh:in} list admits
     * but {@link de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfAdrRepository} cannot decode
     * (there is none left, now that all five lifecycle individuals are decoded) is simulated with a
     * value the shape does not even admit, the only way left to reach the unresolvable branch: the
     * read is skipped with a {@code WARN} rather than crashing the whole listing - the same fate an
     * unresolvable status has always had, only reachable through a different door since
     * kogn-io/arknet#357 closed the {@code Superseded} gap.
     */
    @Test
    void findAllSkipsADecisionWithAnUnresolvableStatusRatherThanCrashing() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");
        AdrId unresolvable = freshId();
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + unresolvable.value().value() + "> a <"
                + ArkarchVocabulary.ADR_TYPE + "> ; <http://purl.org/dc/terms/identifier> \"ADR-2\" ; "
                + "<https://w3id.org/arknet/core#name> \"Unresolvable\" ; <" + ArkarchVocabulary.ADR_STATUS
                + "> <https://w3id.org/arknet/architecture#NotAKnownStatus> ; <"
                + ArkarchVocabulary.ADR_CONTEXT + "> \"Enough context text\" ; <"
                + ArkarchVocabulary.ADR_DECISION + "> \"Enough decision text\" } }");

        List<Adr> all = repository.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals(new AdrCode("ADR-1"), all.get(0).code());
        assertTrue(repository.findByCode(PROJECT_A, new AdrCode("ADR-2"), null).isEmpty());
        // The skipped decision still owns its number: findAllCodes reads dcterms:identifier without
        // joining a single field toAdr could reject, which is what keeps adr_add working for a
        // project holding such a record (kogn-io/arknet#359).
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new AdrCode("ADR-2")));
        // ... and the same identity still resolves to that code, which is what adr_list's successor
        // fallback and adr_get's relatedTo display are built on.
        assertEquals(Map.of(unresolvable, new AdrCode("ADR-2")),
                repository.findCodesByIds(PROJECT_A, List.of(unresolvable)));
    }

    /**
     * The bi-implication kogn-io/arknet#357 introduces (status SUPERSEDED if and only if
     * {@code supersededBy} is set) is enforced at write time only for the direction
     * {@code architecture-shapes.ttl}'s {@code ashapes:ADR-supersededByRequiresSupersededStatus}
     * checks ({@code supersededBy} set implies status SUPERSEDED - {@link Adr}'s compact constructor
     * enforces the full bi-implication, this write gate only half of it, kogn-io/arknet#359). This
     * test's direction (SUPERSEDED without the edge) reaches only read-time tolerance for data the
     * gate never validated - a store-first record whose {@code adrStatus} and {@code supersededBy}
     * disagree is skipped with a {@code WARN}, the same graceful degradation an unresolvable status
     * gets, rather than crashing the whole read the way an unguarded domain constructor call would.
     */
    @Test
    void findAllSkipsADecisionWhoseStoreFirstStatusContradictsItsSupersededByEdge() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");
        AdrId inconsistent = freshId();
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + inconsistent.value().value() + "> a <"
                + ArkarchVocabulary.ADR_TYPE + "> ; <http://purl.org/dc/terms/identifier> \"ADR-2\" ; "
                + "<https://w3id.org/arknet/core#name> \"Inconsistent\" ; <" + ArkarchVocabulary.ADR_STATUS
                + "> <" + ArkarchVocabulary.ACCEPTED + "> ; <" + ArkarchVocabulary.ADR_CONTEXT
                + "> \"Enough context text\" ; <" + ArkarchVocabulary.ADR_DECISION
                + "> \"Enough decision text\" ; <" + ArkarchVocabulary.SUPERSEDED_BY + "> <"
                + inconsistent.value().value() + "> } }");

        List<Adr> all = repository.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals(new AdrCode("ADR-1"), all.get(0).code());
        assertTrue(repository.findByCode(PROJECT_A, new AdrCode("ADR-2"), null).isEmpty());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new AdrCode("ADR-2")));
        assertEquals(Map.of(inconsistent, new AdrCode("ADR-2")),
                repository.findCodesByIds(PROJECT_A, List.of(inconsistent)));
    }

    /**
     * The third rejection {@link Adr}'s compact constructor raises - {@code supersededBy} naming the
     * decision itself - reaches the read path too, and unlike the other two no shape stands in its
     * way at write time either ({@code ashapes:ADR-supersededBy} constrains count, node kind and
     * class, never disjointness with the subject). Paired with {@code arkarch:adrStatus Superseded}
     * it even satisfies the bi-implication, so this is the one self-reference that would otherwise
     * reach the constructor and take down every read of the project (kogn-io/arknet#359).
     */
    @Test
    void findAllSkipsADecisionWhoseSupersededByEdgePointsAtItself() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");
        AdrId selfSuperseding = freshId();
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + selfSuperseding.value().value() + "> a <"
                + ArkarchVocabulary.ADR_TYPE + "> ; <http://purl.org/dc/terms/identifier> \"ADR-2\" ; "
                + "<https://w3id.org/arknet/core#name> \"Self-superseding\" ; <" + ArkarchVocabulary.ADR_STATUS
                + "> <" + ArkarchVocabulary.SUPERSEDED + "> ; <" + ArkarchVocabulary.ADR_CONTEXT
                + "> \"Enough context text\" ; <" + ArkarchVocabulary.ADR_DECISION
                + "> \"Enough decision text\" ; <" + ArkarchVocabulary.SUPERSEDED_BY + "> <"
                + selfSuperseding.value().value() + "> } }");

        List<Adr> all = repository.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals(new AdrCode("ADR-1"), all.get(0).code());
        assertTrue(repository.findByCode(PROJECT_A, new AdrCode("ADR-2"), null).isEmpty());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new AdrCode("ADR-2")));
    }

    /**
     * The same self-reference through {@code arkarch:relatedTo} - rejected by {@link Adr}'s compact
     * constructor since before kogn-io/arknet#359, and just as uncaught by
     * {@code ashapes:ADR-relatedTo} - is skipped the same way rather than crashing the listing.
     */
    @Test
    void findAllSkipsADecisionRelatedToItself() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");
        AdrId selfRelated = freshId();
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + selfRelated.value().value() + "> a <"
                + ArkarchVocabulary.ADR_TYPE + "> ; <http://purl.org/dc/terms/identifier> \"ADR-2\" ; "
                + "<https://w3id.org/arknet/core#name> \"Self-related\" ; <" + ArkarchVocabulary.ADR_STATUS
                + "> <" + ArkarchVocabulary.ACCEPTED + "> ; <" + ArkarchVocabulary.ADR_CONTEXT
                + "> \"Enough context text\" ; <" + ArkarchVocabulary.ADR_DECISION
                + "> \"Enough decision text\" ; <" + ArkarchVocabulary.RELATED_TO + "> <"
                + selfRelated.value().value() + "> } }");

        List<Adr> all = repository.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals(new AdrCode("ADR-1"), all.get(0).code());
        assertTrue(repository.findByCode(PROJECT_A, new AdrCode("ADR-2"), null).isEmpty());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new AdrCode("ADR-2")));
    }

    /**
     * What {@link AdrRepository#findAllCodes} exists for (kogn-io/arknet#359), pinned against the
     * real store: the query joins the type and {@code dcterms:identifier} and nothing else, so a
     * code stays taken even by a subject {@link AdrRepository#findAll} cannot materialise at all.
     * Deliberately the leanest such subject there is - no name, no status, no context, no decision -
     * which every mandatory join of the listing read already drops; any field joined into this query
     * later (the tempting "the code alone is not enough") would fail here rather than silently hand
     * the number out twice.
     */
    @Test
    void findAllCodesKeepsTheCodeOfASubjectFindAllCannotMaterialiseAtAll() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");
        AdrId bare = freshId();
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + bare.value().value() + "> a <"
                + ArkarchVocabulary.ADR_TYPE + "> ; <http://purl.org/dc/terms/identifier> \"ADR-2\" } }");

        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new AdrCode("ADR-2")));
        assertEquals(Map.of(bare, new AdrCode("ADR-2")),
                repository.findCodesByIds(PROJECT_A, List.of(bare)));
        assertTrue(repository.findByCode(PROJECT_A, new AdrCode("ADR-2"), null).isEmpty());
    }

    /**
     * Node kind is the other half of the same promise (kogn-io/arknet#360): the counting query
     * carries no {@code FILTER(isIRI(?s))}, so {@code ADR-2} counts even when a blank node holds
     * it. That is not laxity but agreement with the writer - {@code WriteFunnel#create} decides
     * code uniqueness with {@code tx.contains(graph, null, dcterms:identifier, code)}, a wildcard
     * subject that never asks for a node kind, and would turn down an {@code adr_add} for
     * {@code ADR-2}. Were the counter to look past that code, the {@code CodeAssignment} retry
     * would recompute the identical rejected number every time and the bounded context could never
     * add a decision again. Restoring the filter here - "make it mirror the other reads" - fails
     * this test.
     */
    @Test
    void findAllCodesCountsACodeHeldByABlankNodeSubject() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { [] a <" + ArkarchVocabulary.ADR_TYPE
                + "> ; <http://purl.org/dc/terms/identifier> \"ADR-2\" } }");

        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new AdrCode("ADR-2")),
                repository.findAllCodes(PROJECT_A).toString());
    }

    @Test
    void compareAndUpdateTransitionsToRejected() {
        Adr original = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null);
        repository.create(PROJECT_A, original, "en");

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.reject(DECIDED_ON), null, null, null, Map.of(), Map.of(), null);

        assertEquals(AdrStatus.REJECTED,
                repository.findByCode(PROJECT_A, original.code(), null).orElseThrow().status());
    }

    @Test
    void compareAndUpdateTransitionsToDeprecated() {
        Adr original = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.ACCEPTED, null, null, null,
                List.of(), List.of(), null);
        repository.create(PROJECT_A, original, "en");

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.deprecate(), null, null, null, Map.of(), Map.of(), null);

        assertEquals(AdrStatus.DEPRECATED,
                repository.findByCode(PROJECT_A, original.code(), null).orElseThrow().status());
    }

    @Test
    void findAllReturnsEveryStoredAdr() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");
        repository.create(PROJECT_A, adr(new AdrCode("ADR-2")), "en");

        assertEquals(2, repository.findAll(PROJECT_A, null).size());
    }

    @Test
    void findByCodeIsEmptyForUnknownCode() {
        assertTrue(repository.findByCode(PROJECT_A, new AdrCode("ADR-99"), null).isEmpty());
    }

    @Test
    void createRejectsAnAlreadyExistingIdentity() {
        AdrId id = freshId();
        repository.create(PROJECT_A,
                adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null, List.of(), List.of(),
                        null), "en");

        Adr sameIdentity = adr(id, new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null);

        assertThrows(ResourceAlreadyExistsException.class, () -> repository.create(PROJECT_A, sameIdentity, "en"));
    }

    @Test
    void createRejectsADuplicateBusinessCodeOnADifferentIdentity() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");

        Adr sameCode = adr(new AdrCode("ADR-1"));

        assertThrows(DuplicateAdrCodeException.class, () -> repository.create(PROJECT_A, sameCode, "en"));
    }

    @Test
    void compareAndUpdateReplacesAnExistingAdr() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null);
        repository.create(PROJECT_A, original, "en");

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.accept(DECIDED_ON), null, null, null, Map.of(), Map.of(), null);

        assertEquals(AdrStatus.ACCEPTED,
                repository.findByCode(PROJECT_A, original.code(), null).orElseThrow().status());
    }

    @Test
    void compareAndUpdateKeepsARecordCarryingSeveralOptionsWithOneChosenWritable() {
        // Dry-run finding: a decision created with more than one considered option, exactly one of
        // them CHOSEN, could not be corrected afterwards - every compareAndUpdate was refused by
        // ashapes:ADR-consideredOption-atMostOneChosen although the stored state carries a single
        // arkarch:Chosen. One option alone never reproduced it.
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, List.of(),
                List.of(new ConsideredOption(1, "Rejected one", "Only here to count.", OptionOutcome.REJECTED),
                        new ConsideredOption(2, "Chosen one", "Only here to count.", OptionOutcome.CHOSEN)),
                null, List.of(), List.of(), null);
        repository.create(PROJECT_A, original, "de");

        var current = repository.findCurrentByCode(PROJECT_A, original.code()).orElseThrow();
        repository.compareAndUpdate(PROJECT_A, current.head(), current.value(), null, null, null,
                Map.of(), Map.of(), "de");

        assertEquals(2, repository.findByCode(PROJECT_A, original.code(), null).orElseThrow()
                .consideredOptions().size());
    }

    @Test
    void compareAndUpdateRejectsAMissingIdentity() {
        Adr missing = adr(new AdrCode("ADR-1"));

        assertThrows(AdrNotFoundException.class, () -> repository.compareAndUpdate(PROJECT_A, null, missing, null, null, null, Map.of(), Map.of(), null));
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

    // ---- ashapes:ADR-supersededBy + ashapes:ADR-supersededByRequiresSupersededStatus, driven ----
    // ---- directly against the real gate ----

    /**
     * {@code sh:maxCount 1} on {@code ashapes:ADR-supersededBy}, isolated from the one-directional
     * implication shape: both successors are plain valid ADRs, and the subject's own status is
     * SUPERSEDED with (at least) one edge present, so
     * {@code ashapes:ADR-supersededByRequiresSupersededStatus} conforms (its second disjunct only
     * asks for status {@code Superseded}, which holds) and only the standalone {@code maxCount 1}
     * property shape fires. The spike this issue verified with (kogn-io/arknet#357) left this case
     * vacuous - conflated with an implication violation because it paired two {@code supersededBy}
     * values with an {@code Accepted} status - this test does not.
     */
    @Test
    void writeRejectsMoreThanOneSupersededByEdgeIsolatedFromTheBiImplication() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI successorA = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI successorB = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.SUPERSEDED);
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.SUPERSEDED_BY), successorA);
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.SUPERSEDED_BY), successorB);
        Graph assertedContext = rdf.createGraph();
        merge(assertedContext, minimalCandidate(rdf, successorA, "ADR-2", ArkarchVocabulary.ACCEPTED));
        merge(assertedContext, minimalCandidate(rdf, successorB, "ADR-3", ArkarchVocabulary.ACCEPTED));

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate, assertedContext));
    }

    /** {@code ashapes:ADR-supersededBy} carries {@code sh:class}, exactly like {@code ADR-relatedTo}. */
    @Test
    void writeRejectsASupersededByEdgeToSomethingThatIsNotAnAdr() {
        AdrId dangling = freshId();
        Adr related = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.SUPERSEDED, null, null, null,
                List.of(), List.of(), dangling);

        assertThrows(WriteConstraintViolationException.class, () -> repository.create(PROJECT_A, related, "en"));

        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
    }

    /**
     * The one direction {@code ashapes:ADR-supersededByRequiresSupersededStatus} actually enforces,
     * driven directly against the real RDF4J SHACL engine this project's write gate runs (the spike
     * kogn-io/arknet#357 verified it with, made permanent here, narrowed to one direction by
     * kogn-io/arknet#359): {@code supersededBy} set forces status {@code Superseded}, in both
     * possible statuses.
     */
    @Test
    void gateConformsWhenSupersededByIsSetAndStatusIsSuperseded() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI successor = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.SUPERSEDED);
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.SUPERSEDED_BY), successor);
        Graph assertedContext = minimalCandidate(rdf, successor, "ADR-2", ArkarchVocabulary.ACCEPTED);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        gate.enforce(candidate, assertedContext);
    }

    @Test
    void gateViolatesWhenSupersededByIsSetButStatusIsNotSuperseded() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI successor = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.ACCEPTED);
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.SUPERSEDED_BY), successor);
        Graph assertedContext = minimalCandidate(rdf, successor, "ADR-2", ArkarchVocabulary.ACCEPTED);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate, assertedContext));
    }

    /**
     * The converse direction ("status {@code Superseded} implies {@code supersededBy} set")
     * deliberately no longer conforms-or-violates at this shape at all (kogn-io/arknet#359):
     * {@code ashapes:ADR-supersededByRequiresSupersededStatus} checks only the direction pinned by
     * {@link #gateViolatesWhenSupersededByIsSetButStatusIsNotSuperseded} above. A node shape checking
     * this converse too would also fire on the validation-only {@code assertedContext} copies
     * {@code KognioRdfAdrRepository#crossReferenceAssertedContext} builds for a {@code relatedTo}
     * peer or a {@code supersededBy} target, which never carry the edge at all - a peer that is
     * itself {@code Superseded} would look exactly like this candidate and permanently block every
     * write naming it (measured against RDF4J 6.0.0, kogn-io/arknet#359). This half of the
     * bi-implication is enforced purely in the domain: {@link Adr}'s compact constructor refuses to
     * construct this very state, and {@code KognioRdfAdrRepository}'s read path skips a store-first
     * record that still manages to reach it. Renamed from
     * {@code gateViolatesWhenStatusIsSupersededButSupersededByIsNotSet}, which pinned the pre-#359
     * (buggy) behaviour this test now pins the fix for.
     */
    @Test
    void gateConformsWhenStatusIsSupersededButSupersededByIsNotSet() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.SUPERSEDED);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        gate.enforce(candidate);
    }

    @Test
    void gateConformsWhenNeitherSupersededByNorSupersededStatusIsSet() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.ACCEPTED);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        gate.enforce(candidate);
    }

    // ---- ashapes:ADR-consideredOption-atMostOneChosen (kogn-io/arknet#357), driven directly
    // against the real gate - the SHACL half of the "at most one Chosen" invariant Adr's own
    // compact constructor enforces a second time (AdrTest#rejectsMoreThanOneChosenConsideredOption).

    /**
     * The {@code sh:sparql}-based {@code ashapes:ADR-consideredOption-atMostOneChosen} (originally a
     * {@code sh:qualifiedValueShape}/{@code sh:qualifiedMaxCount 1} form, replaced by
     * kogn-io/arknet#376 - see the shape's own comment in {@code architecture-shapes.ttl} for why) -
     * the spike this issue verified against the real RDF4J SHACL engine (arknet-wt-spike-shacl), made
     * permanent here.
     */
    @Test
    void writeRejectsMoreThanOneChosenConsideredOption() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.PROPOSED);
        addConsideredOption(rdf, candidate, subject, 1, ArkarchVocabulary.CHOSEN);
        addConsideredOption(rdf, candidate, subject, 2, ArkarchVocabulary.CHOSEN);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    @Test
    void gateConformsWithExactlyOneChosenConsideredOptionAmongSeveral() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.PROPOSED);
        addConsideredOption(rdf, candidate, subject, 1, ArkarchVocabulary.CHOSEN);
        addConsideredOption(rdf, candidate, subject, 2, ArkarchVocabulary.OPTION_REJECTED);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        gate.enforce(candidate);
    }

    @Test
    void gateConformsWithZeroChosenConsideredOptions() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.PROPOSED);
        addConsideredOption(rdf, candidate, subject, 1, ArkarchVocabulary.OPTION_REJECTED);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        gate.enforce(candidate);
    }

    /**
     * kogn-io/arknet#376: RDF4J 6.x's ShaclSail measurably misfires the (pre-fix)
     * {@code sh:qualifiedValueShape}/{@code sh:qualifiedMaxCount 1} form of {@code
     * ashapes:ADR-consideredOption-atMostOneChosen} whenever the validated data graph carries a
     * <em>second</em> {@code arkarch:ArchitectureDecisionRecord} focus node beside the one actually
     * being written - exactly the shape of a {@code relatedTo}/{@code supersededBy} peer's
     * validation-only {@code assertedContext} copy ({@link KognioRdfAdrRepository
     * #crossReferenceAssertedContext}), even though the peer carries zero considered options of its
     * own and the candidate itself never carries more than one {@code Chosen}. Reproduced roughly
     * two thirds of the time pre-fix (driven directly against the real gate, exactly like
     * {@link #gateConformsWithExactlyOneChosenConsideredOptionAmongSeveral} plus a relatedTo peer);
     * {@code @RepeatedTest} rather than a single run because the flake would otherwise pass by luck
     * about a third of the time even without the fix.
     */
    @RepeatedTest(20)
    void gateConformsWithExactlyOneChosenAmongSeveralWhenARelatedToPeerIsAlsoAFocusNode() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI peer = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.PROPOSED);
        addConsideredOption(rdf, candidate, subject, 1, ArkarchVocabulary.OPTION_REJECTED);
        addConsideredOption(rdf, candidate, subject, 2, ArkarchVocabulary.CHOSEN);
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.RELATED_TO), peer);
        Graph assertedContext = minimalCandidate(rdf, peer, "ADR-2", ArkarchVocabulary.PROPOSED);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        gate.enforce(candidate, assertedContext);
    }

    /** Adds one well-formed {@code arkarch:ConsideredOption} child, satisfying {@code ashapes:ConsideredOptionShape}. */
    private static void addConsideredOption(RDF rdf, Graph candidate, IRI subject, int position, String outcomeIri) {
        IRI option = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.CONSIDERED_OPTION), option);
        candidate.add(option, VocabRdf.TYPE, rdf.createIRI(ArkarchVocabulary.CONSIDERED_OPTION_TYPE_CLASS));
        candidate.add(option, rdf.createIRI("https://w3id.org/arknet/core#position"),
                rdf.createLiteral(Integer.toString(position), io.kogn.rdf.terms.vocab.VocabXsd.INTEGER));
        candidate.add(option, rdf.createIRI("https://w3id.org/arknet/core#name"), rdf.createLiteral("Option"));
        candidate.add(option, rdf.createIRI(ArkarchVocabulary.OPTION_RATIONALE), rdf.createLiteral("Rationale text"));
        candidate.add(option, rdf.createIRI(ArkarchVocabulary.OPTION_OUTCOME_PROPERTY), rdf.createIRI(outcomeIri));
    }

    /**
     * S2 from the kogn-io/arknet#359 review: a {@code relatedTo} peer that is itself
     * {@code Superseded} must not block a write naming it. Direct-gate reproduction of the P0 bug:
     * the peer's assertedContext copy never carries {@code supersededBy} (only the five mandatory
     * scalar fields plus type are copied, see
     * {@code KognioRdfAdrRepository#crossReferenceAssertedContext}), so under the pre-#359
     * bi-implication shape this candidate would violate even though nothing about the candidate
     * itself is wrong - the peer's own successor lives in an entirely different write.
     */
    @Test
    void gateConformsWhenARelatedToPeerIsItselfSuperseded() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI peer = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, subject, "ADR-1", ArkarchVocabulary.ACCEPTED);
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.RELATED_TO), peer);
        // The peer's own assertedContext copy: Superseded, but - like every such copy - without the
        // supersededBy edge that made it so (crossReferenceAssertedContext never copies that field).
        Graph assertedContext = minimalCandidate(rdf, peer, "ADR-2", ArkarchVocabulary.SUPERSEDED);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        gate.enforce(candidate, assertedContext);
    }

    /**
     * S3 from the kogn-io/arknet#359 review: a supersession chain, A supersededBy B and B itself
     * Superseded (by some C outside this candidate graph entirely) - the exact shape of the
     * permanently-blocked-decision bug. B's assertedContext copy carries the same "Superseded without
     * the edge" appearance S2 does, for the same reason.
     */
    @Test
    void gateConformsWhenTheSupersedingDecisionIsItselfSuperseded() {
        RDF rdf = new SimpleRdf();
        IRI a = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI b = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = minimalCandidate(rdf, a, "ADR-1", ArkarchVocabulary.SUPERSEDED);
        candidate.add(a, rdf.createIRI(ArkarchVocabulary.SUPERSEDED_BY), b);
        Graph assertedContext = minimalCandidate(rdf, b, "ADR-2", ArkarchVocabulary.SUPERSEDED);

        ShaclWriteGate gate = KognioRdfAdrRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        gate.enforce(candidate, assertedContext);
    }

    // ---- kogn-io/arknet#359 P0 regression: the gap left by the two adapter tests that used to ----
    // ---- leave a Superseded successor/peer untested, driven through the full create/compareAndUpdate ----
    // ---- write path (real gate, real crossReferenceAssertedContext) rather than the gate directly ----

    /**
     * The supersession-chain half of the P0 bug: {@code adr_supersede(B, A)} then
     * {@code adr_supersede(C, B)} both go through, and a <em>further</em> write on {@code A} - here,
     * completing an {@code addressesRequirement} edge, always correctable regardless of status - must
     * still go through too. Before kogn-io/arknet#359's fix, {@code A}'s own write asserted {@code B}
     * as its {@code supersededBy} target; {@code crossReferenceAssertedContext}'s copy of {@code B}
     * never carries {@code B}'s own {@code supersededBy} edge (to {@code C}), so {@code B} looked like
     * "Superseded without the edge" to the old bi-implication shape and every further write on
     * {@code A} was refused - permanently, since nothing can un-supersede {@code B} back to
     * {@code Accepted} either.
     */
    @Test
    void furtherWriteOnADecisionSucceedsAfterItsSuccessorIsItselfSuperseded() {
        Adr a = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, a, "en");
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(a.code()), a.accept(DECIDED_ON), "en", "en", "en", Map.of(),
                Map.of(), null);
        Adr b = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, b, "en");
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(b.code()), b.accept(DECIDED_ON), "en", "en", "en", Map.of(),
                Map.of(), null);
        Adr c = adr(new AdrCode("ADR-3"));
        repository.create(PROJECT_A, c, "en");
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(c.code()), c.accept(DECIDED_ON), "en", "en", "en", Map.of(),
                Map.of(), null);

        // adr_supersede(B, A): A becomes SUPERSEDED, supersededBy = B.
        Adr acceptedA = repository.findByCode(PROJECT_A, a.code(), null).orElseThrow();
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(a.code()), acceptedA.supersededBy(b.id()), "en", "en",
                "en", Map.of(), Map.of(), null);
        // adr_supersede(C, B): B becomes SUPERSEDED, supersededBy = C - B is now itself superseded.
        Adr acceptedB = repository.findByCode(PROJECT_A, b.code(), null).orElseThrow();
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(b.code()), acceptedB.supersededBy(c.id()), "en", "en",
                "en", Map.of(), Map.of(), null);

        // A further write on A, referencing B (Superseded) as its own successor.
        Adr supersededA = repository.findByCode(PROJECT_A, a.code(), null).orElseThrow();
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        Adr correctedA = supersededA.reviseReferences(List.of(requirement), List.of(), List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(a.code()), correctedA, "en", "en", "en", Map.of(),
                Map.of(), null);

        assertEquals(List.of(requirement),
                repository.findByCode(PROJECT_A, a.code(), null).orElseThrow().addressesRequirements());
    }

    /**
     * The {@code relatedTo}-peer half of the P0 bug: once a {@code relatedTo} peer is itself
     * superseded, every further write on the record naming it must still go through - before the fix,
     * the peer's assertedContext copy looked like "Superseded without the edge" the same way a
     * superseded successor did, and refused every subsequent write on {@code referencing}.
     */
    @Test
    void writeOnADecisionSucceedsWhenARelatedToPeerIsItselfSuperseded() {
        Adr peer = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, peer, "en");
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(peer.code()), peer.accept(DECIDED_ON), "en", "en", "en",
                Map.of(), Map.of(), null);
        Adr successor = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, successor, "en");
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(successor.code()), successor.accept(DECIDED_ON), "en", "en",
                "en", Map.of(), Map.of(), null);
        Adr referencing = adr(freshId(), new AdrCode("ADR-3"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(peer.id()));
        repository.create(PROJECT_A, referencing, "en");

        // peer becomes SUPERSEDED only after referencing already names it via relatedTo.
        Adr acceptedPeer = repository.findByCode(PROJECT_A, peer.code(), null).orElseThrow();
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(peer.code()), acceptedPeer.supersededBy(successor.id()),
                "en", "en", "en", Map.of(), Map.of(), null);

        // A further write on referencing (still PROPOSED, so its text is correctable too) must not be
        // blocked by peer's own, unrelated Superseded status.
        Adr currentReferencing = repository.findByCode(PROJECT_A, referencing.code(), null).orElseThrow();
        Adr corrected = currentReferencing.reviseText(currentReferencing.name(), "Updated context here",
                currentReferencing.decision(), false);
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(referencing.code()), corrected, "en", "en", "en",
                Map.of(), Map.of(), null);

        assertEquals("Updated context here",
                repository.findByCode(PROJECT_A, referencing.code(), null).orElseThrow().context());
    }

    /**
     * The other direction of the same {@code relatedTo}-peer bug: {@code adr_add} of a brand-new
     * decision that names an already-superseded peer must go through on its very first write, not
     * only on a later correction.
     */
    @Test
    void addSucceedsWhenNamingAnAlreadySupersededDecisionInRelatedTo() {
        Adr peer = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, peer, "en");
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(peer.code()), peer.accept(DECIDED_ON), "en", "en", "en",
                Map.of(), Map.of(), null);
        Adr successor = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, successor, "en");
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(successor.code()), successor.accept(DECIDED_ON), "en", "en",
                "en", Map.of(), Map.of(), null);
        Adr acceptedPeer = repository.findByCode(PROJECT_A, peer.code(), null).orElseThrow();
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(peer.code()), acceptedPeer.supersededBy(successor.id()),
                "en", "en", "en", Map.of(), Map.of(), null);

        Adr newReferencer = adr(freshId(), new AdrCode("ADR-3"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(peer.id()));
        repository.create(PROJECT_A, newReferencer, "en");

        assertEquals(List.of(peer.id()),
                repository.findByCode(PROJECT_A, newReferencer.code(), null).orElseThrow().relatedTo());
    }

    /**
     * Builds the six triples every ADR candidate needs to pass {@code ashapes:ADRShape}'s
     * {@code sh:Violation} property shapes (type, identifier, name, status, context, decision) -
     * shared by the direct-gate tests above so each states only what it varies.
     */
    private static Graph minimalCandidate(RDF rdf, IRI subject, String code, String statusIri) {
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(ArkarchVocabulary.ADR_TYPE));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/identifier"), rdf.createLiteral(code));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/core#name"), rdf.createLiteral("A decision"));
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.ADR_STATUS), rdf.createIRI(statusIri));
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.ADR_CONTEXT), rdf.createLiteral("Enough context text"));
        candidate.add(subject, rdf.createIRI(ArkarchVocabulary.ADR_DECISION), rdf.createLiteral("Enough decision text"));
        return candidate;
    }

    /** Copies every triple of {@code source} into {@code target} - {@link Graph} has no {@code addAll}. */
    private static void merge(Graph target, Graph source) {
        source.stream().forEach(target::add);
    }

    /**
     * {@code ashapes:ADR-consequences}/{@code ADR-alternatives} are {@code sh:Warning}, not
     * {@code sh:Violation}: a decision recorded while it is still being argued has neither yet, and
     * the gate collects only violations - so the write passes.
     */
    @Test
    void anAdrWithoutConsequencesOrAlternativesPassesTheGate() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");

        assertTrue(repository.findByCode(PROJECT_A, new AdrCode("ADR-1"), null).isPresent());
    }

    @Test
    void writePersistsAllFiveRelationsAndReadsThemBack() {
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        BoundedContextRef contextRef = new BoundedContextRef(ResourceId.of("https://w3id.org/arknet/id/bc-1"));
        TermRef termRef = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));
        Adr successor = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, successor, "en");
        Adr created = new Adr(freshId(), new AdrCode("ADR-2"), "Use an embedded triple store",
                AdrStatus.SUPERSEDED,
                "The model has to live somewhere a single-user client can reach without a server.",
                "Use kognio-rdf as the embedded RDF substrate behind an out-port.",
                null, null, null, List.of(requirement), List.of(contextRef), List.of(termRef), successor.id(),
                List.of());

        repository.create(PROJECT_A, created, "en");
        Adr found = repository.findByCode(PROJECT_A, new AdrCode("ADR-2"), null).orElseThrow();

        assertEquals(List.of(requirement), found.addressesRequirements());
        assertEquals(List.of(contextRef), found.affectsContexts());
        assertEquals(List.of(termRef), found.usesTerms());
        assertEquals(successor.id(), found.supersededBy());
    }

    /**
     * {@code usesTerm} (kogn-io/arknet#393) round-trips through {@code compareAndUpdate} exactly
     * like {@code addressesRequirement}/{@code affectsContext} - mirrors
     * {@link #compareAndUpdatePreservesTheSupersededByEdgeWhileExtendingAnotherRelation}, for the
     * fourth reference list rather than the first.
     */
    @Test
    void compareAndUpdateAddsAndReadsBackAUsesTermEdge() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created, "en");
        TermRef termRef = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));

        Adr updated = created.reviseReferences(List.of(), List.of(), List.of(termRef), List.of());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(created.code()), updated, null, null, null, Map.of(),
                Map.of(), null);

        assertEquals(List.of(termRef),
                repository.findByCode(PROJECT_A, created.code(), null).orElseThrow().usesTerms());
    }

    /**
     * The forward edge (kogn-io/arknet#357: written on the <em>superseded</em> decision) is
     * asserted, its {@code owl:inverseOf} partner {@code arkarch:supersedes} - the pre-#357 shape -
     * is never written by this adapter: nothing here materialises both directions of an
     * {@code owl:inverseOf} pair as physical triples. A reader that wants the forward direction from
     * the superseding decision's side gets it from {@link AdrRepository#findSupersedingCodes}, which
     * reads {@code supersededBy} directly rather than a stored {@code arkarch:supersedes}.
     */
    @Test
    void writeAssertsOnlyTheSupersededByTripleNeverTheLegacySupersedesShape() {
        Adr superseding = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, superseding, "en");
        AdrId supersededId = freshId();
        Adr superseded = adr(supersededId, new AdrCode("ADR-1"), AdrStatus.SUPERSEDED, null, null, null,
                List.of(), List.of(), superseding.id());
        repository.create(PROJECT_A, superseded, "en");

        String legacyAsk = "ASK { GRAPH <" + ADR_GRAPH + "> { ?s <" + ArkarchVocabulary.SUPERSEDES + "> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask(legacyAsk),
                    "arkarch:supersedes (pre-#357) must never be written by this adapter");
        }
        assertEquals(List.of(new AdrCode("ADR-2")), repository.findSupersedingCodes(PROJECT_A, supersededId));
    }

    @Test
    void findSupersedingCodesIsEmptyForANeverSupersededAdr() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created, "en");

        assertEquals(List.of(), repository.findSupersedingCodes(PROJECT_A, created.id()));
    }

    /**
     * Since kogn-io/arknet#357 a live decision has at most one successor - {@code supersededBy}
     * carries {@code sh:maxCount 1}, and it lives on the superseded decision's own single field, so
     * more than one entry can only come from the pre-#357 legacy shape (several decisions each still
     * asserting their own {@code arkarch:supersedes} at the same target, store-first data this
     * adapter no longer writes but still reads back). Codes must sort by their parsed running
     * number, not by {@link String}'s natural (lexicographic) order - which would put
     * {@code ADR-10}/{@code ADR-11} before {@code ADR-2}/{@code ADR-3}.
     */
    @Test
    void findSupersedingCodesSortsLegacyEntriesByRunningNumberNotLexicographically() {
        Adr superseded = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, superseded, "en");

        for (String code : List.of("ADR-11", "ADR-2", "ADR-10", "ADR-3")) {
            AdrId legacyId = freshId();
            repository.create(PROJECT_A, adr(legacyId, new AdrCode(code), AdrStatus.PROPOSED, null, null, null,
                    List.of(), List.of(), null), "en");
            insertLegacySupersedes(legacyId, superseded.id());
        }

        assertEquals(
                List.of(new AdrCode("ADR-2"), new AdrCode("ADR-3"), new AdrCode("ADR-10"), new AdrCode("ADR-11")),
                repository.findSupersedingCodes(PROJECT_A, superseded.id()));
    }

    /**
     * Regression for #187: two distinct, non-standard store-first codes that both parse to
     * the same running number (unparseable, hence 0 - see
     * {@code CodeCounter#runningNumber}) must not collide in the internal
     * {@link java.util.TreeSet}. A comparator ordering only by parsed running number is inconsistent
     * with {@link AdrCode#equals}, and a {@link java.util.TreeSet} dedupes by comparator, not by
     * {@code equals} - so without a tie-breaker one of the two codes would be silently dropped.
     */
    @Test
    void findSupersedingCodesKeepsBothLegacyEntriesWhenTheirRunningNumbersCollide() {
        Adr superseded = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, superseded, "en");

        for (String code : List.of("ADR-1x", "ADR-2y")) {
            AdrId legacyId = freshId();
            repository.create(PROJECT_A, adr(legacyId, new AdrCode(code), AdrStatus.PROPOSED, null, null, null,
                    List.of(), List.of(), null), "en");
            insertLegacySupersedes(legacyId, superseded.id());
        }

        assertEquals(List.of(new AdrCode("ADR-1x"), new AdrCode("ADR-2y")),
                repository.findSupersedingCodes(PROJECT_A, superseded.id()));
    }

    /**
     * {@link AdrRepository#findSupersedingCodes} unions the current-model forward read (the
     * superseded decision's own {@code supersededBy} field) with a reverse read of the pre-#357
     * legacy {@code arkarch:supersedes} shape - the "Altbestands-Auffang" this issue introduces
     * (kogn-io/arknet#357): neither source alone would surface a decision superseded by both a
     * current-model successor and a still-present legacy pointer.
     */
    @Test
    void findSupersedingCodesUnionsTheCurrentModelEdgeWithALegacyOne() {
        Adr currentSuccessor = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, currentSuccessor, "en");
        Adr superseded = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.SUPERSEDED, null, null, null,
                List.of(), List.of(), currentSuccessor.id());
        repository.create(PROJECT_A, superseded, "en");
        AdrId legacySuccessorId = freshId();
        repository.create(PROJECT_A, adr(legacySuccessorId, new AdrCode("ADR-3"), AdrStatus.PROPOSED,
                null, null, null, List.of(), List.of(), null), "en");
        insertLegacySupersedes(legacySuccessorId, superseded.id());

        assertEquals(List.of(new AdrCode("ADR-2"), new AdrCode("ADR-3")),
                repository.findSupersedingCodes(PROJECT_A, superseded.id()));
    }

    /**
     * The mirror of {@link #findSupersedingCodesUnionsTheCurrentModelEdgeWithALegacyOne}: which
     * decisions {@code supersedingId} supersedes, unioning a reverse read of every decision naming
     * it in their own current-model {@code supersededBy} field with {@code supersedingId}'s own
     * pre-#357 legacy {@code arkarch:supersedes} triple.
     */
    @Test
    void findSupersededCodesUnionsTheCurrentModelEdgeWithALegacyOne() {
        Adr supersedingAdr = adr(new AdrCode("ADR-3"));
        repository.create(PROJECT_A, supersedingAdr, "en");
        Adr currentPredecessor = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.SUPERSEDED, null, null, null,
                List.of(), List.of(), supersedingAdr.id());
        repository.create(PROJECT_A, currentPredecessor, "en");
        Adr legacyPredecessor = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, legacyPredecessor, "en");
        insertLegacySupersedes(supersedingAdr.id(), legacyPredecessor.id());

        assertEquals(List.of(new AdrCode("ADR-1"), new AdrCode("ADR-2")),
                repository.findSupersededCodes(PROJECT_A, supersedingAdr.id()));
    }

    @Test
    void findSupersededCodesIsEmptyForADecisionThatSupersedesNothing() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created, "en");

        assertEquals(List.of(), repository.findSupersededCodes(PROJECT_A, created.id()));
    }

    /**
     * {@link AdrRepository#findLegacySupersedesEdges} is the bulk read {@code AdrService#list}
     * relies on instead of a reverse query per decision - one call for the whole project.
     */
    @Test
    void findLegacySupersedesEdgesReadsEveryPreIssue357Pair() {
        Adr a = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, a, "en");
        Adr b = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, b, "en");
        Adr c = adr(new AdrCode("ADR-3"));
        repository.create(PROJECT_A, c, "en");
        insertLegacySupersedes(b.id(), a.id());
        insertLegacySupersedes(c.id(), b.id());

        assertEquals(
                List.of(new AdrRepository.LegacySupersession(new AdrCode("ADR-2"), new AdrCode("ADR-1")),
                        new AdrRepository.LegacySupersession(new AdrCode("ADR-3"), new AdrCode("ADR-2"))),
                repository.findLegacySupersedesEdges(PROJECT_A).stream()
                        .sorted((left, right) -> left.supersedingCode().value().compareTo(right.supersedingCode().value()))
                        .toList());
    }

    @Test
    void findLegacySupersedesEdgesIsEmptyWhenNothingUsesTheLegacyShape() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");

        assertEquals(List.of(), repository.findLegacySupersedesEdges(PROJECT_A));
    }

    @Test
    void findCodesByIdsResolvesOnlyWhatExists() {
        Adr first = adr(new AdrCode("ADR-1"));
        Adr second = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, first, "en");
        repository.create(PROJECT_A, second, "en");

        Map<AdrId, AdrCode> codes =
                repository.findCodesByIds(PROJECT_A, List.of(first.id(), second.id(), freshId()));

        assertEquals(Map.of(first.id(), first.code(), second.id(), second.code()), codes);
    }

    @Test
    void findCodesByIdsOfAnEmptyCollectionQueriesNothing() {
        assertEquals(Map.of(), repository.findCodesByIds(PROJECT_A, List.of()));
    }

    /**
     * Replace-by-identity regression: correcting a decision's references must carry both its
     * {@code addressesRequirement} edge and its {@code supersededBy} edge (kogn-io/arknet#357 - now
     * an ordinary field of {@link Adr}, no special preservation logic needed for it any more) along
     * rather than dropping either.
     */
    @Test
    void compareAndUpdatePreservesTheSupersededByEdgeWhileExtendingAnotherRelation() {
        Adr successor = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, successor, "en");
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.SUPERSEDED, null, null, null,
                List.of(), List.of(), successor.id());
        repository.create(PROJECT_A, original, "en");

        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        Adr extended = original.reviseReferences(List.of(requirement), List.of(), List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), extended, null, null, null, Map.of(), Map.of(), null);

        Adr found = repository.findByCode(PROJECT_A, original.code(), null).orElseThrow();
        assertEquals(successor.id(), found.supersededBy());
        assertEquals(List.of(requirement), found.addressesRequirements());
        assertEquals(AdrStatus.SUPERSEDED, found.status());
    }

    /**
     * {@code arkarch:supersedes} (the pre-#357 legacy shape) has no field on {@link Adr} at all - it
     * is reachable only store-first. A replace-by-identity write must carry it along
     * instead of silently erasing it - the "Altbestands-Auffang" this issue introduces, the same
     * preservation the bounded-context adapter performs for {@code arkddd:hasAggregate}.
     * {@code arkarch:relatedTo}/{@code arkarch:supersededBy} deliberately no longer belong in this
     * test: both are fields of the record now, so they travel inside the candidate graph rather than
     * around it.
     */
    @Test
    void compareAndUpdatePreservesAStoreFirstLegacySupersedesEdge() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null);
        repository.create(PROJECT_A, original, "en");

        String legacySupersedesIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        insertLegacySupersedes(id, legacySupersedesIri);

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.accept(DECIDED_ON), null, null, null, Map.of(), Map.of(), null);

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.SUPERSEDES + "> <" + legacySupersedesIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    /**
     * The other half of the lesson from kogn-io/arknet#357: now that {@code arkarch:supersededBy} is
     * a real field of {@link Adr}, it must <em>not</em> survive a write that no longer carries it -
     * the same field a store-first record's stray edge used to be silently re-attached past the gate
     * on every unrelated write, which made it unclearable. A domain object without the field simply
     * omits the triple from its candidate graph, and the replace-by-identity delete-then-rewrite
     * removes whatever the previous write left.
     *
     * <p>The head is read via raw SPARQL ({@link #headsOf}) rather than {@link #currentHeadOf}: a
     * store-first {@code supersededBy} on a decision whose status is not {@code Superseded}
     * violates the bi-implication {@link Adr}'s constructor enforces, and
     * {@code KognioRdfAdrRepository}'s read path treats that the same way it treats an
     * undecodable status - {@code WARN} and skip the decision - so materialising it through the
     * domain path at this exact moment would itself report "not found".</p>
     */
    @Test
    void compareAndUpdateDropsAStoreFirstSupersededByEdgeTheRecordNoLongerCarries() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null);
        repository.create(PROJECT_A, original, "en");
        String headBeforeTheStoreFirstEdit = headsOf(id.value().value()).get(0);

        String supersededByIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.SUPERSEDED_BY + "> <" + supersededByIri + "> } }");

        repository.compareAndUpdate(PROJECT_A, headBeforeTheStoreFirstEdit, original.accept(DECIDED_ON), null, null, null, Map.of(), Map.of(), null);

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.SUPERSEDED_BY + "> <" + supersededByIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask(ask),
                    "a store-first supersededBy edge the record no longer carries must not survive a write");
        }
    }

    @Test
    void createsAndReadsBackRelatedToEdges() {
        Adr peer = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, peer, "en");
        Adr related = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(peer.id()));

        repository.create(PROJECT_A, related, "en");

        assertEquals(List.of(peer.id()),
                repository.findByCode(PROJECT_A, new AdrCode("ADR-2"), null).orElseThrow().relatedTo());
        assertEquals(List.of(peer.id()), repository.findAll(PROJECT_A, null).stream()
                .filter(adr -> adr.code().equals(new AdrCode("ADR-2")))
                .findFirst().orElseThrow().relatedTo());
        assertEquals(List.of(peer.id()),
                repository.findCurrentByCode(PROJECT_A, new AdrCode("ADR-2")).orElseThrow()
                        .value().relatedTo());
    }

    /**
     * Only the forward edge is asserted, even though the ontology declares {@code arkarch:relatedTo}
     * an {@code owl:SymmetricProperty}: nothing here reasons over symmetry, and a hand-written
     * mirror triple would be the drift risk this project avoids everywhere else. The peer's side of
     * the relation is a reverse read ({@link #findRelatedCodesReadsTheReferencingDecisions}), never
     * a second triple.
     */
    @Test
    void createWritesOnlyTheForwardRelatedToEdge() {
        Adr peer = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, peer, "en");
        Adr related = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(peer.id()));
        repository.create(PROJECT_A, related, "en");

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + peer.id().value().value() + "> <"
                + ArkarchVocabulary.RELATED_TO + "> <" + related.id().value().value() + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask(ask), "the mirror triple must not be asserted");
        }
        assertEquals(List.of(), repository.findByCode(PROJECT_A, peer.code(), null).orElseThrow().relatedTo());
    }

    /**
     * The expensive lesson {@link Adr}'s javadoc records, pinned for this relation too: a later,
     * unrelated write (here {@code adr_set_status}) replaces the decision's triples wholesale, so an
     * edge the record still carries has to come back out of the candidate graph instead of being
     * swept away.
     */
    @Test
    void compareAndUpdateKeepsARelatedToEdgeTheRecordStillCarries() {
        Adr peer = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, peer, "en");
        Adr related = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(peer.id()));
        repository.create(PROJECT_A, related, "en");

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(related.code()), related.accept(DECIDED_ON), null, null, null, Map.of(), Map.of(), null);

        Adr found = repository.findByCode(PROJECT_A, related.code(), null).orElseThrow();
        assertEquals(List.of(peer.id()), found.relatedTo());
        assertEquals(AdrStatus.ACCEPTED, found.status());
    }

    /** The other half: a record that no longer carries the edge really does lose it. */
    @Test
    void compareAndUpdateDropsARelatedToEdgeTheRecordNoLongerCarries() {
        Adr peer = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, peer, "en");
        Adr related = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(peer.id()));
        repository.create(PROJECT_A, related, "en");

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(related.code()),
                related.reviseReferences(List.of(), List.of(), List.of(), List.of()), null, null, null, Map.of(),
                Map.of(), null);

        assertEquals(List.of(), repository.findByCode(PROJECT_A, related.code(), null).orElseThrow().relatedTo());
    }

    @Test
    void findRelatedCodesReadsTheReferencingDecisions() {
        Adr target = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, target, "en");
        Adr referencingA = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(target.id()));
        repository.create(PROJECT_A, referencingA, "en");
        Adr referencingB = adr(freshId(), new AdrCode("ADR-10"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(target.id()));
        repository.create(PROJECT_A, referencingB, "en");

        // Ordered by running number, not lexicographically - ADR-10 must not sort before ADR-2.
        assertEquals(List.of(new AdrCode("ADR-2"), new AdrCode("ADR-10")),
                repository.findRelatedCodes(PROJECT_A, target.id()));
        assertEquals(List.of(), repository.findRelatedCodes(PROJECT_A, referencingA.id()));
    }

    /**
     * {@code ashapes:ADR-relatedTo} carries {@code sh:class arkarch:ArchitectureDecisionRecord}, so
     * an edge pointing at something that is not a recorded decision is refused at the gate and
     * nothing is written. The application service resolves peer codes before it ever gets here, so
     * this only fires for a caller reaching past it - which is exactly what the shape is for.
     */
    @Test
    void refusesARelatedToEdgeToSomethingThatIsNotAnAdr() {
        AdrId dangling = freshId();
        Adr related = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(dangling));

        assertThrows(WriteConstraintViolationException.class, () -> repository.create(PROJECT_A, related, "en"));

        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
    }

    /**
     * Unlike {@code addressesRequirement}/{@code affectsContext}/{@code supersedes},
     * {@code arkarch:relatedTo} does not preserve a blank-node target across a write:
     * {@code ashapes:ADR-relatedTo} shapes it {@code sh:nodeKind sh:IRI} with {@code sh:Violation},
     * so a blank-node edge is exactly the state a full-store validation flags as broken - the write
     * path drops it instead of re-attaching it past the gate.
     */
    @Test
    void compareAndUpdateDropsABlankNodeRelatedToEdge() {
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null);
        repository.create(PROJECT_A, original, "en");

        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.RELATED_TO + "> [ a <" + ArkarchVocabulary.ADR_TYPE + "> ] } }");

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.accept(DECIDED_ON), null, null, null, Map.of(), Map.of(), null);

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.RELATED_TO + "> ?target FILTER(isBlank(?target)) } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask(ask));
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
                List.of(), List.of(), null);
        repository.create(PROJECT_A, original, "en");

        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + id.value().value() + "> <"
                + ArkarchVocabulary.ADDRESSES_REQUIREMENT + "> "
                + "[ a <https://w3id.org/arknet/requirements#FunctionalRequirement> ] } }");

        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), original.accept(DECIDED_ON), null, null, null, Map.of(), Map.of(), null);

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
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");

        assertFalse(repository.findByCode(PROJECT_B, new AdrCode("ADR-1"), null).isPresent());
        assertTrue(repository.findAll(PROJECT_B, null).isEmpty());
    }

    /** A store-first ADR is what actually lands in the shared project dataset. */
    @Test
    void writesIntoTheAdrNamedGraph() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created, "en");

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
        repository.create(PROJECT_A, created, "en");

        String ask = "ASK { GRAPH <" + ADR_GRAPH + "> { <" + created.id().value().value() + "> <"
                + ArkarchVocabulary.ADR_STATUS + "> <" + ArkarchVocabulary.PROPOSED + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(ask));
        }
    }

    // ---- revision trail and compare-and-set --------------------------------------

    @Test
    void everyWriteRecordsExactlyOneRevisionAndMovesTheQueryableHead() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created, "en");
        String subject = created.id().value().value();

        List<String> afterCreate = revisionsOf(subject);
        assertEquals(1, afterCreate.size(), "create must record exactly one revision");
        assertEquals(afterCreate, headsOf(subject), "the head must point at the sole revision");

        repository.compareAndUpdate(PROJECT_A, afterCreate.get(0), created.accept(DECIDED_ON), null, null, null, Map.of(), Map.of(), null);

        assertEquals(2, revisionsOf(subject).size(), "update must record exactly one more revision");
        List<String> heads = headsOf(subject);
        assertEquals(1, heads.size(), "the head is rewritten, never duplicated");
        assertFalse(heads.get(0).equals(afterCreate.get(0)), "the head must have moved");
        assertEquals(List.of(afterCreate.get(0)), objectsOf(heads.get(0), ArkprovVocabulary.WAS_REVISION_OF),
                "the new head must supersede the previous one via prov:wasRevisionOf");
    }

    @Test
    void aRejectedWriteLeavesNoRevisionBehind() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");

        assertThrows(DuplicateAdrCodeException.class,
                () -> repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en"));

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
        repository.create(PROJECT_A, created, "en");

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
        repository.create(PROJECT_A, created, "en");
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
        Adr winner = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, winner, "en");
        Adr loser = adr(new AdrCode("ADR-3"));
        repository.create(PROJECT_A, loser, "en");
        AdrId id = freshId();
        Adr original = adr(id, new AdrCode("ADR-1"), AdrStatus.ACCEPTED, null, null, null,
                List.of(), List.of(), null);
        repository.create(PROJECT_A, original, "en");
        String staleHead = currentHeadOf(original.code());

        repository.compareAndUpdate(PROJECT_A, staleHead, original.supersededBy(winner.id()), null, null, null, Map.of(), Map.of(), null);

        Adr byTheLoser = original.supersededBy(loser.id());
        assertThrows(AdrConcurrentlyModifiedException.class,
                () -> repository.compareAndUpdate(PROJECT_A, staleHead, byTheLoser, null, null, null, Map.of(), Map.of(), null));

        assertEquals(winner.id(),
                repository.findByCode(PROJECT_A, original.code(), null).orElseThrow().supersededBy());
        assertEquals(2, revisionsOf(id.value().value()).size(),
                "the rejected write must not have recorded a revision");
    }

    // ---- delete ------------------------------------------------------------------------

    @Test
    void deleteRemovesEveryTripleOfTheDecision() {
        Adr created = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.PROPOSED,
                List.of(new Consequence(1, "Some consequences", ConsequenceType.NEUTRAL)),
                List.of(new ConsideredOption(1, "Option", "Some alternatives", OptionOutcome.REJECTED)),
                LocalDate.of(2026, 8, 23), List.of(), List.of(), null);
        repository.create(PROJECT_A, created, "en");

        repository.delete(PROJECT_A, created.code());

        assertTrue(repository.findByCode(PROJECT_A, created.code(), null).isEmpty());
        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
        assertTrue(triplesOf(created.id().value().value()).isEmpty(), "no triple of the subject may survive");
    }

    @Test
    void deleteRejectsAnUnknownCode() {
        assertThrows(AdrNotFoundException.class, () -> repository.delete(PROJECT_A, new AdrCode("ADR-9")));
    }

    /**
     * The race-free half of the status check: the application service asks before the write
     * transaction opens ({@code AdrService#delete}), this one asks inside it - a status transition
     * committed in that gap (e.g. a concurrent {@code adr_set_status ACCEPTED}) must not slip past
     * it. Pinned directly against the adapter, the only way to exercise this half without the
     * service's own pre-check intercepting first.
     */
    @Test
    void deleteRejectsADecisionThatIsNoLongerProposed() {
        Adr accepted = adr(freshId(), new AdrCode("ADR-1"), AdrStatus.ACCEPTED, null, null, null,
                List.of(), List.of(), null);
        repository.create(PROJECT_A, accepted, "en");

        AdrNotDeletableException thrown = assertThrows(AdrNotDeletableException.class,
                () -> repository.delete(PROJECT_A, accepted.code()));

        assertEquals(AdrStatus.ACCEPTED, thrown.status());
        assertTrue(repository.findByCode(PROJECT_A, accepted.code(), null).isPresent(),
                "a rejected delete must leave the decision untouched");
        assertFalse(headsOf(accepted.id().value().value()).isEmpty(),
                "a rejected delete must not tombstone anything");
    }

    @Test
    void projectsAreIsolatedForDelete() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created, "en");

        assertThrows(AdrNotFoundException.class, () -> repository.delete(PROJECT_B, created.code()));
        assertTrue(repository.findByCode(PROJECT_A, created.code(), null).isPresent(),
                "a delete in another project must not touch this project's decision");
    }

    /**
     * The tombstone the shared funnel leaves (ADR-013): the head pointer goes, the last
     * revision is marked {@code prov:invalidatedAtTime}, and the chain up to it stays as the audit
     * trail - "this existed, until here".
     */
    @Test
    void deleteTombstonesTheLastRevisionAndRemovesTheHead() {
        Adr peer = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, peer, "en");
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created, "en");
        String subject = created.id().value().value();
        // A second write that leaves the decision PROPOSED (delete's own status check runs next),
        // so the revision chain has more than one entry to tombstone correctly.
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(created.code()),
                created.reviseReferences(List.of(), List.of(), List.of(), List.of(peer.id())), null, null, null,
                Map.of(), Map.of(), null);
        String lastRevision = headsOf(subject).get(0);

        repository.delete(PROJECT_A, created.code());

        assertTrue(headsOf(subject).isEmpty(), "the head pointer must be removed");
        assertEquals(2, revisionsOf(subject).size(), "the revision chain must survive the delete");
        String invalidated = "SELECT ?t WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + lastRevision + "> <" + ArkprovVocabulary.INVALIDATED_AT_TIME + "> ?t } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertEquals(1, handle.sparqlQuery().select(invalidated).count(),
                    "the last revision must be tombstoned, not erased");
        }
    }

    /**
     * The one thing the funnel's tombstone cannot carry: the business code lives on the model triple
     * the delete removes, so the adapter hangs it on the tombstoned revision itself - the only place
     * it can outlive its resource, and what keeps {@code ADR-1} from naming a second decision later.
     */
    @Test
    void deleteKeepsTheBusinessCodeOnTheTombstonedRevision() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created, "en");
        String lastRevision = headsOf(created.id().value().value()).get(0);

        repository.delete(PROJECT_A, created.code());

        assertEquals(List.of("ADR-1"), identifiersOf(lastRevision));
        assertEquals(List.of(new AdrCode("ADR-1")), repository.findRetainedCodes(PROJECT_A));
    }

    /** A living decision's revision carries no retained code - only a tombstoned one does. */
    @Test
    void findRetainedCodesIgnoresLivingDecisionsAndOtherProjects() {
        repository.create(PROJECT_A, adr(new AdrCode("ADR-1")), "en");
        Adr deleted = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, deleted, "en");

        repository.delete(PROJECT_A, deleted.code());

        assertEquals(List.of(new AdrCode("ADR-2")), repository.findRetainedCodes(PROJECT_A));
        assertEquals(List.of(), repository.findRetainedCodes(PROJECT_B));
    }

    /**
     * The provenance graph is shared by every bounded context, so a neighbour retaining its own code
     * the same way must not be counted as an ADR code - the deleted resource's type triple is gone,
     * leaving the code prefix as the only discriminator.
     */
    @Test
    void findRetainedCodesIgnoresAnotherContextsRetainedCode() {
        update("INSERT DATA { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "<https://w3id.org/arknet/revision/foreign> <" + ArkprovVocabulary.INVALIDATED_AT_TIME
                + "> \"2026-08-23T10:00:00Z\"^^<http://www.w3.org/2001/XMLSchema#dateTime> ; "
                + "<http://purl.org/dc/terms/identifier> \"TERM-9\" } }");

        assertEquals(List.of(), repository.findRetainedCodes(PROJECT_A));
    }

    /**
     * Only a <em>tombstoned</em> revision retains a code. A code sitting on a live decision's
     * revision - which nothing in this adapter writes, but a store-first edit can - is not
     * a retained one: counting it would raise the numbering over a decision that is still there and
     * already carries that very code.
     */
    @Test
    void findRetainedCodesIgnoresACodeOnARevisionThatWasNeverTombstoned() {
        Adr created = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, created, "en");
        String liveRevision = headsOf(created.id().value().value()).get(0);

        update("INSERT DATA { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <" + liveRevision
                + "> <http://purl.org/dc/terms/identifier> \"ADR-1\" } }");

        assertEquals(List.of(), repository.findRetainedCodes(PROJECT_A));
    }

    /**
     * The race-free half of the reference check: the application service asks before the write
     * transaction opens, this one asks inside it. Pinned against real triples, and for both
     * relations, because they are what a rejection has to name.
     *
     * <p>Names the delete-candidate {@code X} as <em>another</em> decision's successor
     * (kogn-io/arknet#357: {@code Y.supersededBy = X}, i.e. {@code X} supersedes {@code Y}) - the
     * external edge that would dangle if {@code X} disappeared. {@code X}'s own outgoing field (were
     * it superseded itself) is deliberately not what this test protects: that triple would vanish
     * with {@code X} harmlessly, which is also why {@code X} stays PROPOSED here rather than
     * SUPERSEDED - a superseded decision could never reach this check to begin with, since
     * {@code delete}'s own status guard runs first.</p>
     */
    @Test
    void deleteRejectsADecisionAnotherOneNamesAsItsSuccessor() {
        Adr successor = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, successor, "en");
        Adr predecessor = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.SUPERSEDED, null, null, null,
                List.of(), List.of(), successor.id());
        repository.create(PROJECT_A, predecessor, "en");

        AdrReferencedException thrown = assertThrows(AdrReferencedException.class,
                () -> repository.delete(PROJECT_A, successor.code()));

        assertEquals(List.of(new AdrReferencedException.Reference(new AdrCode("ADR-2"),
                AdrReferencedException.SUPERSEDED_BY)), thrown.references());
        assertTrue(repository.findByCode(PROJECT_A, successor.code(), null).isPresent(),
                "a rejected delete must leave the decision untouched");
        assertFalse(headsOf(successor.id().value().value()).isEmpty(),
                "a rejected delete must not tombstone anything");
    }

    /**
     * The pre-#357 legacy shape protects a delete-candidate exactly the same way: a store-first
     * {@code arkarch:supersedes} triple naming it is an external edge too, and would dangle just the
     * same if the candidate disappeared.
     */
    @Test
    void deleteRejectsADecisionALegacySupersedesTripleStillNames() {
        Adr target = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, target, "en");
        Adr legacyReferrer = adr(new AdrCode("ADR-2"));
        repository.create(PROJECT_A, legacyReferrer, "en");
        insertLegacySupersedes(legacyReferrer.id(), target.id());

        AdrReferencedException thrown = assertThrows(AdrReferencedException.class,
                () -> repository.delete(PROJECT_A, target.code()));

        assertEquals(List.of(new AdrReferencedException.Reference(new AdrCode("ADR-2"),
                AdrReferencedException.SUPERSEDES)), thrown.references());
        assertTrue(repository.findByCode(PROJECT_A, target.code(), null).isPresent(),
                "a rejected delete must leave the decision untouched");
    }

    @Test
    void deleteRejectsADecisionAnotherOneIsRelatedTo() {
        Adr peer = adr(new AdrCode("ADR-1"));
        repository.create(PROJECT_A, peer, "en");
        Adr naming = adr(freshId(), new AdrCode("ADR-2"), AdrStatus.PROPOSED, null, null, null,
                List.of(), List.of(), null, List.of(peer.id()));
        repository.create(PROJECT_A, naming, "en");

        AdrReferencedException thrown = assertThrows(AdrReferencedException.class,
                () -> repository.delete(PROJECT_A, peer.code()));

        assertEquals(List.of(new AdrReferencedException.Reference(new AdrCode("ADR-2"),
                AdrReferencedException.RELATED_TO)), thrown.references());
        assertTrue(repository.findByCode(PROJECT_A, peer.code(), null).isPresent(),
                "a rejected delete must leave the decision untouched");
    }

    /** The head a caller would observe right now - what a well-behaved compare-and-set passes. */
    private String currentHeadOf(AdrCode code) {
        return repository.findCurrentByCode(PROJECT_A, code).orElseThrow().head();
    }

    /**
     * Store-first-simulates the pre-#357 {@code arkarch:supersedes} shape: {@code supersedingId}
     * supersedes {@code supersededId}, written directly rather than through any tool - exactly how a
     * project's legacy data would still carry it. Bypasses the SHACL gate entirely, as any raw
     * store-first edit does.
     */
    private void insertLegacySupersedes(AdrId supersedingId, AdrId supersededId) {
        insertLegacySupersedes(supersedingId, supersededId.value().value());
    }

    private void insertLegacySupersedes(AdrId supersedingId, String supersededIri) {
        update("INSERT DATA { GRAPH <" + ADR_GRAPH + "> { <" + supersedingId.value().value() + "> <"
                + ArkarchVocabulary.SUPERSEDES + "> <" + supersededIri + "> } }");
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

    /** Every triple of one subject in the ADR graph, as {@code predicate=object} pairs. */
    private List<String> triplesOf(String subjectIri) {
        String query = "SELECT ?p ?o WHERE { GRAPH <" + ADR_GRAPH + "> { <" + subjectIri + "> ?p ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> row.getValue("p").orElseThrow() + "=" + row.getValue("o").orElseThrow())
                    .toList();
        }
    }

    /** The {@code dcterms:identifier} literals a revision carries in the provenance graph. */
    private List<String> identifiersOf(String revisionIri) {
        String query = "SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + revisionIri + "> <http://purl.org/dc/terms/identifier> ?v } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((Literal) row.getValue("v").orElseThrow()).getLexicalForm())
                    .toList();
        }
    }

    private List<String> selectIris(String query) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .toList();
        }
    }
}
