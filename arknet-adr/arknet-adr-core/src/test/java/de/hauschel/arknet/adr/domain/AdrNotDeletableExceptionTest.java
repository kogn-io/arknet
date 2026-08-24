// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The refusal is the teaching, so its text is behaviour: each status has to name the path that fits
 * it rather than share one generic "no".
 */
class AdrNotDeletableExceptionTest {

    private static final AdrCode CODE = new AdrCode("ADR-1");

    @Test
    void anAcceptedDecisionIsPointedAtItsSuccessorPaths() {
        AdrNotDeletableException thrown = new AdrNotDeletableException(CODE, AdrStatus.ACCEPTED);

        assertEquals(AdrStatus.ACCEPTED, thrown.status());
        assertEquals(CODE, thrown.adrCode());
        assertTrue(thrown.getMessage().contains("adr_supersede"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("adr_set_status DEPRECATED"), thrown.getMessage());
    }

    /**
     * The distinction the whole staging exists for: rejecting an option is a verdict on the option,
     * not a way to get rid of a record recorded by accident.
     */
    @Test
    void aRejectedDecisionIsToldWhyTurningAnOptionDownIsWorthKeeping() {
        AdrNotDeletableException thrown = new AdrNotDeletableException(CODE, AdrStatus.REJECTED);

        assertTrue(thrown.getMessage().contains("considered and turned down"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("created by mistake"), thrown.getMessage());
    }

    @Test
    void aDeprecatedDecisionIsToldThatDeprecationIsTheEndOfTheLifecycle() {
        AdrNotDeletableException thrown = new AdrNotDeletableException(CODE, AdrStatus.DEPRECATED);

        assertTrue(thrown.getMessage().contains("already marked obsolete"), thrown.getMessage());
    }

    /**
     * kogn-io/arknet#357's fifth status gets its own remedy text rather than falling through - a
     * decision already replaced by a successor is told that, not pointed at {@code adr_supersede}
     * again (which is how it got here) or at {@code adr_set_status DEPRECATED} (which no longer
     * applies to it).
     */
    @Test
    void aSupersededDecisionIsToldItAlreadyHasASuccessor() {
        AdrNotDeletableException thrown = new AdrNotDeletableException(CODE, AdrStatus.SUPERSEDED);

        assertEquals(AdrStatus.SUPERSEDED, thrown.status());
        assertTrue(thrown.getMessage().contains("already been replaced"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("created by mistake"), thrown.getMessage());
    }

    /** A proposal is deletable, so constructing this refusal for one is a caller bug, not a message. */
    @Test
    void refusesToBeConstructedForAProposedDecision() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdrNotDeletableException(CODE, AdrStatus.PROPOSED));
    }
}
