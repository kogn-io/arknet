// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;

/**
 * Runs a read that has to see one snapshot - a resource's state together with the library token
 * of its draft, or a history - inside a transaction of its own.
 *
 * <p><strong>A read transaction can lose too.</strong> The store runs every transaction
 * {@code SERIALIZABLE}, and a read-only transaction whose reads a concurrent writer changed is
 * rejected at commit like a write. A read has no effect to undo, so it is simply run again on a
 * fresh snapshot, after a short random pause that lets the writers pass, a bounded number of
 * times; a store that keeps changing under every attempt is reported as a
 * {@link ReadConflictException}, never as the store's own exception.</p>
 */
public final class ReadTransactions {

    /** How often a read is attempted before the conflict is reported. */
    static final int MAX_ATTEMPTS = 5;

    /** The upper bound of the pause before a further attempt, in milliseconds, per attempt made. */
    private static final long PAUSE_MILLIS_PER_ATTEMPT = 2;

    private ReadTransactions() {
    }

    /**
     * Runs {@code work} in a transaction of {@code handle}, again on a lost commit, up to
     * {@link #MAX_ATTEMPTS} times.
     *
     * @param handle the dataset to read
     * @param work   the read; it must not write, since it may run more than once
     * @param <T>    the result type
     * @return the result of the first attempt that committed
     * @throws ReadConflictException if every attempt lost its commit
     */
    public static <T> T read(DatasetHandle handle, Function<DatasetTx, T> work) {
        return read(handle, MAX_ATTEMPTS, work);
    }

    /**
     * Runs {@code work} in a transaction of {@code handle}, again on a lost commit.
     *
     * @param handle      the dataset to read
     * @param maxAttempts how often the read is attempted, at least once
     * @param work        the read; it must not write, since it may run more than once
     * @param <T>         the result type
     * @return the result of the first attempt that committed
     * @throws ReadConflictException if every attempt lost its commit
     */
    public static <T> T read(DatasetHandle handle, int maxAttempts, Function<DatasetTx, T> work) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(work, "work");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, was: " + maxAttempts);
        }
        ConcurrencyConflictException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return handle.transactor().inTransaction(work);
            } catch (ConcurrencyConflictException e) {
                last = e;
            }
            if (attempt < maxAttempts) {
                pauseBefore(attempt, last);
            }
        }
        throw new ReadConflictException(maxAttempts, last);
    }

    private static void pauseBefore(int attemptsMade, ConcurrencyConflictException conflict) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, PAUSE_MILLIS_PER_ATTEMPT * attemptsMade + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReadConflictException(attemptsMade, conflict);
        }
    }
}
