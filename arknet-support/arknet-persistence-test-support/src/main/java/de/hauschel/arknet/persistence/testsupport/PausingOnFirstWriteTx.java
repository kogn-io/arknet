// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence.testsupport;

import java.util.stream.Stream;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * A pass-through {@link DatasetTx} that runs {@code beforeFirstWrite} exactly once, immediately
 * <em>before</em> its delegate's first write call ({@code update}, {@code add}, {@code remove} or
 * {@code clear}) - and then keeps out of the way for the rest of the transaction.
 *
 * <p>That anchor is what pins a delete guard's atomicity. Every {@code *_delete} runs the
 * reference check first ({@code ask()} calls, or a {@code select()}), then lets the library
 * versioning remove the slice and record the tombstone, both inside the single transaction
 * {@code VersionedWriteFunnel#delete} owns. Pausing right before the first write therefore
 * suspends a deleter at the exact point where it has decided "nothing references this" but has
 * removed nothing yet - the window a concurrent writer would have to slip a new referencing edge
 * through, if the check had run outside the transaction. A test holds the deleter there, lets the
 * other writer commit its edge, and then releases the delete. The same anchor holds a first
 * transaction that has read the store but written nothing yet, whatever it is about to write.</p>
 *
 * <p>Counterpart to {@link GuardSyncTx}, which anchors on {@code contains()} instead.</p>
 *
 * <p>The callback runs on the caller's thread, inside its open transaction, which is what lets a
 * test block it there on a latch. This class holds an unsynchronised flag and is therefore
 * confined to that one thread - one instance per racing caller. It fires once per instance and
 * never again, so a retried attempt handed the same instance runs unimpeded.</p>
 */
public final class PausingOnFirstWriteTx implements DatasetTx {

    private final DatasetTx delegate;
    private final Runnable beforeFirstWrite;
    private boolean pending = true;

    /**
     * @param delegate         the real transaction every call is forwarded to
     * @param beforeFirstWrite run once, on the caller's thread, right before the first write call
     *        reaches {@code delegate}
     */
    public PausingOnFirstWriteTx(DatasetTx delegate, Runnable beforeFirstWrite) {
        this.delegate = delegate;
        this.beforeFirstWrite = beforeFirstWrite;
    }

    private void beforeWrite() {
        if (pending) {
            pending = false;
            beforeFirstWrite.run();
        }
    }

    @Override
    public void update(String sparqlUpdate) {
        beforeWrite();
        delegate.update(sparqlUpdate);
    }

    @Override
    public void update(String sparqlUpdate, java.util.Map<String, RDFTerm> bindings) {
        beforeWrite();
        delegate.update(sparqlUpdate, bindings);
    }

    @Override
    public long add(IRI graph, ReadableGraph data) {
        beforeWrite();
        return delegate.add(graph, data);
    }

    @Override
    public boolean contains(IRI graph, BlankNodeOrIRI subject, IRI predicate,
            RDFTerm object) {
        return delegate.contains(graph, subject, predicate, object);
    }

    @Override
    public boolean ask(String query) {
        return delegate.ask(query);
    }

    @Override
    public boolean ask(String query, java.util.Map<String, RDFTerm> bindings) {
        return delegate.ask(query, bindings);
    }

    @Override
    public long remove(IRI graph, ReadableGraph data) {
        beforeWrite();
        return delegate.remove(graph, data);
    }

    @Override
    public void clear(IRI graph) {
        beforeWrite();
        delegate.clear(graph);
    }

    @Override
    public ReadableGraph snapshot(IRI graph) {
        return delegate.snapshot(graph);
    }

    @Override
    public long count(IRI graph) {
        return delegate.count(graph);
    }

    @Override
    public long count() {
        return delegate.count();
    }

    @Override
    public Stream<BindingSet> select(String query) {
        return delegate.select(query);
    }

    @Override
    public Stream<BindingSet> select(String query, java.util.Map<String, RDFTerm> bindings) {
        return delegate.select(query, bindings);
    }

    @Override
    public ReadableGraph construct(String query) {
        return delegate.construct(query);
    }

    @Override
    public ReadableGraph construct(String query, java.util.Map<String, RDFTerm> bindings) {
        return delegate.construct(query, bindings);
    }

    @Override
    public ReadableGraph match(IRI graph, BlankNodeOrIRI subject, IRI predicate,
            RDFTerm object) {
        return delegate.match(graph, subject, predicate, object);
    }
}
