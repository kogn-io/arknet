// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence.testsupport;

import java.util.stream.Stream;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * A pass-through {@link DatasetTx} that runs {@code beforeFirstUpdate} exactly once, immediately
 * <em>before</em> its delegate's first {@code update()} call - and then keeps out of the way for
 * the rest of the transaction.
 *
 * <p>That anchor is what pins a delete guard's atomicity. Every {@code *_delete} write body in
 * this codebase is shaped the same way: the reference check first ({@code ask()} calls, or a
 * {@code select()}), then one physical {@code DELETE WHERE} ({@code update()}), both inside the
 * single transaction {@code WriteFunnel#delete} owns. Pausing right before that {@code update()}
 * therefore suspends a deleter at the exact point where it has decided "nothing references this"
 * but has removed nothing yet - the window a concurrent writer would have to slip a new
 * referencing edge through, if the check had run outside the transaction. A test holds the
 * deleter there, lets the other writer commit its edge, and then releases the delete.</p>
 *
 * <p>Counterpart to {@link GuardSyncTx}, which anchors on {@code contains()} instead: that one
 * suits the create path, whose guards are existence checks, while a delete body issues no
 * {@code contains()} of its own and no {@code add()} at all.</p>
 *
 * <p>The callback runs on the caller's thread, inside its open transaction, which is what lets a
 * test block it there on a latch. This class holds an unsynchronised flag and is therefore
 * confined to that one thread - one instance per racing caller. It fires once per instance and
 * never again, so a retried attempt handed the same instance runs unimpeded.</p>
 */
public final class PausingOnFirstUpdateTx implements DatasetTx {

    private final DatasetTx delegate;
    private final Runnable beforeFirstUpdate;
    private boolean pending = true;

    /**
     * @param delegate          the real transaction every call is forwarded to
     * @param beforeFirstUpdate run once, on the caller's thread, right before the first
     *        {@code update(String)} call reaches {@code delegate}
     */
    public PausingOnFirstUpdateTx(DatasetTx delegate, Runnable beforeFirstUpdate) {
        this.delegate = delegate;
        this.beforeFirstUpdate = beforeFirstUpdate;
    }

    @Override
    public void update(String sparqlUpdate) {
        if (pending) {
            pending = false;
            beforeFirstUpdate.run();
        }
        delegate.update(sparqlUpdate);
    }

    @Override
    public void update(String sparqlUpdate, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
        delegate.update(sparqlUpdate, bindings);
    }

    @Override
    public long add(IRI graph, ReadableGraph data) {
        return delegate.add(graph, data);
    }

    @Override
    public boolean contains(IRI graph, io.kogn.rdf.terms.BlankNodeOrIRI subject, IRI predicate,
            io.kogn.rdf.terms.RDFTerm object) {
        return delegate.contains(graph, subject, predicate, object);
    }

    @Override
    public boolean ask(String query) {
        return delegate.ask(query);
    }

    @Override
    public boolean ask(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
        return delegate.ask(query, bindings);
    }

    @Override
    public long remove(IRI graph, ReadableGraph data) {
        return delegate.remove(graph, data);
    }

    @Override
    public void clear(IRI graph) {
        delegate.clear(graph);
    }

    @Override
    public ReadableGraph export(IRI graph) {
        return delegate.export(graph);
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
    public Stream<BindingSet> select(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
        return delegate.select(query, bindings);
    }

    @Override
    public ReadableGraph construct(String query) {
        return delegate.construct(query);
    }

    @Override
    public ReadableGraph construct(String query, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
        return delegate.construct(query, bindings);
    }
}
