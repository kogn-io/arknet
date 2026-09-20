// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import de.hauschel.arknet.kernel.ProjectId;
import java.util.Objects;

/**
 * Thrown when a {@link Project#label} a caller tries to register or rename to is already used
 * by a different project.
 *
 * <p>Distinct from {@link AnchorAlreadyRegisteredException}: that one flags a collision on the
 * routing key (the anchor), this one flags a collision on the human-readable label a person
 * reads in a report or types when addressing a project by name. The label is
 * required to stay unique across projects precisely because it - not the opaque
 * {@link ProjectId} - is what a human is expected to recognise and type.</p>
 */
public class DuplicateProjectLabelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String label;

    /**
     * Creates the exception.
     *
     * @param label the label that already labels a different project
     */
    public DuplicateProjectLabelException(String label) {
        super("project label '" + Objects.requireNonNull(label, "label") + "' is already in use");
        this.label = label;
    }

    /** @return the label that already labels a different project */
    public String label() {
        return label;
    }
}
