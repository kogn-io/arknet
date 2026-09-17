// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the three answer shapes every writing tool composes from: the closing project line, the
 * short link/unlink confirmation and the wholesale-list diff line. One test per shape, plus the
 * two cases the shapes exist for - a project without a registered label still gets named, and an
 * unchanged list field costs no line.
 */
class WriteResponseTest {

    private static final ProjectId PROJECT = new ProjectId("11111111-1111-1111-1111-111111111111");

    @Test
    void closesTheAnswerWithTheProjectName() {
        ResolvedProject project = new ResolvedProject(PROJECT, "en", List.of(), "arknet");

        String answer = WriteResponse.withProject("BC-1 OrderManagement (Owns orders.)", project);

        assertEquals("BC-1 OrderManagement (Owns orders.)\n\nproject: arknet", answer);
        assertTrue(answer.endsWith("project: arknet"), answer);
    }

    /**
     * The point of the project line is that no writing answer leaves the project unnamed - a
     * resolution without a registered label therefore falls back to the id rather than to nothing.
     */
    @Test
    void namesTheProjectByItsIdWhenNoLabelCameAlong() {
        String answer = WriteResponse.withProject("BC-1", new ResolvedProject(PROJECT, "en"));

        assertEquals("BC-1\n\nproject: " + PROJECT.value(), answer);
    }

    @Test
    void rendersTheShortLinkConfirmation() {
        assertEquals("linked FR-3 -> TERM-7 (usesTerm)", WriteResponse.linked("FR-3", "TERM-7", "usesTerm"));
    }

    @Test
    void rendersTheShortUnlinkConfirmation() {
        assertEquals("unlinked FR-3 -> TERM-7 (usesTerm)", WriteResponse.unlinked("FR-3", "TERM-7", "usesTerm"));
    }

    @Test
    void rendersWhatLeftAndWhatJoinedAListField() {
        String diff = WriteResponse.listFieldDiff("usesTerm", List.of("TERM-22", "TERM-3"),
                List.of("TERM-3", "TERM-9"));

        assertEquals("usesTerm: removed TERM-22, added TERM-9", diff);
    }

    @Test
    void namesOnlyTheSideThatChanged() {
        assertEquals("usesTerm: added TERM-9",
                WriteResponse.listFieldDiff("usesTerm", List.of(), List.of("TERM-9")));
        assertEquals("usesTerm: removed TERM-9",
                WriteResponse.listFieldDiff("usesTerm", List.of("TERM-9"), List.of()));
    }

    /** An unchanged field costs no line - reordering it is not a change either. */
    @Test
    void staysSilentWhenTheFieldHoldsWhatItHeldBefore() {
        assertEquals("", WriteResponse.listFieldDiff("usesTerm", List.of("TERM-3", "TERM-9"),
                List.of("TERM-9", "TERM-3")));
    }

    /** A list field is a set of edges: a duplicate entry is not a change. */
    @Test
    void treatsADuplicateEntryAsOneEntry() {
        assertEquals("", WriteResponse.listFieldDiff("usesTerm", List.of("TERM-3"),
                List.of("TERM-3", "TERM-3")));
    }

    @Test
    void rendersWhatLeftAndWhatJoinedByCountAlone() {
        assertEquals("mainStep: removed 1, added 2", WriteResponse.countDiff("mainStep", 1, 2));
    }

    @Test
    void countDiffNamesOnlyTheSideThatChanged() {
        assertEquals("mainStep: added 2", WriteResponse.countDiff("mainStep", 0, 2));
        assertEquals("mainStep: removed 1", WriteResponse.countDiff("mainStep", 1, 0));
    }

    /** An unchanged field costs no line, the same convention {@link WriteResponse#listFieldDiff} follows. */
    @Test
    void countDiffStaysSilentWhenNothingChanged() {
        assertEquals("", WriteResponse.countDiff("mainStep", 0, 0));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> WriteResponse.withProject(null,
                new ResolvedProject(PROJECT, "en")));
        assertThrows(NullPointerException.class, () -> WriteResponse.withProject("BC-1", null));
        assertThrows(NullPointerException.class, () -> WriteResponse.linked(null, "TERM-7", "usesTerm"));
        assertThrows(NullPointerException.class, () -> WriteResponse.unlinked("FR-3", null, "usesTerm"));
        assertThrows(NullPointerException.class, () -> WriteResponse.listFieldDiff("usesTerm", null, List.of()));
        assertThrows(NullPointerException.class, () -> WriteResponse.countDiff(null, 1, 1));
    }
}
