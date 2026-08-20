// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Policy tests for {@link ActorService}: identity minting, code assignment, listing, lookup and
 * correction rules, exercised against an in-memory fake repository and a deterministic fake
 * {@link ResourceIdFactory}.
 */
class ActorServiceTest {

    private static final ProjectId WS = new ProjectId("test-project");

    private InMemoryActorRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private ActorService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryActorRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        service = new ActorService(repository, resourceIdFactory);
    }

    @Test
    void addAssignsFirstBusinessCode() {
        Actor added = service.add(WS, new NewActor(ActorType.HUMAN, "Sachbearbeiter",
                "Bearbeitet eingehende Antraege im Backoffice."));

        assertEquals(new ActorCode("ACTOR-1"), added.code());
        assertEquals(ActorType.HUMAN, added.type());
        assertEquals("Sachbearbeiter", added.name());
        assertEquals("Bearbeitet eingehende Antraege im Backoffice.", added.description());
        assertEquals(added, repository.findByCode(WS, added.code()).orElseThrow());
    }

    @Test
    void addAcceptsAnAbsentDescription() {
        Actor added = service.add(WS, new NewActor(ActorType.SYSTEM, "PaymentService", null));

        assertNull(added.description());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        Actor first = service.add(WS, newActor());
        Actor second = service.add(WS, newActor());

        assertNotEquals(first.id(), second.id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addNumbersRunSequentially() {
        ActorCode a1 = service.add(WS, newActor()).code();
        ActorCode a2 = service.add(WS, newActor()).code();
        ActorCode a3 = service.add(WS, newActor()).code();

        assertEquals(new ActorCode("ACTOR-1"), a1);
        assertEquals(new ActorCode("ACTOR-2"), a2);
        assertEquals(new ActorCode("ACTOR-3"), a3);
    }

    /**
     * The single-counter decision, pinned: all four types share one {@code ACTOR-N} sequence, unlike
     * {@code ConstraintService}'s per-subtype {@code TCON-}/{@code BCON-}/{@code RCON-} counters.
     */
    @Test
    void addNumbersEveryTypeFromOneSharedCounter() {
        ActorCode human = service.add(WS, new NewActor(ActorType.HUMAN, "Sachbearbeiter", null)).code();
        ActorCode system = service.add(WS, new NewActor(ActorType.SYSTEM, "PaymentService", null)).code();
        ActorCode legal = service.add(WS, new NewActor(ActorType.LEGAL, "Zulieferer AG", null)).code();
        ActorCode group = service.add(WS, new NewActor(ActorType.GROUP, "Fachbereich Vertrieb", null)).code();

        assertEquals(List.of(new ActorCode("ACTOR-1"), new ActorCode("ACTOR-2"),
                new ActorCode("ACTOR-3"), new ActorCode("ACTOR-4")),
                List.of(human, system, legal, group));
    }

    /**
     * An actor minted store-first (ADR-005) may carry a {@code dcterms:identifier} that does not
     * follow the {@code ACTOR-N} scheme at all - the next-code computation must skip such a code as
     * if it contributed no running number, rather than letting the parse failure surface.
     */
    @Test
    void addSkipsNonNumericExistingCodesWhenComputingTheNextCode() {
        Actor storeFirst = new Actor(new ActorId(resourceIdFactory.newId()), new ActorCode("LEGACY-ACTOR"),
                ActorType.HUMAN, "Legacy", null);
        repository.create(WS, storeFirst);

        ActorCode next = service.add(WS, newActor()).code();

        assertEquals(new ActorCode("ACTOR-1"), next);
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(WS, newActor());

        Actor inOther = service.add(other, newActor());

        assertEquals(new ActorCode("ACTOR-1"), inOther.code());
        assertEquals(1, service.list(WS).size());
        assertEquals(1, service.list(other).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewActor(ActorType.HUMAN, "A", null));
        service.add(WS, new NewActor(ActorType.SYSTEM, "B", null));

        List<Actor> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals("A", all.get(0).name());
        assertEquals("B", all.get(1).name());
    }

    @Test
    void getReturnsPersistedActor() {
        ActorCode code = service.add(WS, newActor()).code();

        assertTrue(service.get(WS, code).isPresent());
        assertEquals("Sachbearbeiter", service.get(WS, code).orElseThrow().name());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new ActorCode("ACTOR-99")).isPresent());
    }

    @Test
    void updateCorrectsNameAndDescription() {
        ActorCode code = service.add(WS, newActor()).code();

        Actor updated = service.update(WS, code, "Antragsbearbeiter", "Neue Beschreibung.");

        assertEquals("Antragsbearbeiter", updated.name());
        assertEquals("Neue Beschreibung.", updated.description());
        assertEquals(updated, service.get(WS, code).orElseThrow());
    }

    /** {@code null} leaves a field alone; it never means "remove". */
    @Test
    void updateLeavesAnOmittedFieldUntouched() {
        ActorCode code = service.add(WS, newActor()).code();

        Actor updated = service.update(WS, code, "Antragsbearbeiter", null);

        assertEquals("Antragsbearbeiter", updated.name());
        assertEquals("Bearbeitet eingehende Antraege im Backoffice.", updated.description());
    }

    /** Neither the type nor the code is reachable through {@code actor_update}. */
    @Test
    void updateChangesNeitherTypeNorCode() {
        Actor added = service.add(WS, newActor());

        Actor updated = service.update(WS, added.code(), "Antragsbearbeiter", null);

        assertEquals(added.code(), updated.code());
        assertEquals(added.type(), updated.type());
        assertEquals(added.id(), updated.id());
    }

    /** An update that changes nothing writes nothing and returns the actor as read. */
    @Test
    void updateWithNoChangeIsANoOp() {
        Actor added = service.add(WS, newActor());

        Actor unchanged = service.update(WS, added.code(), added.name(), added.description());

        assertEquals(added, unchanged);
    }

    @Test
    void updateThrowsWhenActorUnknown() {
        ActorNotFoundException ex = assertThrows(ActorNotFoundException.class,
                () -> service.update(WS, new ActorCode("ACTOR-42"), "x", null));

        assertSame(WS, ex.projectId());
        assertEquals(new ActorCode("ACTOR-42"), ex.actorCode());
    }

    /** A correction must still satisfy the aggregate's own invariants. */
    @Test
    void updateRejectsABlankName() {
        ActorCode code = service.add(WS, newActor()).code();

        assertThrows(IllegalArgumentException.class, () -> service.update(WS, code, "  ", null));
    }

    private static NewActor newActor() {
        return new NewActor(ActorType.HUMAN, "Sachbearbeiter",
                "Bearbeitet eingehende Antraege im Backoffice.");
    }

    /** Deterministic fake minting sequential opaque ids, so tests never depend on randomness. */
    private static final class FakeResourceIdFactory implements ResourceIdFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public ResourceId newId() {
            return ResourceId.of("https://w3id.org/arknet/id/fake-" + counter.incrementAndGet());
        }

        int mintedCount() {
            return counter.get();
        }
    }
}
