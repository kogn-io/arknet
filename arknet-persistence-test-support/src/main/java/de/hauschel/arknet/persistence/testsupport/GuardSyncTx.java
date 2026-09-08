// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence.testsupport;

import java.util.stream.Stream;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * A pass-through {@link DatasetTx} that runs {@code afterGuard} exactly once, the moment its
 * delegate's <em>Nth</em> {@code contains()} call has returned - and then keeps out of the way
 * for the rest of the transaction.
 *
 * <p>{@code N} defaults to two via the two-argument constructor, the number of in-transaction
 * existence guards {@code WriteFunnel#create}/{@code #createIfAbsent} issue when a business code
 * is checked - the subject-identity guard first, then (only reached once that one passed) the
 * business-code uniqueness guard. Pausing right after the second one therefore suspends a caller
 * at the exact point where it has decided "this code is free" but has written nothing yet. A
 * write path with only the identity guard (e.g. {@code createIfAbsent} called with a {@code null}
 * business code) instead wants the three-argument constructor with {@code guardCount = 1}.
 * Releasing two callers held there at once is what makes an overlapping-write conflict reproduce
 * deterministically rather than by luck: every guard the write path issues passes before either
 * commits, so the conflict is settled by the store's commit-time detection instead of by the
 * guard.</p>
 *
 * <p>Only applicable where the write path issues exactly {@code guardCount} {@code contains()}
 * calls before the point a test wants to pin. A write path with a variable number of guards, or
 * one whose only guard is the funnel's head comparison (which runs before the transaction body),
 * needs a differently anchored decorator - counting {@code contains()} calls would pause at the
 * wrong moment, or never.</p>
 *
 * <p>The callback runs on the caller's thread, inside its open transaction, which is what lets a
 * test block it there on a barrier or a latch. This class holds an unsynchronised counter and is
 * therefore confined to that one thread - one instance per racing caller, which is also what
 * makes "the Nth guard" mean the Nth guard <em>of that caller</em>. It fires once per instance and
 * never again, so a retried attempt handed the same instance runs unimpeded.</p>
 */
public final class GuardSyncTx implements DatasetTx {

    private final DatasetTx delegate;
    private final int guardCountToPauseAfter;
    private final Runnable afterGuard;
    private int guardCount;

    /**
     * @param delegate       the real transaction every call is forwarded to
     * @param afterSecondGuard run once, on the caller's thread, after the second
     *        {@code contains()} call has returned
     */
    public GuardSyncTx(DatasetTx delegate, Runnable afterSecondGuard) {
        this(delegate, 2, afterSecondGuard);
    }

    /**
     * @param delegate               the real transaction every call is forwarded to
     * @param guardCountToPauseAfter how many {@code contains()} calls to let through before
     *        pausing - {@code 1} for a write path with only the identity guard (e.g.
     *        {@code createIfAbsent} with a {@code null} business code), {@code 2} for the
     *        identity-then-code pair the two-argument constructor already covers
     * @param afterGuard             run once, on the caller's thread, after the
     *        {@code guardCountToPauseAfter}th {@code contains()} call has returned
     */
    public GuardSyncTx(DatasetTx delegate, int guardCountToPauseAfter, Runnable afterGuard) {
        this.delegate = delegate;
        this.guardCountToPauseAfter = guardCountToPauseAfter;
        this.afterGuard = afterGuard;
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
    public long add(IRI graph, ReadableGraph data) {
        return delegate.add(graph, data);
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
    public boolean contains(IRI graph, io.kogn.rdf.terms.BlankNodeOrIRI subject, IRI predicate,
            io.kogn.rdf.terms.RDFTerm object) {
        boolean result = delegate.contains(graph, subject, predicate, object);
        guardCount++;
        if (guardCount == guardCountToPauseAfter) {
            afterGuard.run();
        }
        return result;
    }

    @Override
    public void update(String sparqlUpdate) {
        delegate.update(sparqlUpdate);
    }

    @Override
    public void update(String sparqlUpdate, java.util.Map<String, io.kogn.rdf.terms.RDFTerm> bindings) {
        delegate.update(sparqlUpdate, bindings);
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
