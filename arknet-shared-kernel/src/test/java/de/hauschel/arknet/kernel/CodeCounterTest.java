// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CodeCounter} - the parse every bounded context's code counter now shares
 * (kogn-io/arknet#360), pinned here rather than seven times over in the services that call it.
 *
 * <p>The cases that matter are the ones the old per-context copies disagreed on or never saw: a
 * dash-less prefix, a code from a neighbouring counter, and store-first rubbish that no
 * write path would ever have produced.</p>
 */
class CodeCounterTest {

    @Test
    void readsTheNumberAfterADashedPrefix() {
        assertEquals(7, CodeCounter.runningNumber("TERM-", "TERM-7"));
    }

    /**
     * The case a single shared parse had to swallow: use-case codes carry no separator at all, and
     * used to need their own "skip the leading letters" scan.
     */
    @Test
    void readsTheNumberAfterADashlessPrefix() {
        assertEquals(12, CodeCounter.runningNumber("UC", "UC12"));
    }

    /**
     * What replaces the domain-type filter in {@code RequirementService}/{@code ConstraintService}
     * (kogn-io/arknet#360). Anchored at the start, so the shorter prefix does not swallow the longer
     * one's codes - which is the whole reason the two counters may run side by side.
     */
    @Test
    void ignoresACodeOfANeighbouringCounter() {
        assertEquals(0, CodeCounter.runningNumber("FR-", "NFR-3"));
        assertEquals(0, CodeCounter.runningNumber("NFR-", "FR-3"));
        assertEquals(0, CodeCounter.runningNumber("TCON-", "BCON-2"));
    }

    @Test
    void countsNothingForACodeThatCarriesNoNumber() {
        assertEquals(0, CodeCounter.runningNumber("TERM-", "TERM-"));
        assertEquals(0, CodeCounter.runningNumber("TERM-", "TERM-x"));
        assertEquals(0, CodeCounter.runningNumber("TERM-", "TERM-1a"));
        assertEquals(0, CodeCounter.runningNumber("TERM-", ""));
        assertEquals(0, CodeCounter.runningNumber("TERM-", null));
    }

    /**
     * A signed or oversized number is store-first rubbish like any other unreadable code, and must
     * lose the same way: {@code parseInt} alone would have accepted the sign and returned something
     * that quietly sorts below every real code.
     */
    @Test
    void countsNothingForASignedOrOversizedNumber() {
        assertEquals(0, CodeCounter.runningNumber("TERM-", "TERM--1"));
        assertEquals(0, CodeCounter.runningNumber("TERM-", "TERM-+1"));
        assertEquals(0, CodeCounter.runningNumber("TERM-", "TERM-99999999999999999999"));
    }

    @Test
    void highestIsZeroForNoCodes() {
        assertEquals(0, CodeCounter.highestRunningNumber("TERM-", List.of(), Function.identity()));
    }

    @Test
    void highestIgnoresOrderAndUnreadableCodes() {
        List<String> codes = List.of("TERM-3", "TERM-11", "TERM-broken", "OTHER-99", "TERM-2");

        assertEquals(11, CodeCounter.highestRunningNumber("TERM-", codes, Function.identity()));
    }

    /** The typed call site every service uses: a value object plus its accessor. */
    @Test
    void highestReadsThroughTheCallersCodeType() {
        record Code(String value) {
        }
        List<Code> codes = List.of(new Code("ACTOR-1"), new Code("ACTOR-4"));

        assertEquals(4, CodeCounter.highestRunningNumber("ACTOR-", codes, Code::value));
    }

    @Test
    void rejectsMissingArguments() {
        assertThrows(NullPointerException.class, () -> CodeCounter.runningNumber(null, "TERM-1"));
        assertThrows(NullPointerException.class,
                () -> CodeCounter.highestRunningNumber(null, List.of(), Function.identity()));
        assertThrows(NullPointerException.class,
                () -> CodeCounter.highestRunningNumber("TERM-", null, Function.identity()));
        assertThrows(NullPointerException.class,
                () -> CodeCounter.highestRunningNumber("TERM-", List.of(), null));
    }
}
