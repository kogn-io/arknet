// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

/**
 * A submitted document was rejected; the store holds none of its changes. The message names every
 * reason found, each with the full IRI of the field or resource it concerns.
 */
public class SubmitRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the rejection.
     *
     * @param message every reason, readable without the document at hand
     */
    public SubmitRejectedException(final String message) {
        super(message);
    }
}
