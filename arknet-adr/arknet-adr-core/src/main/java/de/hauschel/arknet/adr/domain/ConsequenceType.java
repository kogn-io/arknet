// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

/**
 * The kind of effect a {@link Consequence} of an {@link Adr} describes.
 *
 * <p>Mirrors the three {@code arkarch:ConsequenceType} individuals ({@code arkarch:Positive}/
 * {@code arkarch:Negative}/{@code arkarch:Neutral}, kogn-io/arknet#357). {@link #NEUTRAL} is also
 * the type the out-adapter's legacy-literal fallback substitutes for a store-first
 * {@code arkarch:adrConsequences} string that predates this structured resource - a plain,
 * unclassified consequence text carries no positive/negative signal of its own, and {@link #NEUTRAL}
 * is the honest reading rather than a guess.</p>
 */
public enum ConsequenceType {
    POSITIVE,
    NEGATIVE,
    NEUTRAL
}
