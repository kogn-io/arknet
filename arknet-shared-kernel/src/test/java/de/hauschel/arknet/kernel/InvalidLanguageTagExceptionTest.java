// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.IllformedLocaleException;

import org.junit.jupiter.api.Test;

/** Domain invariant tests for {@link InvalidLanguageTagException}: round-trips {@code tag()}/{@code getCause()}. */
class InvalidLanguageTagExceptionTest {

    @Test
    void roundTripsTheIllFormedTagAndItsCause() {
        IllformedLocaleException cause = new IllformedLocaleException("Invalid subtag: de_DE");

        InvalidLanguageTagException e = new InvalidLanguageTagException("de_DE", cause);

        assertEquals("de_DE", e.tag());
        assertSame(cause, e.getCause());
        assertTrue(e.getMessage().contains("de_DE"));
    }
}
