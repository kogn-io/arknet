// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleNotFoundException;
import de.hauschel.arknet.kernel.MissingDefaultLanguageException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Policy tests for {@link RoleService}: identity minting, its own {@code ROLE-N} code counter,
 * listing, lookup, correction rules, language resolution and {@code filledBy} resolution against
 * the actor register - mirrors {@code ConstraintServiceTest}'s language coverage and
 * {@code ActorServiceTest}'s structure.
 */
class RoleServiceTest {

    private static final ProjectId WS = new ProjectId("test-project");
    private static final String DEFAULT_LANGUAGE = "en";

    private InMemoryRoleRepository repository;
    private InMemoryActorRepository actorRepository;
    private FakeResourceIdFactory resourceIdFactory;
    private RoleService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRoleRepository();
        actorRepository = new InMemoryActorRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        service = new RoleService(repository, actorRepository, resourceIdFactory);
    }

    @Test
    void addAssignsFirstBusinessCode() {
        RoleDetail added = service.add(WS, new NewRole("Requirements Engineer",
                "Writes and maintains requirements.", null, "en"), DEFAULT_LANGUAGE);

        assertEquals(new RoleCode("ROLE-1"), added.role().code());
        assertEquals("Requirements Engineer", added.role().name());
        assertEquals("Writes and maintains requirements.", added.role().description());
        assertTrue(added.filledByActors().isEmpty());
        assertEquals(added.role(), repository.findByCode(WS, added.role().code(), null).orElseThrow());
    }

    @Test
    void addAcceptsAnAbsentDescription() {
        RoleDetail added = service.add(WS, new NewRole("Architect", null, null, "en"), DEFAULT_LANGUAGE);

        assertNull(added.role().description());
    }

    /** No explicit language falls back to the project's configured default (issue #258's rule, ported). */
    @Test
    void addFallsBackToTheProjectDefaultLanguageWhenNoneIsGiven() {
        RoleDetail added = service.add(WS, new NewRole("Architect", null, null, null), DEFAULT_LANGUAGE);

        assertEquals("Architect", added.role().name());
    }

    /** No explicit language and no project default rejects the call rather than writing untagged. */
    @Test
    void addRejectsWhenNeitherLanguageNorProjectDefaultIsGiven() {
        assertThrows(MissingDefaultLanguageException.class,
                () -> service.add(WS, new NewRole("Architect", null, null, null), null));
    }

    /** {@code ROLE-N} is a counter of its own, unrelated to {@code ACTOR-N}. */
    @Test
    void addNumbersRunSequentiallyIndependentOfActorCodes() {
        actorRepository.create(WS, actor("ACTOR-1", "Someone"));

        RoleCode first = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();
        RoleCode second = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();

        assertEquals(new RoleCode("ROLE-1"), first);
        assertEquals(new RoleCode("ROLE-2"), second);
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        RoleDetail first = service.add(WS, newRole(), DEFAULT_LANGUAGE);
        RoleDetail second = service.add(WS, newRole(), DEFAULT_LANGUAGE);

        assertFalse(first.role().id().equals(second.role().id()));
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(WS, newRole(), DEFAULT_LANGUAGE);

        RoleDetail inOther = service.add(other, newRole(), DEFAULT_LANGUAGE);

        assertEquals(new RoleCode("ROLE-1"), inOther.role().code());
        assertEquals(1, service.list(WS, null).size());
        assertEquals(1, service.list(other, null).size());
    }

    /** Mutation-tests {@code nextCode}'s reliance on {@code findAllCodes} - mirrors {@code ActorServiceTest}. */
    @Test
    void addSkipsOverACodeThatIsAssignedButNotCurrentlyMaterialisable() {
        service.add(WS, newRole(), DEFAULT_LANGUAGE);
        repository.seedUnmaterialisableCode(WS, new RoleCode("ROLE-2"));

        RoleCode third = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();

        assertEquals(new RoleCode("ROLE-3"), third);
    }

    @Test
    void addResolvesFilledByActorCodesToOpaqueIdentitiesAndBackToNames() {
        Actor added = actor("ACTOR-1", "Sachbearbeiter");
        actorRepository.create(WS, added);

        RoleDetail role = service.add(WS, new NewRole("Case Handler", null, List.of("ACTOR-1"), "en"),
                DEFAULT_LANGUAGE);

        assertEquals(List.of(added.id()), role.role().filledBy());
        assertEquals(1, role.filledByActors().size());
        assertEquals(new ActorCode("ACTOR-1"), role.filledByActors().get(0).code());
        assertEquals("Sachbearbeiter", role.filledByActors().get(0).name());
    }

    @Test
    void addRejectsAnUnknownActorCodeInFilledByBeforeWritingAnything() {
        ActorNotFoundException ex = assertThrows(ActorNotFoundException.class,
                () -> service.add(WS, new NewRole("Case Handler", null, List.of("ACTOR-99"), "en"),
                        DEFAULT_LANGUAGE));

        assertSame(WS, ex.projectId());
        assertTrue(service.list(WS, null).isEmpty(), "the role must not be written when a filledBy code is unknown");
    }

    @Test
    void addAcceptsAnUnfilledRole() {
        RoleDetail added = service.add(WS, new NewRole("Architect", null, null, "en"), DEFAULT_LANGUAGE);

        assertTrue(added.role().filledBy().isEmpty());
        assertTrue(added.filledByActors().isEmpty());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewRole("A", null, null, "en"), DEFAULT_LANGUAGE);
        service.add(WS, new NewRole("B", null, null, "en"), DEFAULT_LANGUAGE);

        List<RoleDetail> all = service.list(WS, null);

        assertEquals(2, all.size());
        assertEquals("A", all.get(0).role().name());
        assertEquals("B", all.get(1).role().name());
    }

    @Test
    void getReturnsPersistedRole() {
        RoleCode code = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();

        assertTrue(service.get(WS, code, null).isPresent());
        assertEquals("Requirements Engineer", service.get(WS, code, null).orElseThrow().role().name());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new RoleCode("ROLE-99"), null).isPresent());
    }

    @Test
    void updateCorrectsNameAndDescription() {
        RoleCode code = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();

        RoleDetail updated = service.update(WS, code, "Senior Requirements Engineer", "New description.", null,
                "en", DEFAULT_LANGUAGE);

        assertEquals("Senior Requirements Engineer", updated.role().name());
        assertEquals("New description.", updated.role().description());
    }

    @Test
    void updateLeavesAnOmittedFieldUntouched() {
        RoleCode code = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();

        RoleDetail updated = service.update(WS, code, "Senior Requirements Engineer", null, null, "en",
                DEFAULT_LANGUAGE);

        assertEquals("Senior Requirements Engineer", updated.role().name());
        assertEquals("Writes and maintains requirements.", updated.role().description());
    }

    /** Naming a field with an explicit, different language is a real write, not a no-op (issue #271). */
    @Test
    void updateNamingAFieldWithOnlyADifferentLanguageIsAWrite() {
        RoleCode code = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();

        RoleDetail updated = service.update(WS, code, "Requirements Engineer", null, null, "de", DEFAULT_LANGUAGE);

        assertEquals("Requirements Engineer", updated.role().name());
    }

    /** {@code null} for {@code filledByActorCodes} leaves the occupancy untouched. */
    @Test
    void updateWithNullFilledByLeavesOccupancyUntouched() {
        actorRepository.create(WS, actor("ACTOR-1", "Sachbearbeiter"));
        RoleCode code = service.add(WS, new NewRole("Case Handler", null, List.of("ACTOR-1"), "en"),
                DEFAULT_LANGUAGE).role().code();

        RoleDetail updated = service.update(WS, code, "Renamed", null, null, "en", DEFAULT_LANGUAGE);

        assertEquals(1, updated.role().filledBy().size());
        assertEquals(1, updated.filledByActors().size());
    }

    /**
     * An empty list is the explicit, unambiguous signal to remove every occupant - the same
     * tri-state {@code adr_update}'s reference lists carry.
     */
    @Test
    void updateWithEmptyFilledByClearsEveryOccupant() {
        actorRepository.create(WS, actor("ACTOR-1", "Sachbearbeiter"));
        RoleCode code = service.add(WS, new NewRole("Case Handler", null, List.of("ACTOR-1"), "en"),
                DEFAULT_LANGUAGE).role().code();

        RoleDetail updated = service.update(WS, code, null, null, List.of(), null, DEFAULT_LANGUAGE);

        assertTrue(updated.role().filledBy().isEmpty());
        assertTrue(updated.filledByActors().isEmpty());
    }

    @Test
    void updateWithANonEmptyListReplacesOccupancyWholesale() {
        Actor first = actor("ACTOR-1", "Erstbesetzung");
        Actor second = actor("ACTOR-2", "Zweitbesetzung");
        actorRepository.create(WS, first);
        actorRepository.create(WS, second);
        RoleCode code = service.add(WS, new NewRole("Case Handler", null, List.of("ACTOR-1"), "en"),
                DEFAULT_LANGUAGE).role().code();

        RoleDetail updated = service.update(WS, code, null, null, List.of("ACTOR-2"), null, DEFAULT_LANGUAGE);

        assertEquals(List.of(second.id()), updated.role().filledBy());
    }

    @Test
    void updateRejectsAnUnknownActorCodeInFilledByBeforeWritingAnything() {
        RoleCode code = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();

        assertThrows(ActorNotFoundException.class,
                () -> service.update(WS, code, null, null, List.of("ACTOR-99"), null, DEFAULT_LANGUAGE));
        assertTrue(service.get(WS, code, null).orElseThrow().role().filledBy().isEmpty());
    }

    @Test
    void updateChangesNeitherCode() {
        RoleDetail added = service.add(WS, newRole(), DEFAULT_LANGUAGE);

        RoleDetail updated = service.update(WS, added.role().code(), "Renamed", null, null, "en", DEFAULT_LANGUAGE);

        assertEquals(added.role().code(), updated.role().code());
        assertEquals(added.role().id(), updated.role().id());
    }

    @Test
    void updateWithNoChangeAtAllIsANoOp() {
        RoleDetail added = service.add(WS, newRole(), DEFAULT_LANGUAGE);

        RoleDetail unchanged = service.update(WS, added.role().code(), added.role().name(),
                added.role().description(), null, null, DEFAULT_LANGUAGE);

        assertEquals(added.role(), unchanged.role());
    }

    @Test
    void updateThrowsWhenRoleUnknown() {
        RoleNotFoundException ex = assertThrows(RoleNotFoundException.class,
                () -> service.update(WS, new RoleCode("ROLE-42"), "x", null, null, "en", DEFAULT_LANGUAGE));

        assertSame(WS, ex.projectId());
        assertEquals(new RoleCode("ROLE-42"), ex.roleCode());
    }

    @Test
    void updateRejectsABlankName() {
        RoleCode code = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();

        assertThrows(IllegalArgumentException.class,
                () -> service.update(WS, code, "  ", null, null, "en", DEFAULT_LANGUAGE));
    }

    @Test
    void deleteRemovesTheRole() {
        RoleCode code = service.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();

        service.delete(WS, code);

        assertFalse(service.get(WS, code, null).isPresent());
    }

    @Test
    void deleteThrowsWhenRoleUnknown() {
        assertThrows(RoleNotFoundException.class, () -> service.delete(WS, new RoleCode("ROLE-42")));
    }

    private static Actor actor(String code, String name) {
        return new Actor(new ActorId(ResourceId.of("https://w3id.org/arknet/id/" + code.toLowerCase())),
                new ActorCode(code), ActorType.HUMAN, name, null);
    }

    private static NewRole newRole() {
        return new NewRole("Requirements Engineer", "Writes and maintains requirements.", null, "en");
    }

    /** Deterministic fake minting sequential opaque ids, so tests never depend on randomness. */
    private static final class FakeResourceIdFactory implements ResourceIdFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public ResourceId newId() {
            return ResourceId.of("https://w3id.org/arknet/id/fake-" + counter.incrementAndGet());
        }
    }
}
