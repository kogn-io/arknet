// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The one renumbering rule {@link RemovedPositions} exists to hold (kogn-io/arknet#483): the domain
 * and the write path both read the post-removal number of a surviving position from here.
 */
class RemovedPositionsTest {

    @Test
    void survivingPositionMovesUpByTheNumberOfRemovedPositionsBelowIt() {
        RemovedPositions removed = new RemovedPositions(Set.of(1, 3));

        assertEquals(OptionalInt.of(1), removed.survivingPositionOf(2));
        assertEquals(OptionalInt.of(2), removed.survivingPositionOf(4));
        assertEquals(OptionalInt.of(3), removed.survivingPositionOf(5));
    }

    @Test
    void aRemovedPositionHasNoSurvivingPosition() {
        RemovedPositions removed = new RemovedPositions(Set.of(1, 3));

        assertEquals(OptionalInt.empty(), removed.survivingPositionOf(1));
        assertEquals(OptionalInt.empty(), removed.survivingPositionOf(3));
        assertTrue(removed.contains(3));
        assertFalse(removed.contains(2));
    }

    @Test
    void noneKeepsEveryPositionWhereItIs() {
        assertTrue(RemovedPositions.NONE.isEmpty());
        assertEquals(OptionalInt.of(7), RemovedPositions.NONE.survivingPositionOf(7));
    }

    @Test
    void rejectsAPositionBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new RemovedPositions(Set.of(0)));
        assertThrows(IllegalArgumentException.class, () -> new RemovedPositions(Set.of(2, -1)));
    }
}
