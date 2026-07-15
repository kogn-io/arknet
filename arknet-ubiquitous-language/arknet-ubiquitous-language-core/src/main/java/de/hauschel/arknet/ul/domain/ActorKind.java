package de.hauschel.arknet.ul.domain;

/**
 * Kind of {@link ActorFacet}: whether the actor is a human or a system.
 *
 * <p>{@code HUMAN} maps to {@code arkproc:HumanActor}, {@code SYSTEM} maps to
 * {@code arkproc:SystemActor}.</p>
 */
public enum ActorKind {
    HUMAN,
    SYSTEM
}
