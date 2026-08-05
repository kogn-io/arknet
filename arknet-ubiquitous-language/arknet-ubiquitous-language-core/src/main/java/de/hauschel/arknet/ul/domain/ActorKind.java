// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

/**
 * Kind of {@link ActorFacet}: whether the actor is a human, a system, or a legal person.
 *
 * <p>{@code HUMAN} maps to {@code arkproc:HumanActor}, {@code SYSTEM} maps to
 * {@code arkproc:SystemActor}, {@code LEGAL} maps to {@code arkproc:LegalActor}.</p>
 */
public enum ActorKind {
    HUMAN,
    SYSTEM,
    LEGAL
}
