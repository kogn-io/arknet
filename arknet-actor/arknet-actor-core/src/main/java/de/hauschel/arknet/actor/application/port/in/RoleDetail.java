// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.Role;

/**
 * What every driving port of the role resource type hands back: the role itself plus its
 * {@code arkproc:filledBy} occupants already resolved to their business code and name - mirrors
 * {@code AdrDetail}'s "resolve once, render everywhere" shape.
 *
 * <p><strong>Why the resolution happens here and not at the caller.</strong> {@link Role#filledBy()}
 * carries opaque {@link de.hauschel.arknet.actor.domain.ActorId}s, and a human who typed
 * {@code ACTOR-1} into {@code filledBy} expects to see {@code ACTOR-1 (Sachbearbeiter)} again, not
 * an IRI they cannot re-type. Unlike a genuinely cross-bounded-context reference, resolving it is
 * this same hexagon's own job (see {@code RoleService}'s class-level note on why it depends on
 * {@code ActorRepository} directly), so the application service does it once and every driving
 * adapter renders the same projection.</p>
 *
 * @param role           the role itself, with its opaque {@code filledBy} identities intact
 * @param filledByActors {@link #role}'s {@code filledBy} occupants, each resolved to its current
 *                       business code and name; an identity the read path cannot materialise is
 *                       simply absent rather than an error. In practice that means a store-first
 *                       actor written without an {@code arknet:name} - a mandatory join of
 *                       {@code ActorRepository#findAllByIds}, and the very asymmetry
 *                       {@code findAllCodes} exists for (kogn-io/arknet#360). A <em>deleted</em>
 *                       occupant is the theoretical second case only: {@code actor_delete}'s
 *                       reference guard refuses to remove an actor a role still occupies. Never
 *                       {@code null}
 */
public record RoleDetail(Role role, List<FilledByActor> filledByActors) {

    public RoleDetail {
        Objects.requireNonNull(role, "role");
        filledByActors = filledByActors == null ? List.of() : List.copyOf(filledByActors);
    }

    /**
     * One resolved {@code filledBy} occupant: just enough to render who fills a role, not the full
     * {@code Actor} aggregate - the same slim-projection reasoning
     * {@link de.hauschel.arknet.actor.application.port.out.ActorRepository.ActorProjection}
     * follows, plus the name a display actually needs.
     *
     * @param code the occupant's business code (e.g. {@code ACTOR-1})
     * @param name the occupant's current name
     */
    public record FilledByActor(ActorCode code, String name) {

        public FilledByActor {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
        }
    }
}
