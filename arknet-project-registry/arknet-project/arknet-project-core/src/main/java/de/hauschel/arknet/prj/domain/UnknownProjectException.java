// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Objects;

/**
 * Thrown when a caller names a project - by its opaque identity or by its label - that no
 * registered project carries.
 *
 * <p>Distinct from {@link ProjectNotFoundException}, which is about a {@link
 * de.hauschel.arknet.kernel.ProjectId} an operation already holds: here the caller typed a
 * reference that may be either an id or a label, and the message has to say so rather than claim
 * an id was looked up. It names {@code project_list} as the place to read the exact value from,
 * since a guessed reference is exactly what the project registry never acts on.</p>
 */
public class UnknownProjectException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reference;

    /**
     * Creates the exception.
     *
     * @param reference the id or label that matched no registered project
     */
    public UnknownProjectException(String reference) {
        super("No registered project has the id or label '" + Objects.requireNonNull(reference, "reference")
                + "'. project_list shows every registered project with its label and id, and the ids of "
                + "unregistered datasets, which project_delete accepts as well.");
        this.reference = reference;
    }

    /** @return the id or label that matched no registered project */
    public String reference() {
        return reference;
    }
}
