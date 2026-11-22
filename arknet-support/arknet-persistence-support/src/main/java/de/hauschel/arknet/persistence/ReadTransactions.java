// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.Objects;
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
 * fresh snapshot, a bounded number of times; a store that keeps changing under every attempt
 * reports the conflict.</p>
 */
public final class ReadTransactions {

    /** How often a read is attempted before the conflict is reported. */
    static final int MAX_ATTEMPTS = 5;

    private ReadTransactions() {
    }

    /**
     * Runs {@code work} in a transaction of {@code handle}, again on a lost commit.
     *
     * @param handle the dataset to read
     * @param work   the read; it must not write, since it may run more than once
     * @param <T>    the result type
     * @return the result of the first attempt that committed
     */
    public static <T> T read(DatasetHandle handle, Function<DatasetTx, T> work) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(work, "work");
        ConcurrencyConflictException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return handle.transactor().inTransaction(work);
            } catch (ConcurrencyConflictException e) {
                last = e;
            }
        }
        throw last;
    }
}
