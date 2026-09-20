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
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.MissingDefaultLanguageException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Policy tests for {@link ActorService}: identity minting, code assignment, listing, lookup and
 * the text/language correction path {@code actor_update} backs (kogn-io/arknet#520), exercised
 * against an in-memory fake repository and a deterministic fake {@link ResourceIdFactory} -
 * mirrors {@code ConstraintServiceTest} in shape for the language tests.
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

    /** Adds an actor under the explicit tag {@code de}, the shorthand most tests here want. */
    private Actor add(ProjectId project, ActorType type, String name, String description) {
        return service.add(project, new NewActor(type, name, description, "de"), null);
    }

    @Test
    void addAssignsFirstBusinessCode() {
        Actor added = add(WS, ActorType.HUMAN, "Sachbearbeiter", "Bearbeitet eingehende Antraege im Backoffice.");

        assertEquals(new ActorCode("ACTOR-1"), added.code());
        assertEquals(ActorType.HUMAN, added.type());
        assertEquals("Sachbearbeiter", added.name());
        assertEquals("Bearbeitet eingehende Antraege im Backoffice.", added.description());
        assertEquals(added, repository.findByCode(WS, added.code(), null).orElseThrow());
    }

    @Test
    void addAcceptsAnAbsentDescription() {
        Actor added = add(WS, ActorType.SYSTEM, "PaymentService", null);

        assertNull(added.description());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        Actor first = add(WS, ActorType.HUMAN, "A", null);
        Actor second = add(WS, ActorType.HUMAN, "B", null);

        assertNotEquals(first.id(), second.id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addNumbersRunSequentially() {
        ActorCode a1 = add(WS, ActorType.HUMAN, "A", null).code();
        ActorCode a2 = add(WS, ActorType.HUMAN, "B", null).code();
        ActorCode a3 = add(WS, ActorType.HUMAN, "C", null).code();

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
        ActorCode human = add(WS, ActorType.HUMAN, "Sachbearbeiter", null).code();
        ActorCode system = add(WS, ActorType.SYSTEM, "PaymentService", null).code();
        ActorCode legal = add(WS, ActorType.LEGAL, "Zulieferer AG", null).code();
        ActorCode group = add(WS, ActorType.GROUP, "Fachbereich Vertrieb", null).code();

        assertEquals(List.of(new ActorCode("ACTOR-1"), new ActorCode("ACTOR-2"),
                new ActorCode("ACTOR-3"), new ActorCode("ACTOR-4")),
                List.of(human, system, legal, group));
    }

    /** Issue #258's rule, inherited here: an omitted {@code language} falls back to the project default. */
    @Test
    void addFallsBackToTheProjectDefaultLanguage() {
        Actor added = service.add(WS, new NewActor(ActorType.HUMAN, "Sachbearbeiter", null, null), "de");

        assertEquals("de", currentOf(added.code()).nameLanguage());
    }

    /**
     * A call with neither an explicit language nor a project default is rejected outright - before
     * any code is assigned - mirrors {@code ConstraintServiceTest}'s equivalent.
     */
    @Test
    void addRejectsAWriteWithNeitherAnExplicitNorADefaultLanguage() {
        assertThrows(MissingDefaultLanguageException.class,
                () -> service.add(WS, new NewActor(ActorType.HUMAN, "Sachbearbeiter", null, null), null));
        assertTrue(service.list(WS, null).isEmpty());
    }

    /**
     * An actor minted store-first may carry a {@code dcterms:identifier} that does not
     * follow the {@code ACTOR-N} scheme at all - the next-code computation must skip such a code as
     * if it contributed no running number, rather than letting the parse failure surface.
     */
    @Test
    void addSkipsNonNumericExistingCodesWhenComputingTheNextCode() {
        Actor storeFirst = new Actor(new ActorId(resourceIdFactory.newId()), new ActorCode("LEGACY-ACTOR"),
                ActorType.HUMAN, "Legacy", null);
        repository.create(WS, storeFirst, "de");

        ActorCode next = add(WS, ActorType.HUMAN, "A", null).code();

        assertEquals(new ActorCode("ACTOR-1"), next);
    }

    /**
     * Mutation-tests {@code nextCode}'s reliance on {@link ActorRepository#findAllCodes} rather than
     * {@link ActorRepository#findAll} (kogn-io/arknet#360): revert {@code nextCode} back to deriving
     * its maximum from {@code findAll} and this goes red - the seeded {@code ACTOR-2} holds the
     * project's highest number but is invisible to {@code findAll}, exactly as a store-first
     * actor without an {@code arknet:name} would be, so {@code add} would recompute
     * {@code ACTOR-2} and collide with a code that is still very much taken.
     */
    @Test
    void addSkipsOverACodeThatIsAssignedButNotCurrentlyMaterialisable() {
        add(WS, ActorType.HUMAN, "A", null);
        repository.seedUnmaterialisableCode(WS, new ActorCode("ACTOR-2"));

        ActorCode third = add(WS, ActorType.HUMAN, "C", null).code();

        assertEquals(new ActorCode("ACTOR-3"), third);
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        add(WS, ActorType.HUMAN, "A", null);

        Actor inOther = add(other, ActorType.HUMAN, "B", null);

        assertEquals(new ActorCode("ACTOR-1"), inOther.code());
        assertEquals(1, service.list(WS, null).size());
        assertEquals(1, service.list(other, null).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        add(WS, ActorType.HUMAN, "A", null);
        add(WS, ActorType.SYSTEM, "B", null);

        List<Actor> all = service.list(WS, null);

        assertEquals(2, all.size());
        assertEquals("A", all.get(0).name());
        assertEquals("B", all.get(1).name());
    }

    @Test
    void getReturnsPersistedActor() {
        ActorCode code = add(WS, ActorType.HUMAN, "Sachbearbeiter", null).code();

        assertTrue(service.get(WS, code, null).isPresent());
        assertEquals("Sachbearbeiter", service.get(WS, code, null).orElseThrow().name());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new ActorCode("ACTOR-99"), null).isPresent());
    }

    // --- actor_update (kogn-io/arknet#520) ---------------------------------------

    @Test
    void updateCorrectsNameAndDescription() {
        ActorCode code = add(WS, ActorType.HUMAN, "Sachbearbeiter",
                "Bearbeitet eingehende Antraege im Backoffice.").code();

        Actor updated = service.update(WS, code, "Antragsbearbeiter", "Neue Beschreibung.", "de", null);

        assertEquals("Antragsbearbeiter", updated.name());
        assertEquals("Neue Beschreibung.", updated.description());
        assertEquals(updated, service.get(WS, code, null).orElseThrow());
    }

    /** {@code null} leaves a field alone; it never means "remove". */
    @Test
    void updateLeavesAnOmittedFieldUntouched() {
        ActorCode code = add(WS, ActorType.HUMAN, "Sachbearbeiter",
                "Bearbeitet eingehende Antraege im Backoffice.").code();

        Actor updated = service.update(WS, code, "Antragsbearbeiter", null, "de", null);

        assertEquals("Antragsbearbeiter", updated.name());
        assertEquals("Bearbeitet eingehende Antraege im Backoffice.", updated.description());
    }

    /** Neither the type nor the code is reachable through {@code actor_update}. */
    @Test
    void updateChangesNeitherTypeNorCode() {
        Actor added = add(WS, ActorType.HUMAN, "Sachbearbeiter", null);

        Actor updated = service.update(WS, added.code(), "Antragsbearbeiter", null, "de", null);

        assertEquals(added.code(), updated.code());
        assertEquals(added.type(), updated.type());
        assertEquals(added.id(), updated.id());
    }

    @Test
    void updateThrowsWhenActorUnknown() {
        ActorNotFoundException ex = assertThrows(ActorNotFoundException.class,
                () -> service.update(WS, new ActorCode("ACTOR-42"), "x", null, "de", null));

        assertSame(WS, ex.projectId());
        assertEquals(new ActorCode("ACTOR-42"), ex.actorCode());
    }

    /** A correction must still satisfy the aggregate's own invariants. */
    @Test
    void updateRejectsABlankName() {
        ActorCode code = add(WS, ActorType.HUMAN, "Sachbearbeiter", null).code();

        assertThrows(IllegalArgumentException.class, () -> service.update(WS, code, "  ", null, "de", null));
    }

    /**
     * The two-call shape kogn-io/arknet#520 exists for: {@code actor_add} under one tag, then
     * {@code actor_update} under a second - each call carrying exactly one language.
     */
    @Test
    void updateUnderASecondLanguageRetagsTheTouchedFields() {
        ActorCode code = add(WS, ActorType.HUMAN, "Sachbearbeiter", "Beschreibung").code();

        service.update(WS, code, "Case Worker", "Description", "en", null);

        assertEquals("en", currentOf(code).nameLanguage());
        assertEquals("en", currentOf(code).descriptionLanguage());
    }

    /** A field this call does not name keeps the exact tag it was read under - never a retag. */
    @Test
    void updateLeavesAnUntouchedFieldsLanguageAlone() {
        ActorCode code = add(WS, ActorType.HUMAN, "Sachbearbeiter", "Beschreibung").code();

        service.update(WS, code, "Case Worker", null, "en", null);

        assertEquals("en", currentOf(code).nameLanguage());
        assertEquals("de", currentOf(code).descriptionLanguage());
    }

    /**
     * Naming a field and resending its already-current text with no {@code language} is a no-op,
     * so it never demands a {@code defaultLanguage} the project may not have.
     */
    @Test
    void updateResendingCurrentTextWithoutALanguageIsANoOp() {
        Actor added = add(WS, ActorType.HUMAN, "Sachbearbeiter", null);

        Actor result = service.update(WS, added.code(), "Sachbearbeiter", null, null, null);

        assertEquals(added, result);
        assertEquals("de", currentOf(added.code()).nameLanguage());
    }

    /**
     * ... but the same call <em>with</em> an explicit, different language is a genuine write: it
     * adds that language variant even though the {@link Actor} value is unchanged.
     */
    @Test
    void updateResendingCurrentTextUnderAnExplicitLanguageStillWrites() {
        ActorCode code = add(WS, ActorType.HUMAN, "Sachbearbeiter", null).code();

        service.update(WS, code, "Sachbearbeiter", null, "en", null);

        assertEquals("en", currentOf(code).nameLanguage());
    }

    @Test
    void updateFallsBackToTheProjectDefaultLanguage() {
        ActorCode code = add(WS, ActorType.HUMAN, "Sachbearbeiter", null).code();

        service.update(WS, code, "Case Worker", null, null, "en");

        assertEquals("en", currentOf(code).nameLanguage());
    }

    @Test
    void updateRejectsAChangedFieldWithNeitherAnExplicitNorADefaultLanguage() {
        ActorCode code = add(WS, ActorType.HUMAN, "Sachbearbeiter", null).code();

        assertThrows(MissingDefaultLanguageException.class,
                () -> service.update(WS, code, "Case Worker", null, null, null));
    }

    /** An update that changes nothing writes nothing and returns the actor as read. */
    @Test
    void updateWithNoChangeIsANoOp() {
        Actor added = add(WS, ActorType.HUMAN, "Sachbearbeiter",
                "Bearbeitet eingehende Antraege im Backoffice.");

        Actor unchanged = service.update(WS, added.code(), null, null, null, null);

        assertEquals(added, unchanged);
    }

    private ActorRepository.CurrentActor currentOf(ActorCode code) {
        return repository.findCurrentByCode(WS, code, null).orElseThrow();
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
