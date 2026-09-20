// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence.testsupport;

import java.util.function.Function;

import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetHandle;

/**
 * The handle {@link GuardedLifecycle} hands out: every port passes straight through to the real
 * handle except {@link #transactor()}, which is where both of that lifecycle's hooks fire.
 *
 * <p>Only the transactional path is decorated on purpose. The non-transactional ports
 * ({@link #sparqlQuery()}, {@link #sparqlUpdate()}, {@link #graphStore()},
 * {@link #datasetExport()}) are left untouched, so a test pinning a write race cannot
 * accidentally also perturb a read the code under test performs outside a transaction.</p>
 *
 * <p>Usually constructed by {@link GuardedLifecycle} rather than directly; the constructors are
 * public for the case where only a single handle, not a whole lifecycle, is to be decorated.</p>
 *
 * @see GuardedLifecycle for what the two hooks pin and why the one-shot arming belongs in them
 */
public final class GuardedHandle implements DatasetHandle {

    private final DatasetHandle delegate;
    private final Function<DatasetTx, DatasetTx> txDecorator;
    private final Runnable beforeTransaction;

    /**
     * @param delegate the real handle every call is forwarded to
     * @param txDecorator wraps each opened transaction's {@link DatasetTx}
     * @param beforeTransaction run on the caller's thread right before each transaction opens
     */
    public GuardedHandle(DatasetHandle delegate, Function<DatasetTx, DatasetTx> txDecorator,
            Runnable beforeTransaction) {
        this.delegate = delegate;
        this.txDecorator = txDecorator;
        this.beforeTransaction = beforeTransaction;
    }

    /** Decorates transactions only, with nothing to do before one opens. */
    public GuardedHandle(DatasetHandle delegate, Function<DatasetTx, DatasetTx> txDecorator) {
        this(delegate, txDecorator, () -> {
            // Nothing to pin before the transaction opens.
        });
    }

    @Override
    public GraphStore graphStore() {
        return delegate.graphStore();
    }

    @Override
    public SparqlQuery sparqlQuery() {
        return delegate.sparqlQuery();
    }

    @Override
    public SparqlUpdate sparqlUpdate() {
        return delegate.sparqlUpdate();
    }

    @Override
    public DatasetExport datasetExport() {
        return delegate.datasetExport();
    }

    @Override
    public DatasetTransactor transactor() {
        DatasetTransactor real = delegate.transactor();
        return new DatasetTransactor() {
            @Override
            public <T> T inTransaction(Function<DatasetTx, T> fn) {
                beforeTransaction.run();
                return real.inTransaction(tx -> fn.apply(txDecorator.apply(tx)));
            }
        };
    }

    @Override
    public void close() {
        delegate.close();
    }
}
