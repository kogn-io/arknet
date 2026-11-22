// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * A read lost its transaction to concurrent writers on every attempt {@link ReadTransactions}
 * allows. Nothing was changed; the same call is expected to succeed once the writers have
 * passed. The store's own conflict of the last attempt is the {@linkplain #getCause() cause}.
 */
public class ReadConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int attempts;

    /**
     * Creates the exception.
     *
     * @param attempts the number of attempts made
     * @param cause    the store's conflict of the last attempt
     */
    public ReadConflictException(int attempts, Throwable cause) {
        super("The read lost to concurrent writes on every one of " + attempts
                + " attempts; nothing was changed - try the call again.", cause);
        this.attempts = attempts;
    }

    /** @return the number of attempts made */
    public int attempts() {
        return attempts;
    }
}
