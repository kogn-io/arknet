// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.Objects;

/**
 * Thrown when a client presents an {@link Anchor} no project has ever been registered or
 * attached with.
 *
 * <p>This is the exception the no-default, no-fallback rule is built around: an unknown anchor is a fatal,
 * caller-visible error, never a silent default and never a fallback to some server-side working
 * directory. Rather than fail with a bare "not found", the message is deliberately didactic - it
 * names the two tools that resolve the situation ({@code project_add} for a brand-new project,
 * {@code project_attach_anchor} for extending an existing one) so an agent driving the MCP
 * boundary can recover without a human explaining what to do next.</p>
 */
public class UnknownAnchorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Anchor anchor;

    /**
     * Creates the exception.
     *
     * @param anchor the anchor that resolved to no project
     */
    public UnknownAnchorException(Anchor anchor) {
        super("Anchor '" + Objects.requireNonNull(anchor, "anchor").value()
                + "' is not registered with any project. Register it with project_add, "
                + "or attach it to an existing project with project_attach_anchor.");
        this.anchor = anchor;
    }

    /** @return the anchor that resolved to no project */
    public Anchor anchor() {
        return anchor;
    }
}
