// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.RoleDisplayFallback;
import de.hauschel.arknet.actor.domain.RoleNotFoundException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Regression tests for the two concurrency races {@link RoleService} has to absorb - mirrors
 * {@code ActorServiceConcurrencyTest} exactly, including the deterministic, no-real-threads
 * decorator technique it uses to pin the interleaving.
 */
class RoleServiceConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");
    private static final String DEFAULT_LANGUAGE = "en";

    private InMemoryRoleRepository store;
    private InMemoryActorRepository actorRepository;
    private SequentialResourceIdFactory resourceIdFactory;
    private RoleService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryRoleRepository();
        actorRepository = new InMemoryActorRepository();
        resourceIdFactory = new SequentialResourceIdFactory();
        otherCaller = new RoleService(store, actorRepository, resourceIdFactory);
    }

    @Test
    void concurrentAddCallsBothGetDistinctCodesInsteadOfOneFailing() {
        RaceOnFirstFindAllCodesRepository racing =
                new RaceOnFirstFindAllCodesRepository(store, () -> otherCaller.add(WS, newRole(), DEFAULT_LANGUAGE));
        RoleService underTest = new RoleService(racing, actorRepository, resourceIdFactory);

        RoleDetail result = underTest.add(WS, newRole(), DEFAULT_LANGUAGE);

        assertEquals(new RoleCode("ROLE-2"), result.role().code());
        assertEquals(2, store.findAll(WS, null).size());
        assertTrue(store.findAll(WS, null).stream().map(Role::code).toList()
                .containsAll(List.of(new RoleCode("ROLE-1"), new RoleCode("ROLE-2"))));
    }

    /**
     * Lost update: a concurrent {@code role_update} correcting the description commits between
     * this caller's read and its own write. The retry must re-read, so the name correction lands on
     * top of the description correction instead of reverting it.
     */
    @Test
    void concurrentUpdateCallsForDifferentFieldsBothSurvive() {
        RoleCode code = otherCaller.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.update(WS, code, null, "Beschreibung des anderen Aufrufers.", null, "en",
                        DEFAULT_LANGUAGE));
        RoleService underTest = new RoleService(racing, actorRepository, resourceIdFactory);

        RoleDetail result = underTest.update(WS, code, "Senior Requirements Engineer", null, null, "en",
                DEFAULT_LANGUAGE);

        assertEquals("Senior Requirements Engineer", result.role().name());
        assertEquals("Beschreibung des anderen Aufrufers.", result.role().description(),
                "the retry must build on the state it re-read, not on its stale first read");
        Role stored = store.findByCode(WS, code, null).orElseThrow();
        assertEquals("Senior Requirements Engineer", stored.name());
        assertEquals("Beschreibung des anderen Aufrufers.", stored.description());
    }

    @Test
    void updateGivesUpAfterExhaustingRetriesAgainstPermanentContention() {
        RoleCode code = otherCaller.add(WS, newRole(), DEFAULT_LANGUAGE).role().code();
        RoleService underTest = new RoleService(new AlwaysConflictingRepository(store), actorRepository,
                resourceIdFactory);

        assertThrows(RoleConcurrentlyModifiedException.class,
                () -> underTest.update(WS, code, "Senior Requirements Engineer", null, null, "en", DEFAULT_LANGUAGE));
    }

    @Test
    void anUnchangedUpdateWritesNothingEvenUnderPermanentContention() {
        RoleDetail added = otherCaller.add(WS, newRole(), DEFAULT_LANGUAGE);
        RoleService underTest = new RoleService(new AlwaysConflictingRepository(store), actorRepository,
                resourceIdFactory);

        RoleDetail result = underTest.update(WS, added.role().code(), added.role().name(),
                added.role().description(), null, null, DEFAULT_LANGUAGE);

        assertEquals(added.role(), result.role());
    }

    /**
     * {@code arkproc:filledBy} is a set in the store: naming the same two occupants in the other
     * order states the same occupancy and must therefore write nothing. Pinned against a store that
     * reports a conflict on every write, so a write would surface as
     * {@link RoleConcurrentlyModifiedException} rather than as a silent extra PROV revision.
     */
    @Test
    void aReorderedButOtherwiseIdenticalOccupancyIsStillANoOp() {
        actorRepository.create(WS, actor("ACTOR-1", "Erstbesetzung"));
        actorRepository.create(WS, actor("ACTOR-2", "Zweitbesetzung"));
        RoleDetail added = otherCaller.add(WS, new NewRole("Case Handler", null, List.of("ACTOR-1", "ACTOR-2"), "en"),
                DEFAULT_LANGUAGE);
        RoleService underTest = new RoleService(new AlwaysConflictingRepository(store), actorRepository,
                resourceIdFactory);

        RoleDetail result = underTest.update(WS, added.role().code(), null, null, List.of("ACTOR-2", "ACTOR-1"), null,
                DEFAULT_LANGUAGE);

        assertEquals(added.role(), result.role());
    }

    private static Actor actor(String code, String name) {
        return new Actor(new ActorId(ResourceId.of("https://w3id.org/arknet/id/" + code.toLowerCase())),
                new ActorCode(code), ActorType.HUMAN, name, null);
    }

    private static NewRole newRole() {
        return new NewRole("Requirements Engineer", "Writes and maintains requirements.", null, "en");
    }

    /** Deterministic fake minting sequential opaque ids, so tests never depend on randomness. */
    private static final class SequentialResourceIdFactory implements ResourceIdFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public ResourceId newId() {
            return ResourceId.of("https://w3id.org/arknet/id/fake-" + counter.incrementAndGet());
        }
    }

    /**
     * Decorator that runs {@code injection} exactly once, synchronously, right after the first
     * {@link #findAllCodes} call returns - mirrors {@code ActorServiceConcurrencyTest}'s own
     * decorator exactly.
     */
    private static final class RaceOnFirstFindAllCodesRepository implements RoleRepository {

        private final RoleRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstFindAllCodesRepository(RoleRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, Role role, String language) {
            delegate.create(projectId, role, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Role updated,
                String nameLanguage, String descriptionLanguage, String defaultLanguage) {
            delegate.compareAndUpdate(projectId, expectedHead, updated, nameLanguage, descriptionLanguage,
                    defaultLanguage);
        }

        @Override
        public Optional<Role> findByCode(ProjectId projectId, RoleCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentRole> findCurrentByCode(ProjectId projectId, RoleCode code, String defaultLanguage) {
            return delegate.findCurrentByCode(projectId, code, defaultLanguage);
        }

        @Override
        public List<Role> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public Map<RoleCode, RoleDisplayFallback> findAllDisplayFallback(ProjectId projectId, String displayLocale) {
            return delegate.findAllDisplayFallback(projectId, displayLocale);
        }

        @Override
        public void delete(ProjectId projectId, RoleCode code) {
            delegate.delete(projectId, code);
        }

        @Override
        public List<RoleCode> findAllCodes(ProjectId projectId) {
            List<RoleCode> result = delegate.findAllCodes(projectId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<RoleCode> findRetainedCodes(ProjectId projectId) {
            return delegate.findRetainedCodes(projectId);
        }
    }

    /**
     * Decorator that runs {@code injection} exactly once, synchronously, right after the first
     * {@link #findCurrentByCode} call returns - mirrors {@code ActorServiceConcurrencyTest}'s own
     * decorator exactly.
     */
    private static final class RaceOnFirstReadRepository implements RoleRepository {

        private final RoleRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstReadRepository(RoleRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, Role role, String language) {
            delegate.create(projectId, role, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Role updated,
                String nameLanguage, String descriptionLanguage, String defaultLanguage) {
            delegate.compareAndUpdate(projectId, expectedHead, updated, nameLanguage, descriptionLanguage,
                    defaultLanguage);
        }

        @Override
        public Optional<Role> findByCode(ProjectId projectId, RoleCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentRole> findCurrentByCode(ProjectId projectId, RoleCode code, String defaultLanguage) {
            Optional<CurrentRole> result = delegate.findCurrentByCode(projectId, code, defaultLanguage);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<Role> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public Map<RoleCode, RoleDisplayFallback> findAllDisplayFallback(ProjectId projectId, String displayLocale) {
            return delegate.findAllDisplayFallback(projectId, displayLocale);
        }

        @Override
        public void delete(ProjectId projectId, RoleCode code) {
            delegate.delete(projectId, code);
        }

        @Override
        public List<RoleCode> findAllCodes(ProjectId projectId) {
            return delegate.findAllCodes(projectId);
        }

        @Override
        public List<RoleCode> findRetainedCodes(ProjectId projectId) {
            return delegate.findRetainedCodes(projectId);
        }
    }

    /** A repository whose {@code compareAndUpdate} always reports a conflict, never applying. */
    private static final class AlwaysConflictingRepository implements RoleRepository {

        private final RoleRepository delegate;

        AlwaysConflictingRepository(RoleRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void create(ProjectId projectId, Role role, String language) {
            delegate.create(projectId, role, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Role updated,
                String nameLanguage, String descriptionLanguage, String defaultLanguage) {
            delegate.findByCode(projectId, updated.code(), null)
                    .orElseThrow(() -> new RoleNotFoundException(projectId, updated.code()));
            throw new RoleConcurrentlyModifiedException(projectId, updated.code());
        }

        @Override
        public Optional<Role> findByCode(ProjectId projectId, RoleCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentRole> findCurrentByCode(ProjectId projectId, RoleCode code, String defaultLanguage) {
            return delegate.findCurrentByCode(projectId, code, defaultLanguage);
        }

        @Override
        public List<Role> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public Map<RoleCode, RoleDisplayFallback> findAllDisplayFallback(ProjectId projectId, String displayLocale) {
            return delegate.findAllDisplayFallback(projectId, displayLocale);
        }

        @Override
        public void delete(ProjectId projectId, RoleCode code) {
            delegate.delete(projectId, code);
        }

        @Override
        public List<RoleCode> findAllCodes(ProjectId projectId) {
            return delegate.findAllCodes(projectId);
        }

        @Override
        public List<RoleCode> findRetainedCodes(ProjectId projectId) {
            return delegate.findRetainedCodes(projectId);
        }
    }
}
