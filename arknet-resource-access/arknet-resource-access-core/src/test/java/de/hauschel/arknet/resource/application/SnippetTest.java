// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The excerpt a text hit shows around its match. */
class SnippetTest {

    @Test
    void keepsTheWholeTextWhenItIsShorterThanTheRadiusOnEachSide() {
        assertEquals("Login succeeds", Snippet.around("Login succeeds", "succeeds"));
    }

    @Test
    void addsAnEllipsisOnBothSidesWhenBothAreTruncated() {
        String snippet = Snippet.around("a".repeat(60) + "NEEDLE" + "b".repeat(60), "NEEDLE");

        assertTrue(snippet.startsWith("...") && snippet.endsWith("...") && snippet.contains("NEEDLE"), snippet);
    }

    @Test
    void addsNoLeadingEllipsisWhenTheMatchIsNearTheStart() {
        String snippet = Snippet.around("NEEDLE" + "b".repeat(60), "NEEDLE");

        assertFalse(snippet.startsWith("..."), snippet);
        assertTrue(snippet.endsWith("..."), snippet);
    }

    @Test
    void collapsesNewlinesToSpaces() {
        String snippet = Snippet.around("first line\nsecond line", "second");

        assertEquals("first line second line", snippet);
    }

    @Test
    void matchesCaseInsensitively() {
        assertTrue(Snippet.around("Login succeeds", "LOGIN").contains("Login"));
    }

    @Test
    void showsTheStartOfTheTextWhenTheCaseFoldingsDisagree() {
        assertEquals("abc", Snippet.around("abc", "zzz"));
    }
}
