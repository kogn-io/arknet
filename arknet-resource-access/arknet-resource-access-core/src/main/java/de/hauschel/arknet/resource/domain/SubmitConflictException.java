// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

/**
 * A concurrent write changed what a submit read before it could commit, or took a business code
 * it was about to assign; the submit changed nothing and may run again against the new state.
 */
public class SubmitConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the conflict.
     *
     * @param message what was lost
     * @param cause   the store's signal, may be {@code null}
     */
    public SubmitConflictException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
