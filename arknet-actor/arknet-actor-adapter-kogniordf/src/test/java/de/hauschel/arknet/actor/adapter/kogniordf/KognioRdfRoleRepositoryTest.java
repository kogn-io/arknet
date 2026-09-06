// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.RoleDetail.FilledByActor;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.actor.domain.DuplicateRoleCodeException;
import de.hauschel.arknet.actor.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.RoleId;
import de.hauschel.arknet.actor.domain.RoleNotFoundException;
import de.hauschel.arknet.actor.domain.RoleReferencedException;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Integration test for {@link KognioRdfRoleRepository} against an in-memory RDF4J-backed
 * kognio-rdf store - mirrors {@code KognioRdfActorRepositoryTest}'s structure for the
 * non-multilingual mechanics (CRUD, {@code filledBy}, row-independent gate checks); the
 * multilingual {@code name}/{@code description} behaviour itself is pinned separately in
 * {@code KognioRdfRoleRepositoryMultilingualTest}, mirroring
 * {@code KognioRdfConstraintRepositoryMultilingualTest}'s split.
 */
class KognioRdfRoleRepositoryTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String ROLE_GRAPH = "https://w3id.org/arknet/model/roles";
    private static final String ROLE_TYPE = "https://w3id.org/arknet/process#Role";
    private static final String FILLED_BY_PROPERTY = "https://w3id.org/arknet/process#filledBy";
    private static final String IDENTIFIER_PROPERTY = "http://purl.org/dc/terms/identifier";
    private static final String NAME_PROPERTY = "https://w3id.org/arknet/core#name";
    private static final String DESCRIPTION_PROPERTY = "https://w3id.org/arknet/core#description";

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfRoleRepository repository;

    /**
     * The one funnel both resource types of this hexagon write through - kept as a field, not a
     * local, so {@link #filledByReadsBackAsCodeAndNameThroughTheServiceReadPath()} can build the
     * sibling actor repository over the very same instance the composition root shares.
     */
    private WriteFunnel funnel;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        funnel = new WriteFunnel(datasetLifecycle, gate, WriteFunnel.DEFAULT_WRITE_CONFLICT);
        repository = new KognioRdfRoleRepository(datasetLifecycle, DisplayLocale.DEFAULT, funnel);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static RoleId freshId() {
        return new RoleId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static ActorId freshActorId() {
        return new ActorId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    /** A deliberately chosen opaque actor identity, for the tests that pin the occupancy order. */
    private static ActorId actorId(String localName) {
        return new ActorId(ResourceId.of("https://w3id.org/arknet/id/" + localName));
    }

    private static Role role(RoleCode code, String description, List<ActorId> filledBy) {
        return new Role(freshId(), code, "Requirements Engineer", description, filledBy);
    }

    @Test
    void createsAndFindsRoleByCode() {
        Role stored = role(new RoleCode("ROLE-1"), "Writes and maintains requirements.", List.of());

        repository.create(PROJECT_A, stored, "en");
        Optional<Role> found = repository.findByCode(PROJECT_A, new RoleCode("ROLE-1"), "en");

        assertEquals(Optional.of(stored), found);
        assertEquals("Requirements Engineer", found.orElseThrow().name());
    }

    @Test
    void createsAndReadsBackWithoutTheOptionalDescription() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), null, List.of()), "en");

        Role found = repository.findByCode(PROJECT_A, new RoleCode("ROLE-1"), "en").orElseThrow();

        assertNull(found.description());
    }

    /** Only {@code arkproc:Role} is written - never the abstract {@code arkproc:Actor}. */
    @Test
    void writesOnlyTheRoleTypeIntoItsOwnNamedGraph() {
        Role stored = role(new RoleCode("ROLE-1"), null, List.of());
        repository.create(PROJECT_A, stored, "en");

        String subject = stored.id().value().value();
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask("ASK { GRAPH <" + ROLE_GRAPH + "> { <" + subject
                    + "> a <" + ROLE_TYPE + "> } }"));
            assertFalse(handle.sparqlQuery().ask("ASK { GRAPH <https://w3id.org/arknet/model/actors> { <"
                    + subject + "> ?p ?o } }"), "a role must live in its own named graph, not the actor one");
        }
    }

    @Test
    void findAllReturnsEveryStoredRole() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), null, List.of()), "en");
        repository.create(PROJECT_A, role(new RoleCode("ROLE-2"), "Architects the solution.", List.of()), "en");

        assertEquals(2, repository.findAll(PROJECT_A, "en").size());
    }

    @Test
    void findByCodeIsEmptyForUnknownCode() {
        assertTrue(repository.findByCode(PROJECT_A, new RoleCode("ROLE-99"), "en").isEmpty());
    }

    @Test
    void createRejectsAnAlreadyExistingIdentity() {
        RoleId id = freshId();
        Role first = new Role(id, new RoleCode("ROLE-1"), "Requirements Engineer", null, List.of());
        repository.create(PROJECT_A, first, "en");

        Role sameIdentity = new Role(id, new RoleCode("ROLE-2"), "Architect", null, List.of());

        assertThrows(ResourceAlreadyExistsException.class, () -> repository.create(PROJECT_A, sameIdentity, "en"));
    }

    @Test
    void createRejectsADuplicateBusinessCodeOnADifferentIdentity() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), null, List.of()), "en");

        Role sameCode = role(new RoleCode("ROLE-1"), null, List.of());

        assertThrows(DuplicateRoleCodeException.class, () -> repository.create(PROJECT_A, sameCode, "en"));
    }

    @Test
    void updateReplacesAnExistingRole() {
        RoleId id = freshId();
        Role original = new Role(id, new RoleCode("ROLE-1"), "Requirements Engineer",
                "Writes and maintains requirements.", List.of());
        repository.create(PROJECT_A, original, "en");

        Role changed = new Role(id, new RoleCode("ROLE-1"), "Senior Requirements Engineer",
                "New description.", List.of());
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(changed.code()), changed, "en", "en", null);

        Role found = repository.findByCode(PROJECT_A, new RoleCode("ROLE-1"), "en").orElseThrow();
        assertEquals("Senior Requirements Engineer", found.name());
        assertEquals("New description.", found.description());
    }

    @Test
    void updateRejectsAMissingIdentity() {
        Role missing = role(new RoleCode("ROLE-1"), null, List.of());

        assertThrows(RoleNotFoundException.class,
                () -> repository.compareAndUpdate(PROJECT_A, null, missing, "en", "en", null));
    }

    @Test
    void compareAndUpdateRejectsACodeChangedToCollideWithAnotherRole() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), null, List.of()), "en");
        RoleId id = freshId();
        Role second = new Role(id, new RoleCode("ROLE-2"), "Architect", null, List.of());
        repository.create(PROJECT_A, second, "en");
        RevisionToken head = currentHeadOf(second.code());

        Role recodedToCollide = new Role(id, new RoleCode("ROLE-1"), second.name(), second.description(),
                second.filledBy());

        assertThrows(DuplicateRoleCodeException.class,
                () -> repository.compareAndUpdate(PROJECT_A, head, recodedToCollide, "en", "en", null));
        assertEquals("Architect", repository.findByCode(PROJECT_A, new RoleCode("ROLE-2"), "en")
                .orElseThrow().name(), "the rejected write must not have changed anything");
    }

    // ---- arkproc:filledBy round trip ------------------------------------------------------

    @Test
    void filledByRoundTripsAsOpaqueActorIdentities() {
        ActorId occupant = freshActorId();
        Role stored = role(new RoleCode("ROLE-1"), null, List.of(occupant));

        repository.create(PROJECT_A, stored, "en");

        Role found = repository.findByCode(PROJECT_A, new RoleCode("ROLE-1"), "en").orElseThrow();
        assertEquals(List.of(occupant), found.filledBy());
    }

    @Test
    void anUnfilledRoleRoundTripsWithNoFilledByEdgeAtAll() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), null, List.of()), "en");

        Role found = repository.findByCode(PROJECT_A, new RoleCode("ROLE-1"), "en").orElseThrow();

        assertTrue(found.filledBy().isEmpty());
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask("ASK { GRAPH <" + ROLE_GRAPH + "> { ?s <"
                    + FILLED_BY_PROPERTY + "> ?a } }"));
        }
    }

    /**
     * The occupancy is a set in the store, so the read has to impose the order rather than inherit
     * one ({@code ORDER BY ?a}) - it must come back exactly as the aggregate canonicalised it, or
     * an unchanged occupancy could read back differently from one call to the next and the
     * committed {@code store-report.html} export could diff without a model change.
     */
    @Test
    void filledByAcceptsSeveralOccupantsAndReadsThemBackInTheAggregatesCanonicalOrder() {
        ActorId first = freshActorId();
        ActorId second = freshActorId();
        Role stored = role(new RoleCode("ROLE-1"), null, List.of(first, second));

        repository.create(PROJECT_A, stored, "en");

        Role found = repository.findByCode(PROJECT_A, new RoleCode("ROLE-1"), "en").orElseThrow();
        assertEquals(2, found.filledBy().size());
        assertEquals(stored.filledBy(), found.filledBy(), found.filledBy().toString());
    }

    /** {@code compareAndUpdate} replaces the occupancy wholesale, mirroring every other field's replace. */
    @Test
    void updateReplacesTheOccupancyWholesale() {
        RoleId id = freshId();
        ActorId first = freshActorId();
        ActorId second = freshActorId();
        Role original = new Role(id, new RoleCode("ROLE-1"), "Case Handler", null, List.of(first));
        repository.create(PROJECT_A, original, "en");

        Role changed = new Role(id, new RoleCode("ROLE-1"), "Case Handler", null, List.of(second));
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), changed, "en", "en", null);

        Role found = repository.findByCode(PROJECT_A, new RoleCode("ROLE-1"), "en").orElseThrow();
        assertEquals(List.of(second), found.filledBy());
    }

    /**
     * The gate's {@code sh:nodeKind sh:IRI} constraint on {@code actshapes:Role-filledBy} actually
     * fires - a candidate targeting a blank node is rejected. See {@link KognioRdfRoleRepository}'s
     * own javadoc for why this is {@code sh:nodeKind}, not {@code sh:class arkproc:Actor}.
     */
    @Test
    void gateRejectsAFilledByTargetThatIsABlankNode() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(ROLE_TYPE));
        candidate.add(subject, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral("Requirements Engineer", "en"));
        candidate.add(subject, rdf.createIRI(FILLED_BY_PROPERTY), rdf.createBlankNode());

        ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    /** The counterpart: an IRI target passes. */
    @Test
    void gateAcceptsAFilledByTargetThatIsAnIri() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI occupant = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(ROLE_TYPE));
        candidate.add(subject, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral("Requirements Engineer", "en"));
        candidate.add(subject, rdf.createIRI(FILLED_BY_PROPERTY), occupant);

        ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        gate.enforce(candidate);
    }

    /**
     * The one test that walks the whole occupancy read path against the real store rather than
     * stopping at this repository's opaque {@code filledBy} identities: {@code role_add}, then
     * {@code role_get}/{@code role_list} rendered as {@code ACTOR-N (Name)}. That last hop is
     * {@code ActorRepository#findAllByIds} - a query with mandatory joins of its own - and
     * {@code RoleService} drops silently whatever it fails to materialise, so without this test a
     * broken query would show up as an unfilled role rather than as a failure.
     *
     * <p>An application service inside an adapter test is deliberate and confined to this one
     * case: {@code RoleDetail} is where the two resource types of this hexagon meet, and both
     * sides of that meeting are out-adapters. The sibling actor repository shares {@link #funnel},
     * exactly as the composition root wires it.</p>
     */
    /**
     * The two occupants get deliberately chosen opaque identities here, ordered the other way round
     * than the {@code ACTOR-N} codes the call names them under: the occupancy is ordered by
     * identity, not by the order a caller happened to type - that is what makes an unchanged
     * occupancy read back identically every time.
     */
    @Test
    void filledByReadsBackAsCodeAndNameThroughTheServiceReadPath() {
        ActorRepository actors = KognioRdfActorRepositoryFactory.over(lifecycle, funnel);
        actors.create(PROJECT_A, new Actor(actorId("actor-b"), new ActorCode("ACTOR-1"), ActorType.HUMAN,
                "Sachbearbeiter", null));
        actors.create(PROJECT_A, new Actor(actorId("actor-a"), new ActorCode("ACTOR-2"), ActorType.SYSTEM,
                "Fachanwendung", null));
        RoleService service = new RoleService(repository, actors, new UuidResourceIdFactory());

        RoleCode code = service.add(PROJECT_A, new NewRole("Requirements Engineer",
                "Writes and maintains requirements.", List.of("ACTOR-1", "ACTOR-2"), "en"), "en")
                .role().code();

        List<FilledByActor> occupants = service.get(PROJECT_A, code, "en").orElseThrow().filledByActors();
        assertEquals(List.of(new FilledByActor(new ActorCode("ACTOR-2"), "Fachanwendung"),
                new FilledByActor(new ActorCode("ACTOR-1"), "Sachbearbeiter")), occupants, occupants.toString());
        assertEquals(occupants, service.list(PROJECT_A, "en").get(0).filledByActors(),
                "role_list must resolve the occupants the same way role_get does");
    }

    // ---- the SHACL gate: name -----------------------------------------------------------

    @Test
    void gateRejectsARoleWithoutAName() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(ROLE_TYPE));

        ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    @Test
    void gateRejectsATooShortName() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(ROLE_TYPE));
        candidate.add(subject, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral("x", "en"));

        ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    @Test
    void gateAcceptsAWellFormedRole() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), "Writes requirements.", List.of()), "en");

        assertTrue(repository.findByCode(PROJECT_A, new RoleCode("ROLE-1"), "en").isPresent());
    }

    /**
     * What {@link RoleRepository#findAllCodes} exists for (kogn-io/arknet#360, ported to this
     * resource type): a code stays taken even by a subject {@link RoleRepository#findAll} cannot
     * materialise at all - mirrors {@code KognioRdfActorRepositoryTest
     * #findAllCodesKeepsTheCodeOfASubjectFindAllCannotMaterialiseAtAll} exactly.
     */
    @Test
    void findAllCodesKeepsTheCodeOfASubjectFindAllCannotMaterialiseAtAll() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), null, List.of()), "en");
        RoleId bare = freshId();
        insertTriple(bare.value().value(), VocabRdf.TYPE.getIRIString(), "<" + ROLE_TYPE + ">");
        insertTriple(bare.value().value(), IDENTIFIER_PROPERTY, "\"ROLE-2\"");

        assertEquals(1, repository.findAll(PROJECT_A, "en").size());
        assertTrue(repository.findByCode(PROJECT_A, new RoleCode("ROLE-2"), "en").isEmpty());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new RoleCode("ROLE-2")));
    }

    @Test
    void projectsAreIsolated() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), null, List.of()), "en");

        assertFalse(repository.findByCode(PROJECT_B, new RoleCode("ROLE-1"), "en").isPresent());
        assertTrue(repository.findAll(PROJECT_B, "en").isEmpty());
    }

    // ---- revision trail / compare-and-set --------------------------------------------------

    @Test
    void everyWriteRecordsExactlyOneRevisionAndMovesTheQueryableHead() {
        Role stored = role(new RoleCode("ROLE-1"), null, List.of());
        repository.create(PROJECT_A, stored, "en");
        String subject = stored.id().value().value();

        List<String> afterCreate = revisionsOf(subject);
        assertEquals(1, afterCreate.size());

        repository.compareAndUpdate(PROJECT_A, new RevisionToken(afterCreate.get(0)),
                new Role(stored.id(), stored.code(), "Renamed", null, List.of()), "en", "en", null);

        assertEquals(2, revisionsOf(subject).size());
    }

    @Test
    void findCurrentByCodeReturnsTheStateTogetherWithTheCurrentHead() {
        Role stored = role(new RoleCode("ROLE-1"), "A description.", List.of());
        repository.create(PROJECT_A, stored, "en");

        RoleRepository.CurrentRole current =
                repository.findCurrentByCode(PROJECT_A, new RoleCode("ROLE-1"), "en").orElseThrow();

        assertEquals(stored, current.value());
    }

    @Test
    void compareAndUpdateRejectsAStaleHeadAndWritesNothing() {
        RoleId id = freshId();
        Role original = new Role(id, new RoleCode("ROLE-1"), "Requirements Engineer", null, List.of());
        repository.create(PROJECT_A, original, "en");
        RevisionToken staleHead = currentHeadOf(original.code());

        repository.compareAndUpdate(PROJECT_A, staleHead,
                new Role(id, original.code(), "Renamed by the winner", null, List.of()), "en", "en", null);

        Role byTheLoser = new Role(id, original.code(), "Renamed by the loser", null, List.of());
        assertThrows(RoleConcurrentlyModifiedException.class,
                () -> repository.compareAndUpdate(PROJECT_A, staleHead, byTheLoser, "en", "en", null));

        assertEquals("Renamed by the winner",
                repository.findByCode(PROJECT_A, original.code(), "en").orElseThrow().name());
    }

    // ---- delete ----------------------------------------------------------------------------

    @Test
    void deleteRemovesTheRoleAndItsTriples() {
        Role stored = role(new RoleCode("ROLE-1"), "A description.", List.of());
        repository.create(PROJECT_A, stored, "en");

        repository.delete(PROJECT_A, stored.code());

        assertTrue(repository.findByCode(PROJECT_A, stored.code(), "en").isEmpty());
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask("ASK { GRAPH <" + ROLE_GRAPH + "> { <"
                    + stored.id().value().value() + "> ?p ?o } }"));
        }
    }

    @Test
    void deleteRejectsAnUnknownCode() {
        assertThrows(RoleNotFoundException.class, () -> repository.delete(PROJECT_A, new RoleCode("ROLE-99")));
    }

    /**
     * {@link RoleReferencedException} blocks the delete while a use case still points at the role
     * via {@code arkreq:primaryRole} - {@link KognioRdfRoleRepository#rejectIfReferenced} searches
     * across every named graph, so a reference living outside {@link #ROLE_GRAPH} (as a use-case
     * edge would) must still be found. Real as of ADR-37/kogn-io/arknet#405 Part C: before this
     * part {@code REFERENCING_PREDICATES} was empty and this exception unreachable (see its own
     * javadoc) - mutation check performed by temporarily reverting
     * {@code KognioRdfRoleRepository.REFERENCING_PREDICATES} to {@code Map.of()}, which turns this
     * test red with no exception thrown instead of the expected {@link RoleReferencedException}.
     */
    @Test
    void deleteRejectsARoleStillReferencedAsPrimaryRole() {
        Role stored = role(new RoleCode("ROLE-1"), null, List.of());
        repository.create(PROJECT_A, stored, "en");
        String reference = "INSERT DATA { GRAPH <https://example.org/uc> { <https://example.org/uc/1> <"
                + ArkreqVocabulary.PRIMARY_ROLE + "> <" + stored.id().value().value() + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(reference);
                return null;
            });
        }

        assertThrows(RoleReferencedException.class, () -> repository.delete(PROJECT_A, stored.code()));
        assertTrue(repository.findByCode(PROJECT_A, stored.code(), "en").isPresent(),
                "a rejected delete must leave the role untouched");
    }

    /**
     * Same guard, the other referencing predicate: a use case's {@code arkreq:supportingRole}.
     */
    @Test
    void deleteRejectsARoleStillReferencedAsSupportingRole() {
        Role stored = role(new RoleCode("ROLE-1"), null, List.of());
        repository.create(PROJECT_A, stored, "en");
        String reference = "INSERT DATA { GRAPH <https://example.org/uc> { <https://example.org/uc/1> <"
                + ArkreqVocabulary.SUPPORTING_ROLE + "> <" + stored.id().value().value() + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(reference);
                return null;
            });
        }

        assertThrows(RoleReferencedException.class, () -> repository.delete(PROJECT_A, stored.code()));
        assertTrue(repository.findByCode(PROJECT_A, stored.code(), "en").isPresent(),
                "a rejected delete must leave the role untouched");
    }

    @Test
    void deleteTombstonesTheLastRevisionAndRemovesTheHead() {
        Role stored = role(new RoleCode("ROLE-1"), null, List.of());
        repository.create(PROJECT_A, stored, "en");
        String subject = stored.id().value().value();

        repository.delete(PROJECT_A, stored.code());

        String head = "ASK { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <" + subject
                + "> <" + ArkprovVocabulary.HEAD + "> ?v } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask(head), "the head pointer must be removed");
        }
    }

    @Test
    void deleteKeepsTheBusinessCodeRetained() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), null, List.of()), "en");

        repository.delete(PROJECT_A, new RoleCode("ROLE-1"));

        assertEquals(List.of(new RoleCode("ROLE-1")), repository.findRetainedCodes(PROJECT_A));
    }

    @Test
    void findRetainedCodesIgnoresLivingRolesAndOtherProjects() {
        repository.create(PROJECT_A, role(new RoleCode("ROLE-1"), null, List.of()), "en");
        Role deleted = role(new RoleCode("ROLE-2"), null, List.of());
        repository.create(PROJECT_A, deleted, "en");

        repository.delete(PROJECT_A, deleted.code());

        assertEquals(List.of(new RoleCode("ROLE-2")), repository.findRetainedCodes(PROJECT_A));
        assertEquals(List.of(), repository.findRetainedCodes(PROJECT_B));
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void insertTriple(String subjectIri, String predicateIri, String objectTerm) {
        String insert = "INSERT DATA { GRAPH <" + ROLE_GRAPH + "> { <" + subjectIri + "> <"
                + predicateIri + "> " + objectTerm + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    private RevisionToken currentHeadOf(RoleCode code) {
        return repository.findCurrentByCode(PROJECT_A, code, "en").orElseThrow().head();
    }

    private List<String> revisionsOf(String subjectIri) {
        String query = "SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?v a <" + ArkprovVocabulary.REVISION_TYPE + "> ; "
                + "<" + ArkprovVocabulary.SPECIALIZATION_OF + "> <" + subjectIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .toList();
        }
    }
}
