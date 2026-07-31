// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextConcurrentlyModifiedException;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Regression tests for the two concurrency races {@link BoundedContextService} has to absorb.
 *
 * <p>Issue #144: {@link BoundedContextService#add} used to compute the next business code
 * ({@code BC-N}) client-side via {@code nextCode()} and then {@code create()} it with no retry, so
 * two racing {@code bc_add} calls in the same project both computed the same candidate code and
 * one of two well-formed callers saw the out-adapter's in-transaction uniqueness guard fire as a
 * caller-visible {@code DuplicateBoundedContextCodeException} - even though nothing about its own
 * request was wrong.</p>
 *
 * <p>Issue #176 (lost update): {@link BoundedContextService#linkTerm} used to read via
 * {@code findByCode} outside any transaction and write back via an unconditional
 * replace-by-identity {@code update}, so two racing {@code bc_link_term} calls on the same bounded
 * context silently lost one of the two {@code arkddd:ubiquitousLanguageTerm} edges - the second
 * writer never saw the first one's edge and overwrote it without any conflict being reported.</p>
 *
 * <p>Both races are reproduced deterministically, without real threads: a {@link
 * BoundedContextRepository} decorator runs an "other caller"'s complete round trip exactly once,
 * at the precise point where a concurrent writer's commit would land - after the first
 * {@code findAll} (which {@code nextCode()} reads) for #144, after the first
 * {@code findCurrentByCode} for #176. That pins the exact interleaving instead of relying on
 * thread scheduling, which would make these tests flaky. Mirrors
 * {@code RequirementServiceConcurrencyTest} (issues #108/#167), the bounded context that got both
 * guards first.</p>
 */
class BoundedContextServiceConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");
    private static final ResourceId TERM_1 = ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final ResourceId TERM_2 = ResourceId.of("https://w3id.org/arknet/id/term-2");

    private InMemoryBoundedContextRepository store;
    /**
     * Shared across {@link #otherCaller} and the "under test" service, mirroring the composition
     * root, which wires exactly one {@link ResourceIdFactory} bean shared by all concurrent
     * callers. Two independent factories would mint colliding identities for the two concurrently
     * added bounded contexts, a test artefact this bug does not have.
     */
    private SequentialResourceIdFactory resourceIdFactory;
    private InMemoryTermLookup termLookup;
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private BoundedContextService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryBoundedContextRepository();
        resourceIdFactory = new SequentialResourceIdFactory();
        termLookup = new InMemoryTermLookup();
        termLookup.register("TERM-1", TERM_1);
        termLookup.register("TERM-2", TERM_2);
        otherCaller = new BoundedContextService(store, resourceIdFactory, termLookup);
    }

    /**
     * Issue #176 (lost update): two concurrent {@code bc_link_term} calls for the same bounded
     * context, linking different terms, must both survive. Before the fix, the second writer's
     * {@code repository.update} blindly overwrote the first writer's already-committed edge
     * because neither read nor write carried any concurrency guard.
     */
    @Test
    void concurrentLinkTermCallsForDifferentTermsBothSurvive() {
        BoundedContextCode code = otherCaller.add(WS, newBoundedContext()).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.linkTerm(WS, code, "TERM-2"));
        BoundedContextService underTest = new BoundedContextService(racing, resourceIdFactory, termLookup);

        BoundedContext result = underTest.linkTerm(WS, code, "TERM-1");

        assertEquals(2, result.usesTerms().size());
        assertTrue(result.usesTerms().containsAll(List.of(new TermRef(TERM_1), new TermRef(TERM_2))));
        BoundedContext stored = store.findByCode(WS, code).orElseThrow();
        assertEquals(2, stored.usesTerms().size());
    }

    /**
     * A read-modify-write that keeps losing the race on every single attempt (a repository whose
     * {@code compareAndUpdate} always reports a conflict) must fail loudly with
     * {@link BoundedContextConcurrentlyModifiedException} instead of looping forever.
     */
    @Test
    void linkTermGivesUpAfterExhaustingRetriesAgainstPermanentContention() {
        BoundedContextCode code = otherCaller.add(WS, newBoundedContext()).code();
        BoundedContextService underTest = new BoundedContextService(
                new AlwaysConflictingRepository(store), resourceIdFactory, termLookup);

        assertThrows(BoundedContextConcurrentlyModifiedException.class,
                () -> underTest.linkTerm(WS, code, "TERM-1"));
    }

    /**
     * Linking an already-linked term stays a no-op: the mutation returns the state it was given,
     * so no write is attempted at all - not even a compare-and-set one that could fail.
     */
    @Test
    void linkingAnAlreadyLinkedTermWritesNothingEvenUnderPermanentContention() {
        BoundedContextCode code = otherCaller.add(WS, newBoundedContext()).code();
        otherCaller.linkTerm(WS, code, "TERM-1");
        BoundedContextService underTest = new BoundedContextService(
                new AlwaysConflictingRepository(store), resourceIdFactory, termLookup);

        BoundedContext result = underTest.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1)), result.usesTerms());
    }

    @Test
    void concurrentAddCallsBothGetDistinctCodesInsteadOfOneFailing() {
        RaceOnFirstFindAllRepository racing =
                new RaceOnFirstFindAllRepository(store, () -> otherCaller.add(WS, newBoundedContext()));
        BoundedContextService underTest =
                new BoundedContextService(racing, resourceIdFactory, new InMemoryTermLookup());

        BoundedContext result = underTest.add(WS, newBoundedContext());

        assertEquals(new BoundedContextCode("BC-2"), result.code());
        assertEquals(2, store.findAll(WS).size());
        assertTrue(store.findAll(WS).stream()
                .map(BoundedContext::code)
                .toList()
                .containsAll(List.of(new BoundedContextCode("BC-1"), new BoundedContextCode("BC-2"))));
    }

    private static NewBoundedContext newBoundedContext() {
        return new NewBoundedContext("OrderManagement",
                "Owns the lifecycle of a customer order from placement to fulfilment.",
                Subdomain.CORE_DOMAIN, "orders-team");
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
     * simulates a concurrent {@code bc_add} committing between this caller's code computation and
     * its own {@code create()}.
     */
    private static final class RaceOnFirstFindAllRepository implements BoundedContextRepository {

        private final BoundedContextRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstFindAllRepository(BoundedContextRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, BoundedContext boundedContext) {
            delegate.create(projectId, boundedContext);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, String expectedHead, BoundedContext updated) {
            delegate.compareAndUpdate(projectId, expectedHead, updated);
        }

        @Override
        public Optional<BoundedContext> findByCode(ProjectId projectId, BoundedContextCode code) {
            return delegate.findByCode(projectId, code);
        }

        @Override
        public Optional<CurrentBoundedContext> findCurrentByCode(ProjectId projectId,
                BoundedContextCode code) {
            return delegate.findCurrentByCode(projectId, code);
        }

        @Override
        public List<BoundedContext> findAll(ProjectId projectId) {
            List<BoundedContext> result = delegate.findAll(projectId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<ResolveBoundedContexts.ResolvedBoundedContext> findByIds(
                ProjectId projectId, List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }
    }

    /**
     * Decorator that runs {@code injection} exactly once, synchronously, right after the first
     * {@link #findCurrentByCode} call returns - simulating a concurrent caller whose own complete
     * read-modify-write round trip commits in the window between this caller's read and its own
     * write. Every other call, including every subsequent {@code findCurrentByCode} the retry
     * issues, delegates unchanged.
     */
    private static final class RaceOnFirstReadRepository implements BoundedContextRepository {

        private final BoundedContextRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstReadRepository(BoundedContextRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, BoundedContext boundedContext) {
            delegate.create(projectId, boundedContext);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, String expectedHead, BoundedContext updated) {
            delegate.compareAndUpdate(projectId, expectedHead, updated);
        }

        @Override
        public Optional<BoundedContext> findByCode(ProjectId projectId, BoundedContextCode code) {
            return delegate.findByCode(projectId, code);
        }

        @Override
        public Optional<CurrentBoundedContext> findCurrentByCode(ProjectId projectId,
                BoundedContextCode code) {
            Optional<CurrentBoundedContext> result = delegate.findCurrentByCode(projectId, code);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<BoundedContext> findAll(ProjectId projectId) {
            return delegate.findAll(projectId);
        }

        @Override
        public List<ResolveBoundedContexts.ResolvedBoundedContext> findByIds(
                ProjectId projectId, List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }
    }

    /** A repository whose {@code compareAndUpdate} always reports a conflict, never applying. */
    private static final class AlwaysConflictingRepository implements BoundedContextRepository {

        private final BoundedContextRepository delegate;

        AlwaysConflictingRepository(BoundedContextRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void create(ProjectId projectId, BoundedContext boundedContext) {
            delegate.create(projectId, boundedContext);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, String expectedHead, BoundedContext updated) {
            // Still enforce "must exist", same as the real contract - only ever report a conflict.
            delegate.findByCode(projectId, updated.code())
                    .orElseThrow(() -> new BoundedContextNotFoundException(projectId, updated.code()));
            throw new BoundedContextConcurrentlyModifiedException(projectId, updated.code());
        }

        @Override
        public Optional<BoundedContext> findByCode(ProjectId projectId, BoundedContextCode code) {
            return delegate.findByCode(projectId, code);
        }

        @Override
        public Optional<CurrentBoundedContext> findCurrentByCode(ProjectId projectId,
                BoundedContextCode code) {
            return delegate.findCurrentByCode(projectId, code);
        }

        @Override
        public List<BoundedContext> findAll(ProjectId projectId) {
            return delegate.findAll(projectId);
        }

        @Override
        public List<ResolveBoundedContexts.ResolvedBoundedContext> findByIds(
                ProjectId projectId, List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }
    }
}
