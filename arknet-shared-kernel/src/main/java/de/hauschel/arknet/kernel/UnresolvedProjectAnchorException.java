// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.Objects;

/**
 * Raised when a tool call cannot be attributed to a project: it carried no anchor at all, or an
 * anchor no project is registered under (ADR-016 decision 3).
 *
 * <p><strong>This is the type that makes "no default" enforceable.</strong> Before ADR-016 an
 * unattributable call still routed somewhere - to a slug derived from a directory name, or to an
 * implicit default project - and the caller never learned that the server had guessed. Making it
 * an exception moves the failure to the one moment where it is still cheap and unambiguous: before
 * anything is read or written. A missing anchor and an unknown one deliberately share this type,
 * because they are the same failure from the caller's side ("this call has no project"), differing
 * only in the remedy the message names.</p>
 *
 * <p>The message is supplied by the caller rather than composed here, because the useful remedy is
 * call-site-specific and names MCP tools - knowledge of the driving adapter, not of this kernel
 * type. The kernel states the failure; the adapter states what to do about it.</p>
 */
public class UnresolvedProjectAnchorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String anchor;

    /**
     * @param anchor  the anchor that could not be resolved, or {@code null} if the call carried
     *                none at all
     * @param message what went wrong and how the caller can fix it
     */
    public UnresolvedProjectAnchorException(String anchor, String message) {
        this(anchor, message, null);
    }

    /**
     * @param anchor  the anchor that could not be resolved, or {@code null} if the call carried
     *                none at all
     * @param message what went wrong and how the caller can fix it
     * @param cause   the underlying failure, e.g. the project component's own "unknown anchor"
     *                exception being translated at this port boundary
     */
    public UnresolvedProjectAnchorException(String anchor, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.anchor = anchor;
    }

    /**
     * @return the unresolvable anchor, or {@code null} if the call carried none
     */
    public String anchor() {
        return anchor;
    }
}
