// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;

/** Invariants of the {@link Role} value object. */
class RoleTest {

    private static final RoleId ID = new RoleId(ResourceId.of("https://w3id.org/arknet/id/role-1"));
    private static final RoleCode CODE = new RoleCode("ROLE-1");
    private static final ActorId ACTOR_1 = new ActorId(ResourceId.of("https://w3id.org/arknet/id/actor-1"));
    private static final ActorId ACTOR_2 = new ActorId(ResourceId.of("https://w3id.org/arknet/id/actor-2"));

    @Test
    void rejectsNullMandatoryFields() {
        assertThrows(NullPointerException.class,
                () -> new Role(null, CODE, "Requirements Engineer", null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new Role(ID, null, "Requirements Engineer", null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new Role(ID, CODE, null, null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new Role(ID, CODE, "Requirements Engineer", null, null));
    }

    @Test
    void rejectsABlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new Role(ID, CODE, "  ", null, List.of()));
    }

    /**
     * Optional means absent, never present-but-empty - mirrors {@code ActorTest}'s own note.
     */
    @Test
    void rejectsABlankDescriptionWhenPresent() {
        assertThrows(IllegalArgumentException.class,
                () -> new Role(ID, CODE, "Requirements Engineer", "  ", List.of()));
    }

    @Test
    void rejectsANullActorIdentityInFilledBy() {
        List<ActorId> withNull = new ArrayList<>();
        withNull.add(ACTOR_1);
        withNull.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> new Role(ID, CODE, "Requirements Engineer", null, withNull));
    }

    /** Unfilled is a legitimate, common state (TERM-21/FR-7), not an error. */
    @Test
    void acceptsAnEmptyFilledByList() {
        Role role = new Role(ID, CODE, "Requirements Engineer", null, List.of());

        assertTrue(role.filledBy().isEmpty());
        assertNull(role.description());
    }

    /**
     * {@code arkproc:filledBy} is an RDF edge: two identical triples read back as one, so the
     * aggregate's own equality should not depend on whether a caller happened to name the same
     * actor twice.
     */
    @Test
    void deduplicatesFilledBy() {
        Role role = new Role(ID, CODE, "Requirements Engineer", null, List.of(ACTOR_1, ACTOR_2, ACTOR_1));

        assertEquals(List.of(ACTOR_1, ACTOR_2), role.filledBy());
    }

    /**
     * {@code arkproc:filledBy} is a set in the store, read back without a promised solution order,
     * so the order a caller happened to name the occupants in must not survive into the aggregate -
     * otherwise re-stating the same occupancy differently ordered would read as a change and cost a
     * PROV revision behind which nothing changed ({@code RoleService}'s no-op contract).
     */
    @Test
    void ordersFilledByCanonicallySoTheCallersOrderNeverReachesEquality() {
        Role oneWayRound = new Role(ID, CODE, "Requirements Engineer", null, List.of(ACTOR_1, ACTOR_2));
        Role theOther = new Role(ID, CODE, "Requirements Engineer", null, List.of(ACTOR_2, ACTOR_1));

        assertEquals(List.of(ACTOR_1, ACTOR_2), theOther.filledBy());
        assertEquals(oneWayRound, theOther);
    }

    @Test
    void acceptsSeveralOccupants() {
        Role role = new Role(ID, CODE, "Requirements Engineer", "Writes and maintains requirements.",
                List.of(ACTOR_1, ACTOR_2));

        assertEquals(2, role.filledBy().size());
        assertTrue(role.filledBy().containsAll(List.of(ACTOR_1, ACTOR_2)));
    }
}
