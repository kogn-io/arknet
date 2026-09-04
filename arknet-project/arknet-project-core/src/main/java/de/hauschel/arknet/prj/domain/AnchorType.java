// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

/**
 * The kind of {@link Anchor} value a client sends (ADR-016 decision 2).
 *
 * <p>The type is metadata a client attaches to help a human reading the registry make sense of
 * an anchor's origin (a report listing a project's anchors can label them "path"/"url"/"uuid"
 * rather than showing an undifferentiated string). It carries no behaviour: the server never
 * branches on it to validate or interpret the anchor's value - that would reintroduce the very
 * inspection ADR-016 removes. Deliberately open to growth ("later further types"
 * decision 2) without being unbounded: an enum, not a free-form string, so a caller can never
 * send a type the server silently accepts without ever having decided to support it.</p>
 */
public enum AnchorType {

    /** A filesystem path a client works from, e.g. a git worktree or IDE project directory. */
    PATH,

    /** A URL, e.g. a remote workspace or a browser-hosted client's origin. */
    URL,

    /** An opaque UUID a client mints itself when it has no stable path or URL to offer. */
    UUID
}
