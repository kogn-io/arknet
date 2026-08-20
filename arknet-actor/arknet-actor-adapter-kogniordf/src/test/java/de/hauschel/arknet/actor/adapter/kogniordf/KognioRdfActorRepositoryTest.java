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
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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

import de.hauschel.arknet.actor.application.port.in.ResolveActors;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorReferencedException;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.actor.domain.DuplicateActorCodeException;
import de.hauschel.arknet.actor.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Integration test for {@link KognioRdfActorRepository} against an in-memory RDF4J-backed
 * kognio-rdf store.
 */
class KognioRdfActorRepositoryTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String ACTOR_GRAPH = "https://w3id.org/arknet/model/actors";
    private static final String HUMAN_ACTOR_TYPE = "https://w3id.org/arknet/process#HumanActor";
    private static final String GROUP_ACTOR_TYPE = "https://w3id.org/arknet/process#GroupActor";
    private static final String NAME_PROPERTY = "https://w3id.org/arknet/core#name";
    private static final String DESCRIPTION_PROPERTY = "https://w3id.org/arknet/core#description";

    /**
     * The store's on-disk home, managed by JUnit rather than {@code Files.createTempDirectory},
     * which left its directories behind - harmless while the store is {@code IN_MEMORY}, but still
     * an inode left in {@code /tmp} for every test run. Deleted after {@link #tearDown()} has shut
     * the store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfActorRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        WriteFunnel funnel = new WriteFunnel(datasetLifecycle, gate, WriteFunnel.DEFAULT_WRITE_CONFLICT);
        repository = new KognioRdfActorRepository(datasetLifecycle, funnel);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    /** Fresh, valid opaque identity - every test picks its own so ids never collide. */
    private static ActorId freshId() {
        return new ActorId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static Actor actor(ActorCode code, ActorType type, String description) {
        return new Actor(freshId(), code, type, "Sachbearbeiter", description);
    }

    @Test
    void createsAndFindsActorByCode() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN,
                "Bearbeitet eingehende Antraege im Backoffice.");

        repository.create(PROJECT_A, stored);
        Optional<Actor> found = repository.findByCode(PROJECT_A, new ActorCode("ACTOR-1"));

        assertEquals(Optional.of(stored), found);
        assertEquals(ActorType.HUMAN, found.orElseThrow().type());
        assertEquals("Sachbearbeiter", found.orElseThrow().name());
    }

    @Test
    void createsAndReadsBackWithoutTheOptionalDescription() {
        repository.create(PROJECT_A, actor(new ActorCode("ACTOR-1"), ActorType.SYSTEM, null));

        Actor found = repository.findByCode(PROJECT_A, new ActorCode("ACTOR-1")).orElseThrow();

        assertNull(found.description());
    }

    /** Every one of the four types round-trips through its own concrete {@code rdf:type}. */
    @Test
    void everyActorTypeRoundTrips() {
        int n = 0;
        for (ActorType type : ActorType.values()) {
            ActorCode code = new ActorCode("ACTOR-" + (++n));
            repository.create(PROJECT_A, actor(code, type, null));

            assertEquals(type, repository.findByCode(PROJECT_A, code).orElseThrow().type());
        }
    }

    /** The concrete type is what lands in the store - the abstract superclass is never asserted. */
    @Test
    void writesOnlyTheConcreteActorTypeIntoTheActorNamedGraph() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.GROUP, null);
        repository.create(PROJECT_A, stored);

        String subject = stored.id().value().value();
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask("ASK { GRAPH <" + ACTOR_GRAPH + "> { <" + subject
                    + "> a <" + GROUP_ACTOR_TYPE + "> } }"));
            assertFalse(handle.sparqlQuery().ask("ASK { GRAPH <" + ACTOR_GRAPH + "> { <" + subject
                    + "> a <https://w3id.org/arknet/process#Actor> } }"),
                    "the abstract superclass must not be materialised - the gate reasons it in instead");
        }
    }

    @Test
    void findAllReturnsEveryStoredActor() {
        repository.create(PROJECT_A, actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null));
        repository.create(PROJECT_A, actor(new ActorCode("ACTOR-2"), ActorType.LEGAL, "Zulieferer."));

        List<Actor> all = repository.findAll(PROJECT_A);

        assertEquals(2, all.size());
    }

    @Test
    void findByCodeIsEmptyForUnknownCode() {
        assertTrue(repository.findByCode(PROJECT_A, new ActorCode("ACTOR-99")).isEmpty());
    }

    @Test
    void createRejectsAnAlreadyExistingIdentity() {
        ActorId id = freshId();
        Actor first = new Actor(id, new ActorCode("ACTOR-1"), ActorType.HUMAN, "Sachbearbeiter", null);
        repository.create(PROJECT_A, first);

        Actor sameIdentity = new Actor(id, new ActorCode("ACTOR-2"), ActorType.SYSTEM, "PaymentService", null);

        assertThrows(ResourceAlreadyExistsException.class, () -> repository.create(PROJECT_A, sameIdentity));
    }

    @Test
    void createRejectsADuplicateBusinessCodeOnADifferentIdentity() {
        repository.create(PROJECT_A, actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null));

        Actor sameCode = actor(new ActorCode("ACTOR-1"), ActorType.SYSTEM, null);

        assertThrows(DuplicateActorCodeException.class, () -> repository.create(PROJECT_A, sameCode));
    }

    @Test
    void updateReplacesAnExistingActor() {
        ActorId id = freshId();
        Actor original = new Actor(id, new ActorCode("ACTOR-1"), ActorType.HUMAN, "Sachbearbeiter",
                "Bearbeitet eingehende Antraege im Backoffice.");
        repository.create(PROJECT_A, original);

        Actor changed = new Actor(id, new ActorCode("ACTOR-1"), ActorType.HUMAN, "Antragsbearbeiter",
                "Neue Beschreibung.");
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(changed.code()), changed);

        Actor found = repository.findByCode(PROJECT_A, new ActorCode("ACTOR-1")).orElseThrow();
        assertEquals("Antragsbearbeiter", found.name());
        assertEquals("Neue Beschreibung.", found.description());
    }

    /**
     * The whole-subject replace must not leave the superseded description behind: an actor that
     * loses its description keeps none, rather than accumulating both.
     */
    @Test
    void updateReplacesRatherThanAccumulatesTriples() {
        ActorId id = freshId();
        Actor original = new Actor(id, new ActorCode("ACTOR-1"), ActorType.HUMAN, "Sachbearbeiter",
                "Alte Beschreibung.");
        repository.create(PROJECT_A, original);

        Actor changed = new Actor(id, new ActorCode("ACTOR-1"), ActorType.HUMAN, "Sachbearbeiter",
                "Neue Beschreibung.");
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(changed.code()), changed);

        String ask = "ASK { GRAPH <" + ACTOR_GRAPH + "> { <" + id.value().value() + "> <"
                + DESCRIPTION_PROPERTY + "> \"Alte Beschreibung.\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask(ask), "the superseded description must be gone");
        }
    }

    @Test
    void updateRejectsAMissingIdentity() {
        Actor missing = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null);

        assertThrows(ActorNotFoundException.class,
                () -> repository.compareAndUpdate(PROJECT_A, null, missing));
    }

    /**
     * {@code compareAndUpdate} must enforce the same business-code uniqueness {@code create}
     * already does. Changing an actor's code to one already held by a <em>different</em> identity
     * must be rejected, not silently committed.
     */
    @Test
    void compareAndUpdateRejectsACodeChangedToCollideWithAnotherActor() {
        repository.create(PROJECT_A, actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null));
        ActorId id = freshId();
        Actor second = new Actor(id, new ActorCode("ACTOR-2"), ActorType.SYSTEM, "PaymentService", null);
        repository.create(PROJECT_A, second);
        RevisionToken head = currentHeadOf(second.code());

        Actor recodedToCollide = new Actor(id, new ActorCode("ACTOR-1"), second.type(), second.name(),
                second.description());

        assertThrows(DuplicateActorCodeException.class,
                () -> repository.compareAndUpdate(PROJECT_A, head, recodedToCollide));
        assertEquals("PaymentService", repository.findByCode(PROJECT_A, new ActorCode("ACTOR-2"))
                .orElseThrow().name(), "the rejected write must not have changed anything");
        assertEquals(1, revisionsOf(id.value().value()).size(),
                "the rejected write must not have recorded a revision");
    }

    /**
     * The unchanged-code path every real caller today ({@code actor_update}) exercises must keep
     * working: a {@code compareAndUpdate} that resubmits the identity's own current code is not a
     * collision with itself.
     */
    @Test
    void compareAndUpdateAcceptsAnUnchangedCode() {
        ActorId id = freshId();
        Actor original = new Actor(id, new ActorCode("ACTOR-1"), ActorType.HUMAN, "Sachbearbeiter", null);
        repository.create(PROJECT_A, original);

        Actor renamedOnly = new Actor(id, new ActorCode("ACTOR-1"), ActorType.HUMAN, "Antragsbearbeiter", null);
        repository.compareAndUpdate(PROJECT_A, currentHeadOf(original.code()), renamedOnly);

        assertEquals("Antragsbearbeiter",
                repository.findByCode(PROJECT_A, new ActorCode("ACTOR-1")).orElseThrow().name());
    }

    // ---- the SHACL gate actually fires (the reasoning decision, pinned) --------------------

    /**
     * The load-bearing check behind {@link KognioRdfActorRepositoryFactory#buildGate}'s reasoning:
     * {@code actshapes:ActorShape} targets the abstract {@code arkproc:Actor}, but this adapter only
     * ever asserts a concrete subclass. Were the axioms or the reasoning missing, the shape would
     * never fire and this candidate - a {@code HumanActor} with no {@code arknet:name} at all -
     * would pass silently. The rejection is therefore the proof that the abstract target is reached
     * through {@code rdfs:subClassOf} entailment.
     *
     * <p>The candidate is built directly rather than through {@link Actor}, whose compact
     * constructor forbids a missing name in the first place - a store-first (ADR-005) write is the
     * only way to produce one.</p>
     */
    @Test
    void gateRejectsAConcretelyTypedActorWithoutANameThroughSubclassReasoning() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(HUMAN_ACTOR_TYPE));

        ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    /** Same shape, the {@code sh:minLength} half: a one-character name is rejected too. */
    @Test
    void gateRejectsATooShortName() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(HUMAN_ACTOR_TYPE));
        candidate.add(subject, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral("x"));

        ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);

        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    /**
     * The counterpart the two rejections above need to mean anything: a well-formed actor passes the
     * very same gate, so those failures come from the shape and not from a gate that rejects
     * everything.
     */
    @Test
    void gateAcceptsAWellFormedActor() {
        repository.create(PROJECT_A, actor(new ActorCode("ACTOR-1"), ActorType.GROUP,
                "Der Fachbereich, der die Freigabe erteilt."));

        assertTrue(repository.findByCode(PROJECT_A, new ActorCode("ACTOR-1")).isPresent());
    }

    /**
     * The actor shapes stand on their own: an actor is not a {@code skos:Concept}, so none of the
     * glossary's own obligations (a {@code skos:prefLabel}, a {@code skos:definition}) apply to it.
     * That independence is the whole point of giving actors their own resource type - pinned here so
     * a future merge of the two shape files cannot reintroduce the definition requirement unnoticed.
     */
    @Test
    void anActorNeedsNoGlossaryDefinition() {
        Actor bare = actor(new ActorCode("ACTOR-1"), ActorType.LEGAL, null);

        repository.create(PROJECT_A, bare);

        Actor found = repository.findByCode(PROJECT_A, new ActorCode("ACTOR-1")).orElseThrow();
        assertNull(found.description());
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask("ASK { GRAPH <" + ACTOR_GRAPH + "> { <"
                    + bare.id().value().value() + "> a <http://www.w3.org/2004/02/skos/core#Concept> } }"),
                    "actor_add must not make the actor a glossary term as a side effect");
        }
    }

    // ---- row multiplication: SHACL gates writes, not the store ----------------------------

    /**
     * {@code actshapes:Actor-name} bounds the name to one value, but SHACL gates writes rather than
     * the store: a store-first (ADR-005) actor can carry two of them. The read path must group its
     * rows and pick deterministically instead of returning an arbitrary, unlogged one - and must log
     * the collapse, because {@code compareAndUpdate}'s replace-by-identity write would otherwise
     * silently drop the other value on the very next update.
     */
    @Test
    void findByCodeGroupsARowMultipliedNameAndLogsAWarning() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null);
        repository.create(PROJECT_A, stored);
        insertTriple(stored.id().value().value(), NAME_PROPERTY, "\"Antragsbearbeiter\"");

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            Actor found = repository.findByCode(PROJECT_A, new ActorCode("ACTOR-1")).orElseThrow();

            assertTrue(List.of("Sachbearbeiter", "Antragsbearbeiter").contains(found.name()),
                    "must return one of the two legally co-existing values, not throw or return null");
            assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("name")
                            && event.getFormattedMessage().contains("2 distinct values")),
                    "the collapsed second name must be logged, not silently discarded");
        } finally {
            detachLogAppender(logs);
        }
    }

    /** Same defect, the listing path: a row-multiplied actor must appear once, not twice. */
    @Test
    void findAllSurfacesARowMultipliedActorExactlyOnce() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null);
        repository.create(PROJECT_A, stored);
        insertTriple(stored.id().value().value(), DESCRIPTION_PROPERTY, "\"Eine Beschreibung.\"");
        insertTriple(stored.id().value().value(), DESCRIPTION_PROPERTY, "\"Eine zweite Beschreibung.\"");

        List<Actor> all = repository.findAll(PROJECT_A);

        assertEquals(1, all.size(), "one subject, one actor - regardless of how many rows it binds");
    }

    @Test
    void projectsAreIsolated() {
        repository.create(PROJECT_A, actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null));

        assertFalse(repository.findByCode(PROJECT_B, new ActorCode("ACTOR-1")).isPresent());
        assertTrue(repository.findAll(PROJECT_B).isEmpty());
    }

    // ---- findByIds: the batch lookup ResolveActors/uc_get/uc_list drive -------------------

    /**
     * The batch shape {@link ResolveActors} needs: known ids resolve, an id absent from the
     * project is simply missing from the result rather than an error.
     */
    @Test
    void findByIdsResolvesOnlyTheIdentitiesTheProjectHolds() {
        Actor first = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null);
        Actor second = actor(new ActorCode("ACTOR-2"), ActorType.SYSTEM, null);
        repository.create(PROJECT_A, first);
        repository.create(PROJECT_A, second);
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID());

        List<ResolveActors.ResolvedActor> resolved = repository.findByIds(
                PROJECT_A, List.of(first.id().value(), second.id().value(), unknown));

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveActors.ResolvedActor(first.id().value(), first.code())));
        assertTrue(resolved.contains(new ResolveActors.ResolvedActor(second.id().value(), second.code())));
    }

    @Test
    void findByIdsOfAnEmptyListQueriesNothing() {
        assertEquals(List.of(), repository.findByIds(PROJECT_A, List.of()));
    }

    // ---- revision trail (ADR-014): one revision per write, head queryable ------------------

    @Test
    void everyWriteRecordsExactlyOneRevisionAndMovesTheQueryableHead() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null);
        repository.create(PROJECT_A, stored);
        String subject = stored.id().value().value();

        List<String> afterCreate = revisionsOf(subject);
        assertEquals(1, afterCreate.size(), "create must record exactly one revision");
        assertEquals(afterCreate, headsOf(subject), "the head must point at the sole revision");

        repository.compareAndUpdate(PROJECT_A, new RevisionToken(afterCreate.get(0)),
                new Actor(stored.id(), stored.code(), stored.type(), "Antragsbearbeiter", null));

        assertEquals(2, revisionsOf(subject).size(), "update must record exactly one more revision");
        List<String> heads = headsOf(subject);
        assertEquals(1, heads.size(), "the head is rewritten, never duplicated");
        assertFalse(heads.get(0).equals(afterCreate.get(0)), "the head must have moved");
        assertEquals(List.of(afterCreate.get(0)), objectsOf(heads.get(0), ArkprovVocabulary.WAS_REVISION_OF),
                "the new head must supersede the previous one via prov:wasRevisionOf");
    }

    @Test
    void aRejectedWriteLeavesNoRevisionBehind() {
        repository.create(PROJECT_A, actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null));

        assertThrows(DuplicateActorCodeException.class,
                () -> repository.create(PROJECT_A, actor(new ActorCode("ACTOR-1"), ActorType.SYSTEM, null)));

        String all = "SELECT ?r WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?r a <" + ArkprovVocabulary.REVISION_TYPE + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertEquals(1, handle.sparqlQuery().select(all).count(),
                    "the rejected write must not have recorded a revision");
        }
    }

    // ---- compare-and-set -------------------------------------------------------------------

    @Test
    void findCurrentByCodeReturnsTheStateTogetherWithTheCurrentHead() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, "Eine Beschreibung.");
        repository.create(PROJECT_A, stored);

        ActorRepository.CurrentActor current =
                repository.findCurrentByCode(PROJECT_A, new ActorCode("ACTOR-1")).orElseThrow();

        assertEquals(stored, current.value());
        assertEquals(headsOf(stored.id().value().value()), List.of(current.head().value()));
    }

    @Test
    void findCurrentByCodeReturnsEmptyForAnUnknownCode() {
        assertEquals(Optional.empty(), repository.findCurrentByCode(PROJECT_A, new ActorCode("ACTOR-9")));
    }

    /**
     * The write side of the guard: a caller whose observed head is no longer current - because
     * another writer committed in between - is rejected instead of overwriting the change it never
     * saw, and its rejected write leaves neither a triple nor a revision behind.
     */
    @Test
    void compareAndUpdateRejectsAStaleHeadAndWritesNothing() {
        ActorId id = freshId();
        Actor original = new Actor(id, new ActorCode("ACTOR-1"), ActorType.HUMAN, "Sachbearbeiter", null);
        repository.create(PROJECT_A, original);
        RevisionToken staleHead = currentHeadOf(original.code());

        // A concurrent writer commits first, moving the head away from what the loser observed.
        repository.compareAndUpdate(PROJECT_A, staleHead,
                new Actor(id, original.code(), original.type(), "Renamed by the winner", null));

        Actor byTheLoser = new Actor(id, original.code(), original.type(), "Renamed by the loser", null);
        assertThrows(ActorConcurrentlyModifiedException.class,
                () -> repository.compareAndUpdate(PROJECT_A, staleHead, byTheLoser));

        assertEquals("Renamed by the winner",
                repository.findByCode(PROJECT_A, original.code()).orElseThrow().name());
        assertEquals(2, revisionsOf(id.value().value()).size(),
                "the rejected write must not have recorded a revision");
    }

    /**
     * An actor written before the funnel recorded revisions carries no head at all. Its {@code null}
     * head is a legitimate expectation, not a missing one - so a caller that observed "no head yet"
     * may still write, and the write records the first revision.
     */
    @Test
    void compareAndUpdateAcceptsANullHeadWhenTheResourceHasNoneYet() {
        ActorId id = freshId();
        Actor original = new Actor(id, new ActorCode("ACTOR-1"), ActorType.HUMAN, "Sachbearbeiter", null);
        repository.create(PROJECT_A, original);
        // Strips the head the create recorded, leaving the pre-ADR-014 state behind.
        String dropHead = "DELETE WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + id.value().value() + "> <" + ArkprovVocabulary.HEAD + "> ?head } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(dropHead);
                return null;
            });
        }
        assertNull(currentHeadOf(original.code()), "precondition: the actor carries no head");

        repository.compareAndUpdate(PROJECT_A, null,
                new Actor(id, original.code(), original.type(), "Antragsbearbeiter", null));

        assertEquals("Antragsbearbeiter", repository.findByCode(PROJECT_A, original.code()).orElseThrow().name());
        assertEquals(1, headsOf(id.value().value()).size(), "the write must have recorded a head again");
    }

    // ---- delete (issue #335) -----------------------------------------------------------------

    @Test
    void deleteRemovesTheActorAndItsTriples() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, "Bearbeitet eingehende Antraege.");
        repository.create(PROJECT_A, stored);

        repository.delete(PROJECT_A, stored.code());

        assertTrue(repository.findByCode(PROJECT_A, stored.code()).isEmpty());
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertFalse(handle.sparqlQuery().ask("ASK { GRAPH <" + ACTOR_GRAPH + "> { <"
                    + stored.id().value().value() + "> ?p ?o } }"), "no triple of the deleted actor may remain");
        }
    }

    @Test
    void deleteRejectsAnUnknownCode() {
        assertThrows(ActorNotFoundException.class, () -> repository.delete(PROJECT_A, new ActorCode("ACTOR-99")));
    }

    /**
     * The tombstone contract {@link de.hauschel.arknet.persistence.WriteFunnel#delete} documents:
     * the {@code arkprov:head} pointer is removed and the last revision is marked
     * {@code prov:invalidatedAtTime} rather than erased.
     */
    @Test
    void deleteTombstonesTheLastRevisionAndRemovesTheHead() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null);
        repository.create(PROJECT_A, stored);
        String subject = stored.id().value().value();
        String lastRevision = headsOf(subject).get(0);

        repository.delete(PROJECT_A, stored.code());

        assertTrue(headsOf(subject).isEmpty(), "the head pointer must be removed");
        String invalidated = "SELECT ?t WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + lastRevision + "> <" + ArkprovVocabulary.INVALIDATED_AT_TIME + "> ?t } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertEquals(1, handle.sparqlQuery().select(invalidated).count(),
                    "the last revision must be tombstoned, not erased");
        }
    }

    /**
     * {@link ActorReferencedException} blocks the delete while something still points at the actor
     * via {@code arkreq:primaryActor} - {@link KognioRdfActorRepository#rejectIfReferenced} searches
     * across every named graph, so a reference living outside {@link #ACTOR_GRAPH} (as a use-case
     * edge would) must still be found.
     */
    @Test
    void deleteRejectsAnActorStillReferencedAsPrimaryActor() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null);
        repository.create(PROJECT_A, stored);
        String reference = "INSERT DATA { GRAPH <https://example.org/uc> { <https://example.org/uc/1> <"
                + ArkreqVocabulary.PRIMARY_ACTOR + "> <" + stored.id().value().value() + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(reference);
                return null;
            });
        }

        assertThrows(ActorReferencedException.class, () -> repository.delete(PROJECT_A, stored.code()));
        assertTrue(repository.findByCode(PROJECT_A, stored.code()).isPresent(),
                "a rejected delete must leave the actor untouched");
    }

    @Test
    void projectsAreIsolatedForDelete() {
        Actor stored = actor(new ActorCode("ACTOR-1"), ActorType.HUMAN, null);
        repository.create(PROJECT_A, stored);

        assertThrows(ActorNotFoundException.class, () -> repository.delete(PROJECT_B, stored.code()));
        assertTrue(repository.findByCode(PROJECT_A, stored.code()).isPresent(),
                "a delete in another project must not touch this project's actor");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Inserts one raw triple directly into the actor named graph, bypassing the domain. */
    private void insertTriple(String subjectIri, String predicateIri, String objectTerm) {
        String insert = "INSERT DATA { GRAPH <" + ACTOR_GRAPH + "> { <" + subjectIri + "> <"
                + predicateIri + "> " + objectTerm + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Attaches a fresh {@link ListAppender} to {@code KognioRdfActorRepository}'s logger so a test
     * can assert a specific {@code WARN} was actually logged, not merely that the picked value
     * happens to be valid - the module carries no SLF4J binding otherwise, so without this the
     * production {@code LOG.warn} calls are silent NOP-logger no-ops even when reached.
     */
    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KognioRdfActorRepository.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KognioRdfActorRepository.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    /** The head a caller would observe right now - what a well-behaved compare-and-set passes. */
    private RevisionToken currentHeadOf(ActorCode code) {
        return repository.findCurrentByCode(PROJECT_A, code).orElseThrow().head();
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
