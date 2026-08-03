// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence.testsupport;

import java.util.Set;
import java.util.function.Function;

import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

/**
 * Wraps a real {@link DatasetLifecycle} so that a test can pin a chosen interleaving of two
 * writers against the very store production runs on, instead of hoping the threads happen to
 * collide. Every method other than {@link #acquire(DatasetId)} passes straight through; the
 * acquired handle is a {@link GuardedHandle} carrying this decorator's two hooks.
 *
 * <p>The two hooks pin two different interleavings, and a test normally uses exactly one of
 * them:</p>
 * <ul>
 *   <li>{@code beforeTransaction} runs immediately <em>before</em> a write transaction opens.
 *       That is where a concurrent writer's commit turns an already-taken read stale, so this is
 *       the hook for a lost-update / compare-and-set race: let the interfering writer commit
 *       here, and the caller's own transaction then opens against a head it no longer holds.</li>
 *   <li>{@code txDecorator} wraps the transaction's {@link DatasetTx} once it is open, which is
 *       where two transactions can be held open against each other - typically with
 *       {@link GuardSyncTx}, so that both callers' in-transaction uniqueness guards pass before
 *       either commits and the loser's commit is the one the store rejects.</li>
 * </ul>
 *
 * <p>Neither hook disarms itself: this decorator runs them on <em>every</em> transaction it sees.
 * Where a race must fire once and let a retry through unimpeded, the one-shot arming belongs in
 * the caller's own {@link Function}/{@link Runnable} (an {@code AtomicBoolean} guard is the usual
 * shape), so that this class stays a plain pass-through decorator with no state of its own.</p>
 *
 * <p>Not thread-safe beyond what its delegate and the injected hooks are: both hooks are called
 * on the caller's thread, which is exactly what lets a test block that thread on a barrier or a
 * latch.</p>
 */
public final class GuardedLifecycle implements DatasetLifecycle {

    private final DatasetLifecycle delegate;
    private final Function<DatasetTx, DatasetTx> txDecorator;
    private final Runnable beforeTransaction;

    /**
     * @param delegate the real lifecycle every call is forwarded to
     * @param txDecorator wraps each opened transaction's {@link DatasetTx}; return the argument
     *        unchanged to leave the transaction alone
     * @param beforeTransaction run on the caller's thread right before each write transaction
     *        opens
     */
    public GuardedLifecycle(DatasetLifecycle delegate, Function<DatasetTx, DatasetTx> txDecorator,
            Runnable beforeTransaction) {
        this.delegate = delegate;
        this.txDecorator = txDecorator;
        this.beforeTransaction = beforeTransaction;
    }

    /**
     * Decorates transactions only, with nothing to do before one opens - the shape a test uses
     * when the interleaving it pins lives entirely inside the transaction.
     */
    public GuardedLifecycle(DatasetLifecycle delegate, Function<DatasetTx, DatasetTx> txDecorator) {
        this(delegate, txDecorator, () -> {
            // Nothing to pin before the transaction opens.
        });
    }

    @Override
    public DatasetHandle acquire(DatasetId id) {
        return new GuardedHandle(delegate.acquire(id), txDecorator, beforeTransaction);
    }

    @Override
    public void close(DatasetId id) {
        delegate.close(id);
    }

    @Override
    public void delete(DatasetId id) {
        delegate.delete(id);
    }

    @Override
    public Set<DatasetId> list() {
        return delegate.list();
    }
}
