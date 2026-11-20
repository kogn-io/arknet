// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Objects;

/**
 * Thrown when a caller asks to delete the very project its own anchor resolves to.
 *
 * <p>A project is deleted by naming it, never implicitly through the anchor a call arrives with -
 * and the one project a caller must not be able to name is the one it is working in. A session
 * deleting its own project would cut the ground from under every following call it makes, which
 * then fails as "not registered" with nothing left to explain why. Deleting it from a session
 * anchored elsewhere is still possible; that is a deliberate act, not an accident of where the
 * agent happened to run.</p>
 */
public class OwnProjectDeletionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Project project;

    /**
     * Creates the exception.
     *
     * @param project the project the caller's own anchor resolves to, and which it asked to delete
     */
    public OwnProjectDeletionException(Project project) {
        super("Project '" + Objects.requireNonNull(project, "project").label() + "' (id: " + project.id().value()
                + ") is the project this call comes from, so it cannot be deleted from here. Delete it "
                + "from a session that is not anchored in it.");
        this.project = project;
    }

    /** @return the project the caller's own anchor resolves to */
    public Project project() {
        return project;
    }
}
