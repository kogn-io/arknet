// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Regression tests for the two concurrency races {@link ActorService} has to absorb - the same pair
 * every other bounded context here had to be retrofitted with, built in from the first commit.
 *
 * <p>Code assignment: {@link ActorService#add} computes the next business code ({@code ACTOR-N})
 * client-side against a read and only then {@code create()}s it, so two racing {@code actor_add}
 * calls in the same project compute the same candidate. Without {@code CodeAssignment}'s retry, one
 * of two well-formed callers would see the out-adapter's in-transaction uniqueness guard as a
 * caller-visible {@link de.hauschel.arknet.actor.domain.DuplicateActorCodeException}.</p>
 *
 * <p>Lost update: {@link ActorService#update} reads the actor and writes it back wholesale, so
 * without the compare-and-set guard two racing {@code actor_update} calls on the same actor - one
 * correcting the name, one the description - would silently lose whichever committed first.</p>
 *
 * <p>Both races are reproduced deterministically, without real threads: an
 * {@link ActorRepository} decorator runs an "other caller"'s complete round trip exactly once, at
 * the precise point where a concurrent writer's commit would land - after the first
 * {@code findAllCodes} (which {@code nextCode()} reads, kogn-io/arknet#360) for the code-assignment
 * race, after the first {@code findCurrentByCode} for the lost-update race. That pins the exact
 * interleaving instead of relying on thread scheduling, which would make these tests flaky.</p>
 */
class ActorServiceConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");

    private InMemoryActorRepository store;
    /**
     * Shared across {@link #otherCaller} and the "under test" service, mirroring the composition
     * root, which wires exactly one {@link ResourceIdFactory} bean shared by all concurrent callers.
     */
    private SequentialResourceIdFactory resourceIdFactory;
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private ActorService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryActorRepository();
        resourceIdFactory = new SequentialResourceIdFactory();
        otherCaller = new ActorService(store, resourceIdFactory);
    }

    @Test
    void concurrentAddCallsBothGetDistinctCodesInsteadOfOneFailing() {
        RaceOnFirstFindAllCodesRepository racing =
                new RaceOnFirstFindAllCodesRepository(store, () -> otherCaller.add(WS, newActor(), "de"));
        ActorService underTest = new ActorService(racing, resourceIdFactory);

        Actor result = underTest.add(WS, newActor(), "de");

        assertEquals(new ActorCode("ACTOR-2"), result.code());
        assertEquals(2, store.findAll(WS, null).size());
        assertTrue(store.findAll(WS, null).stream().map(Actor::code).toList()
                .containsAll(List.of(new ActorCode("ACTOR-1"), new ActorCode("ACTOR-2"))));
    }

    /**
     * Lost update: a concurrent {@code actor_update} correcting the description commits between
     * this caller's read and its own write. The retry must re-read, so the name correction lands on
     * top of the description correction instead of reverting it.
     */
    @Test
    void concurrentUpdateCallsForDifferentFieldsBothSurvive() {
        ActorCode code = otherCaller.add(WS, newActor(), "de").code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.update(WS, code, null, "Beschreibung des anderen Aufrufers.", "de", null));
        ActorService underTest = new ActorService(racing, resourceIdFactory);

        Actor result = underTest.update(WS, code, "Antragsbearbeiter", null, "de", null);

        assertEquals("Antragsbearbeiter", result.name());
        assertEquals("Beschreibung des anderen Aufrufers.", result.description(),
                "the retry must build on the state it re-read, not on its stale first read");
        Actor stored = store.findByCode(WS, code, null).orElseThrow();
        assertEquals("Antragsbearbeiter", stored.name());
        assertEquals("Beschreibung des anderen Aufrufers.", stored.description());
    }

    /**
     * A read-modify-write that keeps losing the race on every single attempt (a repository whose
     * {@code compareAndUpdate} always reports a conflict) must fail loudly with
     * {@link ActorConcurrentlyModifiedException} instead of looping forever.
     */
    @Test
    void updateGivesUpAfterExhaustingRetriesAgainstPermanentContention() {
        ActorCode code = otherCaller.add(WS, newActor(), "de").code();
        ActorService underTest = new ActorService(new AlwaysConflictingRepository(store), resourceIdFactory);

        assertThrows(ActorConcurrentlyModifiedException.class,
                () -> underTest.update(WS, code, "Antragsbearbeiter", null, "de", null));
    }

    /**
     * An update that changes nothing stays a no-op: the derived state equals the state read, so no
     * write is attempted at all - not even a compare-and-set one that could fail.
     */
    @Test
    void anUnchangedUpdateWritesNothingEvenUnderPermanentContention() {
        Actor added = otherCaller.add(WS, newActor(), "de");
        ActorService underTest = new ActorService(new AlwaysConflictingRepository(store), resourceIdFactory);

        Actor result = underTest.update(WS, added.code(), null, null, null, null);

        assertEquals(added, result);
    }

    private static NewActor newActor() {
        return new NewActor(ActorType.HUMAN, "Sachbearbeiter",
                "Bearbeitet eingehende Antraege im Backoffice.", "de");
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
     * {@link #findAllCodes} call returns - {@code nextCode()} reads via {@code findAllCodes} rather
     * than {@code findAll} (kogn-io/arknet#360, see {@link ActorRepository#findAllCodes}'s own
     * javadoc), so this simulates a concurrent {@code actor_add} committing between this caller's
     * code computation and its own {@code create()}. Every other call delegates unchanged via
     * {@link ForwardingActorRepository}.
     */
    private static final class RaceOnFirstFindAllCodesRepository extends ForwardingActorRepository {

        private final Runnable injection;
        private boolean injected;

        RaceOnFirstFindAllCodesRepository(ActorRepository delegate, Runnable injection) {
            super(delegate);
            this.injection = injection;
        }

        @Override
        public List<ActorCode> findAllCodes(ProjectId projectId) {
            List<ActorCode> result = delegate.findAllCodes(projectId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }
    }

    /**
     * Decorator that runs {@code injection} exactly once, synchronously, right after the first
     * {@link #findCurrentByCode} call returns - simulating a concurrent caller whose own complete
     * read-modify-write round trip commits in the window between this caller's read and its own
     * write. Every other call, including every subsequent {@code findCurrentByCode} the retry
     * issues, delegates unchanged via {@link ForwardingActorRepository}.
     */
    private static final class RaceOnFirstReadRepository extends ForwardingActorRepository {

        private final Runnable injection;
        private boolean injected;

        RaceOnFirstReadRepository(ActorRepository delegate, Runnable injection) {
            super(delegate);
            this.injection = injection;
        }

        @Override
        public Optional<CurrentActor> findCurrentByCode(ProjectId projectId, ActorCode code, String defaultLanguage) {
            Optional<CurrentActor> result = delegate.findCurrentByCode(projectId, code, defaultLanguage);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }
    }

    /** A repository whose {@code compareAndUpdate} always reports a conflict, never applying. */
    private static final class AlwaysConflictingRepository extends ForwardingActorRepository {

        AlwaysConflictingRepository(ActorRepository delegate) {
            super(delegate);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Actor updated,
                String nameLanguage, String descriptionLanguage, String defaultLanguage) {
            // Still enforce "must exist", same as the real contract - only ever report a conflict.
            delegate.findByCode(projectId, updated.code(), null)
                    .orElseThrow(() -> new ActorNotFoundException(projectId, updated.code()));
            throw new ActorConcurrentlyModifiedException(projectId, updated.code());
        }
    }
}
