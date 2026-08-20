// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;

/** Invariants of the {@link Actor} value object. */
class ActorTest {

    private static final ActorId ID = new ActorId(ResourceId.of("https://w3id.org/arknet/id/actor-1"));
    private static final ActorCode CODE = new ActorCode("ACTOR-1");

    @Test
    void rejectsNullMandatoryFields() {
        assertThrows(NullPointerException.class,
                () -> new Actor(null, CODE, ActorType.HUMAN, "Sachbearbeiter", null));
        assertThrows(NullPointerException.class,
                () -> new Actor(ID, null, ActorType.HUMAN, "Sachbearbeiter", null));
        assertThrows(NullPointerException.class,
                () -> new Actor(ID, CODE, null, "Sachbearbeiter", null));
        assertThrows(NullPointerException.class,
                () -> new Actor(ID, CODE, ActorType.HUMAN, null, null));
    }

    @Test
    void rejectsABlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new Actor(ID, CODE, ActorType.HUMAN, "  ", null));
    }

    /**
     * Optional means absent, never present-but-empty: a blank description is a caller mistake, and
     * accepting it would put a meaningless {@code arknet:description} triple in the store.
     */
    @Test
    void rejectsABlankDescriptionWhenPresent() {
        assertThrows(IllegalArgumentException.class,
                () -> new Actor(ID, CODE, ActorType.HUMAN, "Sachbearbeiter", "  "));
    }

    @Test
    void acceptsAnAbsentDescription() {
        Actor actor = new Actor(ID, CODE, ActorType.GROUP, "Fachbereich Vertrieb", null);

        assertNull(actor.description());
        assertEquals(ActorType.GROUP, actor.type());
        assertEquals("Fachbereich Vertrieb", actor.name());
    }

    /**
     * A regulator or a business unit that never touches the system is an actor too - the four types
     * are all equally supported, none is the "real" one and none needs a use case to exist.
     */
    @Test
    void acceptsEveryType() {
        for (ActorType type : ActorType.values()) {
            Actor actor = new Actor(ID, CODE, type, "Some actor", "Some description");
            assertEquals(type, actor.type());
        }
    }
}
