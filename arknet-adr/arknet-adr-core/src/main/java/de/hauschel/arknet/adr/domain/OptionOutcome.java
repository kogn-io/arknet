// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

/**
 * Whether a {@link ConsideredOption} of an {@link Adr} was chosen or rejected.
 *
 * <p>Mirrors the two {@code arkarch:OptionOutcome} individuals ({@code arkarch:Chosen}/
 * {@code arkarch:Rejected}, kogn-io/arknet#357). Unlike {@link ConsequenceType}, this field is
 * nullable on {@link ConsideredOption}: the out-adapter's legacy-literal fallback synthesises an
 * option with no outcome at all for a store-first (ADR-005) {@code arkarch:adrAlternatives} string
 * that predates this structured resource (see {@link ConsideredOption}'s javadoc) - there is no
 * honest default to guess between chosen and rejected the way {@link ConsequenceType#NEUTRAL} is
 * for an unclassified consequence. Every option a caller records through {@code adr_add}/
 * {@code adr_update} carries one of these two values; only the legacy fallback ever omits it.</p>
 */
public enum OptionOutcome {
    CHOSEN,
    REJECTED
}
