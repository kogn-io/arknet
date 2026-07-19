package de.hauschel.arknet.bc.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Regression test for issue #144: {@link BoundedContextService#add} used to compute the next
 * business code ({@code BC-N}) client-side via {@code nextCode()} and then {@code create()} it
 * with no retry, so two racing {@code bc_add} calls in the same workspace both computed the same
 * candidate code and one of two well-formed callers saw the out-adapter's in-transaction
 * uniqueness guard fire as a caller-visible {@code DuplicateBoundedContextCodeException} - even
 * though nothing about its own request was wrong.
 *
 * <p>The race is reproduced deterministically, without real threads: a {@link
 * BoundedContextRepository} decorator runs an "other caller"'s complete add exactly once, right
 * after the first {@code findAll} (which {@code nextCode()} reads) returns - pinning the exact
 * interleaving instead of relying on thread scheduling, which would make the test flaky. Mirrors
 * {@code RequirementServiceConcurrencyTest} (issue #108), the one type that already guarded this.</p>
 */
class BoundedContextServiceConcurrencyTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;

    private InMemoryBoundedContextRepository store;
    /**
     * Shared across {@link #otherCaller} and the "under test" service, mirroring the composition
     * root, which wires exactly one {@link ResourceIdFactory} bean shared by all concurrent
     * callers. Two independent factories would mint colliding identities for the two concurrently
     * added bounded contexts, a test artefact this bug does not have.
     */
    private SequentialResourceIdFactory resourceIdFactory;
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private BoundedContextService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryBoundedContextRepository();
        resourceIdFactory = new SequentialResourceIdFactory();
        otherCaller = new BoundedContextService(store, resourceIdFactory, new InMemoryTermLookup());
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
        public void create(WorkspaceId workspaceId, BoundedContext boundedContext) {
            delegate.create(workspaceId, boundedContext);
        }

        @Override
        public void update(WorkspaceId workspaceId, BoundedContext boundedContext) {
            delegate.update(workspaceId, boundedContext);
        }

        @Override
        public Optional<BoundedContext> findByCode(WorkspaceId workspaceId, BoundedContextCode code) {
            return delegate.findByCode(workspaceId, code);
        }

        @Override
        public List<BoundedContext> findAll(WorkspaceId workspaceId) {
            List<BoundedContext> result = delegate.findAll(workspaceId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }
    }
}
