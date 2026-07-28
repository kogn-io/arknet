// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseConcurrentlyModifiedException;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Regression test for issue #144: {@link UseCaseService#add} used to compute the next business
 * code ({@code UCn}) client-side via {@code nextCode()} and then {@code create()} it with no
 * retry, so two racing {@code uc_add} calls in the same project both computed the same candidate
 * code and one of two well-formed callers saw the out-adapter's in-transaction uniqueness guard
 * fire as a caller-visible {@code DuplicateUseCaseCodeException} - even though nothing about its
 * own request was wrong.
 *
 * <p>The race is reproduced deterministically, without real threads: a {@link UseCaseRepository}
 * decorator runs an "other caller"'s complete add exactly once, right after the first {@code
 * findAll} (which {@code nextCode()} reads) returns - pinning the exact interleaving instead of
 * relying on thread scheduling, which would make the test flaky. Mirrors {@code
 * RequirementServiceConcurrencyTest} (issue #108), the one type that already guarded this.</p>
 */
class UseCaseServiceConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");
    private static final ResourceId CUSTOMER_ID = ResourceId.of("https://w3id.org/arknet/id/actor-customer");

    private InMemoryUseCaseRepository store;
    /**
     * Shared across {@link #otherCaller} and the "under test" service, mirroring the composition
     * root, which wires exactly one {@link ResourceIdFactory} bean shared by all concurrent
     * callers. Two independent factories would mint colliding identities for the two concurrently
     * added use cases, a test artefact this bug does not have.
     */
    private SequentialResourceIdFactory resourceIdFactory;
    private InMemoryActorLookup actorLookup;
    private InMemoryRequirementLookup requirementLookup;
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private UseCaseService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryUseCaseRepository();
        resourceIdFactory = new SequentialResourceIdFactory();
        actorLookup = new InMemoryActorLookup();
        actorLookup.register("Customer", CUSTOMER_ID);
        requirementLookup = new InMemoryRequirementLookup();
        otherCaller = new UseCaseService(store, resourceIdFactory, requirementLookup, actorLookup);
    }

    @Test
    void concurrentAddCallsBothGetDistinctCodesInsteadOfOneFailing() {
        RaceOnFirstFindAllRepository racing =
                new RaceOnFirstFindAllRepository(store, () -> otherCaller.add(WS, newUseCase()));
        UseCaseService underTest =
                new UseCaseService(racing, resourceIdFactory, requirementLookup, actorLookup);

        UseCase result = underTest.add(WS, newUseCase());

        assertEquals(new UseCaseCode("UC2"), result.code());
        assertEquals(2, store.findAll(WS).size());
        assertTrue(store.findAll(WS).stream()
                .map(UseCase::code)
                .toList()
                .containsAll(List.of(new UseCaseCode("UC1"), new UseCaseCode("UC2"))));
    }

    /**
     * Regression test for issue #165's {@code updateWithOptimisticRetry}: a concurrent writer
     * that commits a different field between this caller's read and its own write must cost the
     * caller nothing and lose neither field, mirroring {@code
     * RequirementServiceConcurrencyTest#updateSurvivesAConcurrentLinkTermBetweenReadAndWrite}.
     */
    @Test
    void updateSurvivesAConcurrentUpdateOfADifferentFieldBetweenReadAndWrite() {
        UseCaseCode code = otherCaller.add(WS, newUseCase()).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.update(WS, code, null, null, null, "Concurrent trigger",
                        null, null, null, null));
        UseCaseService underTest = new UseCaseService(racing, resourceIdFactory, requirementLookup, actorLookup);

        UseCase result = underTest.update(WS, code, null, null, null, null,
                "Racing precondition", null, null, null);

        assertEquals("Concurrent trigger", result.trigger());
        assertEquals("Racing precondition", result.precondition());
        UseCase stored = store.findByCode(WS, code).orElseThrow();
        assertEquals("Concurrent trigger", stored.trigger());
        assertEquals("Racing precondition", stored.precondition());
    }

    /**
     * A read-modify-write that keeps losing the race on every single attempt (a repository whose
     * {@code compareAndUpdate} always reports a conflict) must fail loudly with {@link
     * UseCaseConcurrentlyModifiedException} instead of looping forever.
     */
    @Test
    void updateGivesUpAfterExhaustingRetriesAgainstPermanentContention() {
        UseCaseCode code = otherCaller.add(WS, newUseCase()).code();
        UseCaseService underTest = new UseCaseService(
                new AlwaysConflictingRepository(store), resourceIdFactory, requirementLookup, actorLookup);

        assertThrows(UseCaseConcurrentlyModifiedException.class,
                () -> underTest.update(WS, code, null, null, null, "New trigger", null, null, null, null));
    }

    private static NewUseCase newUseCase() {
        return new NewUseCase("Place order", "goal of Place order", null, null, "Customer",
                List.of(), null, null, List.of(new NewStep(1, "do something", List.of())), List.of());
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
     * {@link #findAll} call returns - {@code nextCode()} reads via {@code findAll}, so this
     * simulates a concurrent {@code uc_add} committing between this caller's code computation and
     * its own {@code create()}.
     */
    private static final class RaceOnFirstFindAllRepository implements UseCaseRepository {

        private final UseCaseRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstFindAllRepository(UseCaseRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, UseCase useCase) {
            delegate.create(projectId, useCase);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, String expectedHead, UseCase updated) {
            delegate.compareAndUpdate(projectId, expectedHead, updated);
        }

        @Override
        public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code) {
            return delegate.findByCode(projectId, code);
        }

        @Override
        public Optional<CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code) {
            return delegate.findCurrentByCode(projectId, code);
        }

        @Override
        public List<UseCase> findAll(ProjectId projectId) {
            List<UseCase> result = delegate.findAll(projectId);
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
     * write. Every other call, including every subsequent {@code findCurrentByCode}, delegates
     * unchanged. Mirrors {@code RequirementServiceConcurrencyTest}'s
     * {@code RaceOnFirstReadRepository}.
     */
    private static final class RaceOnFirstReadRepository implements UseCaseRepository {

        private final UseCaseRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstReadRepository(UseCaseRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, UseCase useCase) {
            delegate.create(projectId, useCase);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, String expectedHead, UseCase updated) {
            delegate.compareAndUpdate(projectId, expectedHead, updated);
        }

        @Override
        public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code) {
            return delegate.findByCode(projectId, code);
        }

        @Override
        public Optional<CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code) {
            Optional<CurrentUseCase> result = delegate.findCurrentByCode(projectId, code);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<UseCase> findAll(ProjectId projectId) {
            return delegate.findAll(projectId);
        }
    }

    /** A repository whose {@code compareAndUpdate} always reports a conflict, never applying. */
    private static final class AlwaysConflictingRepository implements UseCaseRepository {

        private final UseCaseRepository delegate;

        AlwaysConflictingRepository(UseCaseRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void create(ProjectId projectId, UseCase useCase) {
            delegate.create(projectId, useCase);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, String expectedHead, UseCase updated) {
            // Still enforce "must exist", same as the real contract - only ever report a conflict.
            delegate.findByCode(projectId, updated.code())
                    .orElseThrow(() -> new UseCaseNotFoundException(projectId, updated.code()));
            throw new UseCaseConcurrentlyModifiedException(projectId, updated.code());
        }

        @Override
        public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code) {
            return delegate.findByCode(projectId, code);
        }

        @Override
        public Optional<CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code) {
            return delegate.findCurrentByCode(projectId, code);
        }

        @Override
        public List<UseCase> findAll(ProjectId projectId) {
            return delegate.findAll(projectId);
        }
    }
}
