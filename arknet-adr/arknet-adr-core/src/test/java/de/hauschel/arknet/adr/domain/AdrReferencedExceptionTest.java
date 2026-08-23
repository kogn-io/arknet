// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.adr.domain.AdrReferencedException.Reference;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * The rejection has to be actionable: it names the decisions in the way, and the remedy it offers
 * has to match the edges that were actually found - a hint for an edge the caller does not have is
 * noise, and a hint that claims a tool where none exists is worse.
 */
class AdrReferencedExceptionTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final AdrCode CODE = new AdrCode("ADR-1");

    @Test
    void namesEveryReferrerWithTheEdgeItPointsWith() {
        AdrReferencedException thrown = new AdrReferencedException(PROJECT, CODE, List.of(
                new Reference(new AdrCode("ADR-2"), AdrReferencedException.SUPERSEDES),
                new Reference(new AdrCode("ADR-3"), AdrReferencedException.RELATED_TO)));

        assertEquals(CODE, thrown.adrCode());
        assertEquals(PROJECT, thrown.projectId());
        assertEquals(2, thrown.references().size());
        assertTrue(thrown.getMessage().contains("ADR-2 (supersedes)"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("ADR-3 (relatedTo)"), thrown.getMessage());
    }

    @Test
    void offersAdrUpdateOnlyWhereARelatedToEdgeWasFound() {
        AdrReferencedException relatedTo = new AdrReferencedException(PROJECT, CODE,
                List.of(new Reference(new AdrCode("ADR-3"), AdrReferencedException.RELATED_TO)));

        assertTrue(relatedTo.getMessage().contains("adr_update"), relatedTo.getMessage());
        assertFalse(relatedTo.getMessage().contains("superseding decision"), relatedTo.getMessage());
    }

    /**
     * There is no tool that removes a {@code supersedes} edge, so the message must not invent one -
     * it says what is true: that edge goes away with the superseding decision itself.
     */
    @Test
    void doesNotClaimAToolForASupersedesEdge() {
        AdrReferencedException supersedes = new AdrReferencedException(PROJECT, CODE,
                List.of(new Reference(new AdrCode("ADR-2"), AdrReferencedException.SUPERSEDES)));

        assertTrue(supersedes.getMessage().contains("no removal tool"), supersedes.getMessage());
        assertFalse(supersedes.getMessage().contains("adr_update"), supersedes.getMessage());
    }

    /** Nothing rejected the delete, so there is nothing to report - a caller bug, not a message. */
    @Test
    void refusesToBeConstructedWithoutAnyReferrer() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdrReferencedException(PROJECT, CODE, List.of()));
    }

    @Test
    void keepsItsOwnCopyOfTheReferrers() {
        List<Reference> mutable = new ArrayList<>(
                List.of(new Reference(new AdrCode("ADR-2"), AdrReferencedException.SUPERSEDES)));
        AdrReferencedException thrown = new AdrReferencedException(PROJECT, CODE, mutable);

        mutable.clear();

        assertEquals(1, thrown.references().size());
    }
}
