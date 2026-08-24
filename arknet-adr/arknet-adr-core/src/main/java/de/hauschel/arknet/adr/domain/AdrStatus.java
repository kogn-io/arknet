// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

/**
 * Lifecycle state of an {@link Adr}.
 *
 * <p>Implements all five ontology values: a decision starts {@link #PROPOSED}, and from there may
 * become {@link #ACCEPTED} or {@link #REJECTED} - or, once accepted, {@link #DEPRECATED} or
 * {@link #SUPERSEDED}. See {@link Adr#accept()}/{@link Adr#reject()}/{@link Adr#deprecate()}/
 * {@link Adr#supersededBy(AdrId)} for the exact legal transitions; none of the four resurrects into
 * any of the others beyond those methods, this is a small, terminal-leaning subset rather than a
 * full state machine.</p>
 *
 * <p><strong>{@link #SUPERSEDED} is a real status, coupled to a real edge (kogn-io/arknet#357).</strong>
 * It is set together with {@link Adr#supersededBy()} in the very same write - the compact
 * constructor of {@link Adr} enforces the bi-implication (status is {@code SUPERSEDED} if and only
 * if {@code supersededBy} is set) as a domain invariant, and {@code architecture-shapes.ttl}
 * enforces one direction of the same rule (edge set implies {@code SUPERSEDED}) a second time at
 * the write gate; the converse stays domain-only, since a node shape checking it would also fire on
 * the validation-only peer copies a write asserts for a {@code relatedTo}/{@code supersededBy}
 * target (kogn-io/arknet#359). Before this issue, {@code SUPERSEDED} stayed
 * derived-only from a {@code supersedes}/{@code supersededBy} reverse-read and this codebase
 * materialised neither status nor edge as a second, independently maintained signal; that reasoning
 * inverted once the edge itself moved onto the superseded decision's own record ({@link Adr#supersededBy}
 * replacing the superseding decision's forward-only {@code supersedes} list) - the status and the edge
 * are now one write on one record, not two hand-kept signals for one fact.</p>
 */
public enum AdrStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED,
    DEPRECATED,
    SUPERSEDED
}
