// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import de.hauschel.arknet.kernel.ProjectId;
import java.util.Objects;

/**
 * Thrown when an {@link Anchor} a caller tries to register or attach already belongs to a
 * project - possibly a different one than the caller intended.
 *
 * <p>This is the enforcement point for the anchor model's central invariant: an anchor belongs to
 * exactly one project. Two callers - or the same caller, twice, by mistake - registering the
 * same anchor for two different projects would silently split a client's own history between
 * them; this exception makes that collision a loud, immediate rejection instead. The message
 * names both the offending anchor and the project that already owns it, so a caller can decide
 * whether to attach to the existing project instead of creating a new one.</p>
 */
public class AnchorAlreadyRegisteredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Anchor anchor;
    private final transient ProjectId owner;

    /**
     * Creates the exception.
     *
     * @param anchor the anchor that already has an owner
     * @param owner  the project that already owns it
     */
    public AnchorAlreadyRegisteredException(Anchor anchor, ProjectId owner) {
        super("anchor '" + Objects.requireNonNull(anchor, "anchor").value()
                + "' is already registered with project " + Objects.requireNonNull(owner, "owner").value());
        this.anchor = anchor;
        this.owner = owner;
    }

    /** @return the anchor that already has an owner */
    public Anchor anchor() {
        return anchor;
    }

    /** @return the project that already owns the anchor */
    public ProjectId owner() {
        return owner;
    }
}
