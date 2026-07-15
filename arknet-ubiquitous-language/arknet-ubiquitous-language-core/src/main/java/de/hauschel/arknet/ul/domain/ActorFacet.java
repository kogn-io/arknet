package de.hauschel.arknet.ul.domain;

import java.util.Objects;

/**
 * Optionale Actor-Facette eines Glossar-Concepts (Weg A aus #45): derselbe
 * skos:Concept ist zusaetzlich ein arkproc:Actor. role -&gt; arkproc:actorRole
 * (fachliche Rolle im BC); kein actorName, da der skos:prefLabel bereits der
 * Name ist (keine Drift).
 *
 * @param kind whether the actor is a human or a system; maps to
 *             {@code arkproc:HumanActor}/{@code arkproc:SystemActor}
 * @param role the actor's role in the bounded context; maps to
 *             {@code arkproc:actorRole}. Optional (may be {@code null})
 */
public record ActorFacet(ActorKind kind, String role) {

    public ActorFacet {
        Objects.requireNonNull(kind, "kind");
        if (role != null && role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }
}
