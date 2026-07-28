// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import de.hauschel.arknet.kernel.ProjectId;
import java.util.Objects;

/**
 * Thrown when an operation refers to a {@link Project} identity the registry does not know.
 *
 * <p>An expected domain outcome (not a programming error): a caller may hold a
 * {@link ProjectId} that was valid once but the project was never actually registered under it,
 * or that came from a different registry entirely. Driving adapters - e.g. the MCP tools -
 * translate it into a user-facing "unknown project" message rather than a stack trace.</p>
 */
public class ProjectNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId id;

    /**
     * Creates the exception.
     *
     * @param id the project identity that was not found
     */
    public ProjectNotFoundException(ProjectId id) {
        super("no project registered with id " + Objects.requireNonNull(id, "id").value());
        this.id = id;
    }

    /** @return the project identity that was not found */
    public ProjectId id() {
        return id;
    }
}
