// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

/**
 * Kind of an {@link Actor}: what sort of thing acts or holds an interest.
 *
 * <ul>
 *   <li>{@link #HUMAN} - a natural person ({@code arkproc:HumanActor}).</li>
 *   <li>{@link #SYSTEM} - an external system or service ({@code arkproc:SystemActor}).</li>
 *   <li>{@link #LEGAL} - a legal person: an organization, company or association
 *       ({@code arkproc:LegalActor}).</li>
 *   <li>{@link #GROUP} - a group without a legal form of its own: a department, a committee, a
 *       team ({@code arkproc:GroupActor}).</li>
 * </ul>
 *
 * <p><strong>No code prefix here, deliberately</strong> - unlike
 * {@code ConstraintType}/{@code RequirementType}, which each carry their own {@code idPrefix()}
 * and therefore their own running number. Every actor is numbered {@code ACTOR-N} from one shared
 * counter regardless of type, because the type is a classification of an actor, not a different
 * kind of thing being counted: retyping a stakeholder from {@link #GROUP} to {@link #LEGAL} is a
 * correction of the same actor, whereas a technical and a business constraint are two distinct
 * registers. The type does decide the {@code rdf:type} the out-adapter writes, and it is fixed at
 * creation for exactly the reason a code is: everything referring to an actor refers to
 * {@code ACTOR-N}, and nothing would follow a renumbering.</p>
 */
public enum ActorType {

    /** A natural person; persisted as {@code arkproc:HumanActor}. */
    HUMAN,

    /** An external system or service; persisted as {@code arkproc:SystemActor}. */
    SYSTEM,

    /** A legal person (organization, company, association); persisted as {@code arkproc:LegalActor}. */
    LEGAL,

    /** A group without a legal form of its own (department, committee); persisted as {@code arkproc:GroupActor}. */
    GROUP
}
