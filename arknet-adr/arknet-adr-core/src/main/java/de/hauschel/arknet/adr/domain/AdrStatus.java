// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

/**
 * Lifecycle state of an {@link Adr}.
 *
 * <p>Kept intentionally minimal: a decision is either freshly {@link #PROPOSED} or has been
 * {@link #ACCEPTED}. The shipped ontology ({@code arkarch:ADRStatus}) and the SHACL shape's
 * {@code sh:in} list additionally admit {@code Rejected}, {@code Deprecated} and
 * {@code Superseded}; implementing only a subset here is the same deliberate choice
 * {@code RequirementStatus} makes for the requirements lifecycle - a tool must never report a value
 * it would then refuse. The remaining three are deferred, not forgotten.</p>
 */
public enum AdrStatus {
    PROPOSED,
    ACCEPTED
}
