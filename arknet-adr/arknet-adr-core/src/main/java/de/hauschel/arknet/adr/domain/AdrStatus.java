// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

/**
 * Lifecycle state of an {@link Adr}.
 *
 * <p>Implements four of the five ontology values: a decision starts {@link #PROPOSED}, and from
 * there may become {@link #ACCEPTED}, or {@link #REJECTED} - or, once accepted, {@link #DEPRECATED}.
 * See {@link Adr#accept()}/{@link Adr#reject()}/{@link Adr#deprecate()} for the exact legal
 * transitions; none of the three resurrects into either of the others beyond those methods, this is
 * a small, terminal-leaning subset rather than a full state machine.</p>
 *
 * <p>{@link #SUPERSEDED} is deliberately <strong>not</strong> a value here, even though the shipped
 * ontology ({@code arkarch:ADRStatus}) and the SHACL shape's {@code sh:in} list admit it as a fifth
 * individual: it stays derived-only from the {@code arkarch:supersedes}/{@code supersededBy}
 * reverse-read {@code adr_supersede} already performs (see {@link Adr#supersede}), the same reason
 * this codebase never materialises {@code supersededBy} itself as a second physical triple. Adding a
 * {@code SUPERSEDED} status value would invite exactly that duplication - two independently
 * maintained signals for one fact - so {@code adr_supersede} continues to leave the superseded
 * decision's own {@link Adr#status()} field untouched.</p>
 */
public enum AdrStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED,
    DEPRECATED
}
