// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;

/**
 * Regression test for issue #144: {@link UseCaseService#add} used to compute the next business
 * code ({@code UCn}) client-side via {@code nextCode()} and then {@code create()} it with no
 * retry, so two racing {@code uc_add} calls in the same workspace both computed the same candidate
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

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;
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
        public void create(WorkspaceId workspaceId, UseCase useCase) {
            delegate.create(workspaceId, useCase);
        }

        @Override
        public void update(WorkspaceId workspaceId, UseCase useCase) {
            delegate.update(workspaceId, useCase);
        }

        @Override
        public Optional<UseCase> findByCode(WorkspaceId workspaceId, UseCaseCode code) {
            return delegate.findByCode(workspaceId, code);
        }

        @Override
        public List<UseCase> findAll(WorkspaceId workspaceId) {
            List<UseCase> result = delegate.findAll(workspaceId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }
    }
}
