// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.persistence.ChildFragments;
import de.hauschel.arknet.resource.application.port.out.ResourceBatch;

/**
 * Pauses the first read of one kind inside the engine's write transaction - the slice of a
 * resource, the edges pointing at it, or the highest running number of a code prefix - until a
 * concurrent writer has committed. The read has already happened when the pause starts, so the
 * engine's commit runs against a state the concurrent writer has since changed.
 *
 * <p>The one-shot flag is shared across the attempts of one racer: the second run, after the
 * engine lost its commit, reads unimpeded.</p>
 */
final class PausingBatch implements ResourceBatch {

    /** The read the batch pauses after. */
    enum Read {
        /** The slice of the resource {@code pauseOn}. */
        SLICE,
        /** The edges pointing at the resource {@code pauseOn}. */
        EDGES,
        /** The highest running number of the code prefix {@code pauseOn}. */
        RUNNING_NUMBER
    }

    static final long WAIT_SECONDS = 30;

    private final ResourceBatch delegate;
    private final Read pauseAfter;
    private final String pauseOn;
    private final AtomicBoolean firstRead;
    private final CountDownLatch read;
    private final CountDownLatch resume;

    PausingBatch(final ResourceBatch delegate, final Read pauseAfter, final String pauseOn,
            final AtomicBoolean firstRead, final CountDownLatch read, final CountDownLatch resume) {
        this.delegate = delegate;
        this.pauseAfter = pauseAfter;
        this.pauseOn = pauseOn;
        this.firstRead = firstRead;
        this.read = read;
        this.resume = resume;
    }

    @Override
    public ReadableGraph slice(final IRI subject) {
        ReadableGraph slice = delegate.slice(subject);
        pauseOnFirstRead(Read.SLICE, subject.getIRIString());
        return slice;
    }

    @Override
    public ReadableGraph pointingAt(final IRI resource) {
        ReadableGraph edges = delegate.pointingAt(resource);
        pauseOnFirstRead(Read.EDGES, resource.getIRIString());
        return edges;
    }

    @Override
    public int highestRunningNumber(final String codePrefix) {
        int highest = delegate.highestRunningNumber(codePrefix);
        pauseOnFirstRead(Read.RUNNING_NUMBER, codePrefix);
        return highest;
    }

    private void pauseOnFirstRead(final Read kind, final String target) {
        if (kind != pauseAfter || !target.equals(pauseOn) || !firstRead.getAndSet(false)) {
            return;
        }
        read.countDown();
        try {
            if (!resume.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the concurrent write never finished");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ChildFragments childFragments(final IRI parent, final ReadableGraph current) {
        return delegate.childFragments(parent, current);
    }

    @Override
    public List<String> violations(final IRI shape, final IRI focus, final ReadableGraph data) {
        return delegate.violations(shape, focus, data);
    }

    @Override
    public void create(final IRI subject, final String code, final ReadableGraph slice,
            final ReadableGraph context) {
        delegate.create(subject, code, slice, context);
    }

    @Override
    public void update(final IRI subject, final ReadableGraph slice, final ReadableGraph context) {
        delegate.update(subject, slice, context);
    }

    @Override
    public void delete(final IRI subject, final String code) {
        delegate.delete(subject, code);
    }
}
