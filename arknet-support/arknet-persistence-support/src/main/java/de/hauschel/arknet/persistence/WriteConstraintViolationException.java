// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * Thrown by the {@link ShaclWriteGate} when a candidate graph violates the SHACL shapes of the
 * writing bounded context and is therefore rejected before persistence.
 *
 * <p>Carries a human-readable aggregation of the violated SHACL results (focus node, path,
 * message) - never the RDF-technology-specific {@code ShaclReport} itself, so that this
 * exception stays a plain, adapter-external signal.</p>
 */
public class WriteConstraintViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the aggregated description of the violated SHACL results
     */
    public WriteConstraintViolationException(String message) {
        super(message);
    }
}
